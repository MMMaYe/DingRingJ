package com.dingring.infrastructure.rag;

import com.dingring.common.util.LogHelper;
import com.dingring.domain.service.RagService;
import com.dingring.domain.service.Reranker;
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
import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG 检索服务实现（Phase E）。
 * <p>检索流程：向量召回 Top-20 + LLM 重排 Top-5。
 * <p>绑定过滤：metadata.kbId IN (群绑定的知识库 ID 列表)，只有群绑定的库才会注入该群。
 * <p>容错：任何环节失败不阻塞群聊主流程，返回空字符串。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "dingring.rag.enabled", havingValue = "true", matchIfMissing = true)
public class SaaRagService implements RagService {

    private final VectorStore vectorStore;
    private final Reranker reranker;

    /** 向量召回候选数 */
    private static final int RECALL_TOP_K = 20;
    /** 重排后返回的最终结果数 */
    private static final int RERANK_TOP_K = 5;
    /** 相似度阈值（低于此值不召回） */
    private static final double SIMILARITY_THRESHOLD = 0.7;

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
                        "向量召回无结果", "query={} kbIds={}", query.substring(0, Math.min(50, query.length())), kbIds);
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
                    query.substring(0, Math.min(50, query.length())), kbIds, candidates.size(), topResults.size());

            return formatResults(topResults);
        } catch (Exception e) {
            LogHelper.printWarnLog(SaaRagService.class, "retrieve", "RAG_RETRIEVE",
                    "RAG检索失败降级为空", "query={} kbIds={} 错误: {}",
                    query.substring(0, Math.min(50, query.length())), kbIds, e.getMessage());
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
