package com.dingring.infrastructure.rag;

import com.dingring.common.util.LogHelper;
import com.dingring.domain.service.RagService;
import com.dingring.domain.service.Reranker;
import com.dingring.domain.service.RetrievalCandidate;
import com.dingring.domain.service.RetrievalResult;
import com.dingring.infrastructure.rag.cache.RedisRetrievalCache;
import com.dingring.infrastructure.rag.config.RagProperties;
import com.dingring.infrastructure.rag.retrieval.LexicalRetriever;
import com.dingring.infrastructure.rag.retrieval.PgChunkReader;
import com.dingring.infrastructure.rag.retrieval.QueryNormalizer;
import com.dingring.infrastructure.rag.retrieval.RrfFuser;
import com.dingring.infrastructure.aop.Event;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RAG 检索服务实现（P3 多阶段召回，7.3）。
 * <p>主链路（retrieveStructured）：dense（pgvector HNSW Top-30）∥ lexical（pg_trgm Top-30）
 * → RRF 融合 Top-40 → 邻居扩展（前后兄弟 chunk 补充候选池）→ rerank（Qwen3-Reranker-4B，
 * 失败回退 RRF 顺序）→ relevance 阈值过滤 → MMR 多样性选择 Top-15 → Redis 双级缓存。
 * <p>缓存：目录（rag:retrieval:{groupId}:{queryHash}，TTL 1 天）命中则跳过整个管道；
 * 目录级短 TTL 包住 chunk 全文级长 TTL（TTL 2 天）。
 * <p>容错：每个通道独立降级（lexical 挂→单 dense；rerank 挂→RRF 顺序；
 * Redis 挂→直查 PG），检索结果整体异常才返回空，绝不阻塞群聊主流程。
 * <p>legacy retrieve 保留：旧单阶段链路（向量 Top-20 + rerank Top-5），灰度期可切回。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "dingring.rag.enabled", havingValue = "true", matchIfMissing = true)
public class SaaRagService implements RagService {

    private final VectorStore vectorStore;
    private final Reranker reranker;
    private final LexicalRetriever lexicalRetriever;
    private final RrfFuser rrfFuser;
    private final PgChunkReader pgChunkReader;
    private final RedisRetrievalCache retrievalCache;
    private final RagProperties ragProperties;

    /** 向量召回候选数（legacy 链路） */
    private static final int RECALL_TOP_K = 20;
    /** 重排后返回的最终结果数（legacy 链路） */
    private static final int RERANK_TOP_K = 5;
    /** 相似度阈值（低于此值不召回，legacy 链路） */
    private static final double SIMILARITY_THRESHOLD = 0.7;

    // ==================== P3 主链路：多阶段结构化检索 ====================

    @Override
    public RetrievalResult retrieveStructured(String query, List<Long> kbIds, Long groupId) {
        if (query == null || query.isBlank() || kbIds == null || kbIds.isEmpty()) {
            return RetrievalResult.empty(query == null ? "" : query);
        }
        long start = System.currentTimeMillis();
        try {
            // 1. 幂等规范化：入参已由 InjectKbHook 完成指代词改写（topicTitle 上下文在 Hook 层），
            //    这里再规范化一次保证缓存 key 稳定（改写结果与直查 query 的空白形态统一）
            String normalized = QueryNormalizer.normalize(query);
            String searchQuery = normalized;
            String queryHash = QueryNormalizer.cacheKey(normalized);

            // 2. 目录缓存命中：同群同 query 免整条管道
            List<RetrievalCandidate> cached = retrievalCache.getCatalog(groupId, queryHash);
            if (cached != null) {
                LogHelper.printLog(SaaRagService.class, "retrieveStructured", "RAG_RETRIEVE",
                        "目录缓存命中", "groupId={} 候选数={} 延迟={}ms",
                        groupId, cached.size(), System.currentTimeMillis() - start);
                return new RetrievalResult(query, searchQuery, cached, true, null);
            }

            // 3. 双通道并行召回（lexical 内部降级为空时退化为纯 dense）
            List<RetrievalCandidate> dense = denseSearch(searchQuery, kbIds);
            List<RetrievalCandidate> lexical = lexicalRetriever.search(searchQuery, kbIds);

            // 4. RRF 融合（candidateId 去重，双通道排名互补）
            List<RetrievalCandidate> fused = rrfFuser.fuse(dense, lexical);
            if (fused.isEmpty()) {
                LogHelper.printLog(SaaRagService.class, "retrieveStructured", "RAG_RETRIEVE",
                        "双通道融合无结果", "query={} kbIds={} dense={} lexical={}",
                        truncate(searchQuery), kbIds, dense.size(), lexical.size());
                return new RetrievalResult(query, searchQuery, List.of(), false, null);
            }

            // 5. 邻居扩展：按 chunkId 确定性格式补前后兄弟（仅激活活跃版本 chunk）
            List<RetrievalCandidate> expanded = expandNeighbors(fused, kbIds);
            int expandedCount = expanded.size();

            // 6. rerank：失败回退 RRF 顺序（reranker 返回 null）
            List<RetrievalCandidate> ranked;
            String fallbackReason = null;
            Reranker.RerankOutcome outcome =
                    reranker.rerankStructured(searchQuery, expanded);
            if (outcome == null || outcome.ranked().isEmpty()) {
                ranked = expanded;
                fallbackReason = "rerank-fallback-rrf-order";
            } else {
                ranked = outcome.ranked();
            }

            // 7. relevance 阈值过滤（仅 rerank 成功时：RRF 回退分数量纲不同，不做阈值）
            if (fallbackReason == null) {
                ranked = ranked.stream()
                        .filter(c -> c.score() >= ragProperties.getRetrieval().getMinScore())
                        .toList();
            }

            // 8. MMR 多样性选择 Top-N（需要候选向量，从 PG 取回）
            List<RetrievalCandidate> finalList = selectWithMmr(ranked);
            if (finalList.isEmpty()) {
                return new RetrievalResult(query, searchQuery, List.of(), false, fallbackReason);
            }

            // 9. 写 Redis 两级缓存（目录 + 全文；降级静默）
            retrievalCache.putCatalog(groupId, queryHash, finalList);
            retrievalCache.putChunks(chunkContents(finalList));

            // 10. 结构化检索日志（10.1）
            LogHelper.printLog(SaaRagService.class, "retrieveStructured", "RAG_RETRIEVE",
                    "多阶段检索完成", "query={} groupId={} kbIds={} candidateCountDense={} "
                            + "candidateCountLexical={} fusedCount={} expandedCount={} rerankCount={} "
                            + "finalCount={} cacheHit=false fallback={} latencyMs={}",
                    truncate(searchQuery), groupId, kbIds, dense.size(), lexical.size(),
                    fused.size(), expandedCount, ranked.size(), finalList.size(),
                    fallbackReason, System.currentTimeMillis() - start);
            return new RetrievalResult(query, searchQuery, finalList, false, fallbackReason);
        } catch (Exception e) {
            // 整链路兜底：检索失败不阻塞群聊（返回空结果，Injection 层注入空目录）
            LogHelper.printWarnLog(SaaRagService.class, "retrieveStructured", "RAG_RETRIEVE",
                    "多阶段检索失败降级为空", "query={} kbIds={} 错误: {}",
                    truncate(query), kbIds, e.getMessage());
            return RetrievalResult.empty(query);
        }
    }

    /** dense 通道：pgvector HNSW 相似度召回（复用 legacy 参数语义：threshold 0.7） */
    private List<RetrievalCandidate> denseSearch(String query, List<Long> kbIds) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        SearchRequest searchRequest = SearchRequest.builder()
                .query(query)
                .topK(ragProperties.getRetrieval().getDenseTopK())
                .similarityThreshold(SIMILARITY_THRESHOLD)
                .filterExpression(b.in("kbId", kbIds.toArray()).build())
                .build();
        List<Document> documents = vectorStore.similaritySearch(searchRequest);
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        return documents.stream().map(SaaRagService::toCandidate).collect(Collectors.toList());
    }

    /** Document（pgvector 返回）→ 候选（score 即 cosine 相似度 0-1） */
    private static RetrievalCandidate toCandidate(Document doc) {
        Map<String, Object> metadata = doc.getMetadata();
        return new RetrievalCandidate(
                doc.getId(),
                (String) metadata.get("chunkId"),
                doc.getText(),
                (String) metadata.getOrDefault("fileName", ""),
                stringList(metadata.get("headingPath")),
                sourceLocation(metadata),
                doc.getScore() == null ? 0 : doc.getScore(),
                metadata);
    }

    /**
     * 邻居扩展（7.3）：对融合后的候选补前后兄弟 chunk。
     * <p>chunkId = {fileId}-v{version}-{index}（摄入时写入的确定性格式），
     * 兄弟 chunkId 直接字符串构造，PG 批量取回——无索引反查。
     * 只补充缺失候选（已在列表中的不重复添加），补充候选进不了 RRF 排名竞争
     * （天然低优先级，由 rerank 决定去留）。
     */
    private List<RetrievalCandidate> expandNeighbors(List<RetrievalCandidate> fused, List<Long> kbIds) {
        if (!ragProperties.getRetrieval().isNeighborExpansion() || fused.isEmpty()) {
            return fused;
        }
        // 兄弟 chunkId 候选集：fused 每个候选的 index±1
        Map<String, Integer> siblingIds = new LinkedHashMap<>();
        Map<String, RetrievalCandidate> existing = new HashMap<>();
        for (RetrievalCandidate c : fused) {
            if (c.chunkId() == null) {
                continue;
            }
            existing.put(c.chunkId(), c);
            int slash = c.chunkId().lastIndexOf('-');
            String prefix = slash > 0 ? c.chunkId().substring(0, slash + 1) : "";
            if (prefix.isBlank()) {
                continue;
            }
            try {
                int index = Integer.parseInt(c.chunkId().substring(slash + 1));
                siblingIds.put(prefix + (index - 1), index - 1);
                siblingIds.put(prefix + (index + 1), index + 1);
            } catch (NumberFormatException ignore) {
                // chunkId 非新格式（legacy 滑窗切片无 chunkId 规律）：跳过邻居扩展
            }
        }
        siblingIds.keySet().removeAll(existing.keySet());
        if (siblingIds.isEmpty()) {
            return fused;
        }
        // 批量读兄弟 chunk，补进候选池尾部
        Map<String, PgChunkReader.ChunkRecord> records =
                pgChunkReader.findByChunkIds(siblingIds.keySet());
        List<RetrievalCandidate> result = new ArrayList<>(fused);
        for (Map.Entry<String, PgChunkReader.ChunkRecord> entry : records.entrySet()) {
            result.add(toCandidate(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    /** PG 直读记录 → 候选（无通道分数：作为扩展候选，分数量纲待 rerank 重置） */
    private static RetrievalCandidate toCandidate(String chunkId, PgChunkReader.ChunkRecord record) {
        Map<String, Object> metadata = record.metadata();
        return new RetrievalCandidate(
                record.rowId(),
                chunkId,
                record.content(),
                (String) metadata.getOrDefault("fileName", ""),
                stringList(metadata.get("headingPath")),
                sourceLocation(metadata),
                0,
                metadata);
    }

    /** MMR 选择（rerank 回退时退化为简单截断：RRF 分数下多样性选择无意义） */
    private List<RetrievalCandidate> selectWithMmr(List<RetrievalCandidate> ranked) {
        int topK = ragProperties.getRetrieval().getRerankTopK();
        if (ranked.size() <= topK) {
            return ranked;
        }
        // 向量批量取回（MMR 需要；取失败时 selectDiverse 内部仍可用相关性单调截断）
        Map<String, float[]> embeddings = new HashMap<>();
        List<String> rowIds = ranked.stream().map(RetrievalCandidate::candidateId).toList();
        try {
            Map<String, PgChunkReader.ChunkRecord> records = pgChunkReader.findByRowIds(rowIds);
            records.forEach((chunkId, rec) -> {
                if (rec.embedding().length > 0) {
                    embeddings.put(rec.rowId(), rec.embedding());
                }
            });
        } catch (Exception e) {
            LogHelper.printWarnLog(SaaRagService.class, "selectWithMmr", "RAG_RETRIEVE",
                    "MMR 向量取回失败退化为截断", "错误: {}", e.getMessage());
        }
        return rrfFuser.selectDiverse(ranked, embeddings, topK);
    }

    private static Map<String, String> chunkContents(List<RetrievalCandidate> candidates) {
        Map<String, String> contents = new LinkedHashMap<>();
        for (RetrievalCandidate c : candidates) {
            if (c.chunkId() != null && c.content() != null && !c.content().isBlank()) {
                contents.put(c.chunkId(), c.content());
            }
        }
        return contents;
    }

    private static List<String> stringList(Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).collect(Collectors.toList());
        }
        return List.of();
    }

    private static String sourceLocation(Map<String, Object> metadata) {
        Object startLine = metadata.get("sourceStartLine");
        Object endLine = metadata.get("sourceEndLine");
        if (startLine instanceof Number s && endLine instanceof Number e) {
            return "L" + s.longValue() + "-L" + e.longValue();
        }
        return "";
    }

    private static String truncate(String text) {
        return text == null ? "" : text.substring(0, Math.min(50, text.length()));
    }

    // ==================== legacy 单阶段链路（灰度保留） ====================

    @Override
    @Event(eventCode = "RETRIEVE_KB_BASE", eventName = "召回向量化的知识")
    public String retrieve(String query, List<Long> kbIds) {
        // 未绑定任何知识库的群：不注入知识，也省一次无效向量检索
        if (query == null || query.isBlank() || kbIds == null || kbIds.isEmpty()) {
            return "";
        }
        try {
            // 1. 绑定过滤：摄入管道写入的 metadata.kbId 溯源字段，IN 匹配群绑定库
            FilterExpressionBuilder b = new FilterExpressionBuilder();
            FilterExpressionBuilder.Op filter = b.in("kbId", kbIds.toArray());

            // 2. 向量召回 Top-20
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(query)
                    .topK(RECALL_TOP_K)
                    .similarityThreshold(SIMILARITY_THRESHOLD)
                    .filterExpression(filter.build())
                    .build();

            List<Document> candidates = vectorStore.similaritySearch(searchRequest);
            if (candidates == null || candidates.isEmpty()) {
                LogHelper.printLog(SaaRagService.class, "retrieve", "RAG_RETRIEVE",
                        "向量召回无结果", "query={} kbIds={}", truncate(query), kbIds);
                return "";
            }

            // 3. LLM 重排 Top-5
            List<String> candidateTexts = candidates.stream()
                    .map(Document::getText)
                    .collect(Collectors.toList());
            List<Reranker.ScoredDocument> reranked = reranker.rerank(query, candidateTexts);

            // 4. 格式化为文本段
            List<Reranker.ScoredDocument> topResults = reranked.stream()
                    .limit(RERANK_TOP_K)
                    .collect(Collectors.toList());

            LogHelper.printLog(SaaRagService.class, "retrieve", "RAG_RETRIEVE",
                    "检索完成", "query={} kbIds={} 召回={} 重排={}",
                    truncate(query), kbIds, candidates.size(), topResults.size());

            return formatResults(topResults);
        } catch (Exception e) {
            LogHelper.printWarnLog(SaaRagService.class, "retrieve", "RAG_RETRIEVE",
                    "RAG检索失败降级为空", "query={} kbIds={} 错误: {}",
                    truncate(query), kbIds, e.getMessage());
            return "";
        }
    }

    /** 格式化重排结果为 system prompt 注入文本段 */
    private String formatResults(List<Reranker.ScoredDocument> results) {
        if (results.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("知识库检索结果（按相关性排序）：\n");
        for (int i = 0; i < results.size(); i++) {
            Reranker.ScoredDocument doc = results.get(i);
            sb.append(String.format("[%d] (相关性: %.1f)\n", i + 1, doc.score()));
            sb.append(doc.content()).append("\n\n");
        }
        return sb.toString().trim();
    }
}
