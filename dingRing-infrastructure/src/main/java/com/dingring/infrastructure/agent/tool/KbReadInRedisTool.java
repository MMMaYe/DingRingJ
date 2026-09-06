package com.dingring.infrastructure.agent.tool;

import com.dingring.common.util.LogHelper;
import com.dingring.infrastructure.rag.cache.RedisRetrievalCache;
import com.dingring.infrastructure.rag.config.RagProperties;
import com.dingring.infrastructure.rag.retrieval.PgChunkReader;
import com.dingring.infrastructure.rag.splitter.TokenCounter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Redis 知识库读取工具（7.6）：Agent 按需深读知识库 chunk 全文。
 * <p>注入协议的两级结构（7.5）：SystemMessage 只注入目录（≤500 token 预告），
 * 全文按需通过本工具读取（按次 4096 token 预算）——目录级保 prompt 低成本，
 * 全文级保 ReAct 深读能力，两级之间以 chunkId 关联。
 * <p>读路径：Redis MGET（TTL 2 天的全文缓存）→ 未命中部分回 PG（chunkId 直查）。
 * Redis 不可用是常态而非异常：回退 PG，功能不丢只损失缓存加速。
 * <p>安全面：chunkId 仅允许 {fileId}-v{version}-{index} 形态（正则白名单），
 * 拼前缀前校验——工具入参来自 LLM 输出，防注入任意 Redis key。
 * <p>预算语义：4096 token 是"截断上限"而非硬失败——截断的 chunk 标注省略标记，
 * Agent 可感知并调整后续读取粒度。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "dingring.rag.enabled", havingValue = "true", matchIfMissing = true)
public class KbReadInRedisTool {

    /** chunkId 白名单：{fileId}-v{version}-{index} */
    private static final String CHUNK_ID_PATTERN = "\\d+-v\\d+-\\d+";

    private final RedisRetrievalCache retrievalCache;
    private final PgChunkReader pgChunkReader;
    private final TokenCounter tokenCounter;
    private final RagProperties ragProperties;

    public KbReadInRedisTool(RedisRetrievalCache retrievalCache, PgChunkReader pgChunkReader,
                             TokenCounter tokenCounter, RagProperties ragProperties) {
        this.retrievalCache = retrievalCache;
        this.pgChunkReader = pgChunkReader;
        this.tokenCounter = tokenCounter;
        this.ragProperties = ragProperties;
    }

    @Tool(name = "kb_read_in_redis",
            description = "读取知识库片段全文。入参为目录中的 chunkId（可传多个，逗号分隔），"
                    + "返回片段全文（按重要性顺序）。单次读取有 token 预算上限，"
                    + "超预算部分会被截断并标注；请按目录相关性从高到低按需读取，不要一次全读。")
    public String kbReadInRedis(
            @ToolParam(description = "要读取的 chunkId 列表，逗号分隔，如 \"12-v1-3,12-v1-4\"", required = true)
            String chunkIds) {
        long start = System.currentTimeMillis();
        List<String> ids = parseAndValidate(chunkIds);
        if (ids.isEmpty()) {
            return "未提供有效的 chunkId（格式：{fileId}-v{version}-{index}），请从知识库目录中复制";
        }

        // 1. Redis 批量读（服务端拼前缀，工具只收裸 chunkId）
        Map<String, String> found = retrievalCache.getChunks(ids);
        List<String> missed = ids.stream().filter(id -> !found.containsKey(id)).toList();

        // 2. 未命中回 PG（缓存过期但目录还在的窗口期）
        if (!missed.isEmpty()) {
            Map<String, PgChunkReader.ChunkRecord> records = pgChunkReader.findByChunkIds(missed);
            records.forEach((chunkId, rec) -> found.put(chunkId, rec.content()));
        }

        if (found.isEmpty()) {
            LogHelper.printLog(KbReadInRedisTool.class, "kbReadInRedis", "KB_READ_REDIS",
                    "全部未命中", "chunkIds={} 延迟={}ms", ids, System.currentTimeMillis() - start);
            return "片段不存在或已过期（知识库可能已更新），请重新检索获取新目录";
        }

        // 3. 预算内按请求顺序拼接（超出预算截断）
        String result = assemble(ids, found);

        // 10.1 结构化事件：kbReadCount / 命中率 / 预算使用 / 延迟
        LogHelper.printLog(KbReadInRedisTool.class, "kbReadInRedis", "KB_READ_REDIS",
                "知识库全文读取", "kbReadCount={} redisHits={} pgFallbacks={} 延迟={}ms",
                ids.size(), ids.size() - missed.size(), missed.size(),
                System.currentTimeMillis() - start);
        return result;
    }

    /** 解析 + 白名单过滤（LLM 输出不可信，防任意 key 注入） */
    private static List<String> parseAndValidate(String chunkIds) {
        if (chunkIds == null || chunkIds.isBlank()) {
            return List.of();
        }
        List<String> valid = new ArrayList<>();
        for (String raw : chunkIds.split("[,，\\s]+")) {
            String id = raw.trim();
            if (!id.isEmpty() && id.matches(CHUNK_ID_PATTERN) && !valid.contains(id)) {
                valid.add(id);
            }
        }
        return valid;
    }

    /** 按次预算拼接：每个 chunk 标注 chunkId，超预算截断并标注 */
    private String assemble(List<String> order, Map<String, String> found) {
        int budget = ragProperties.getRetrieval().getReadBudgetTokens();
        int used = 0;
        StringBuilder sb = new StringBuilder();
        List<String> skipped = new ArrayList<>();
        Map<String, Boolean> emitted = new LinkedHashMap<>();
        for (String id : order) {
            String content = found.get(id);
            if (content == null) {
                continue;
            }
            int tokens = tokenCounter.count(content);
            if (used + tokens > budget) {
                emitted.put(id, false);
                skipped.add(id);
                continue;
            }
            used += tokens;
            emitted.put(id, true);
            sb.append("=== chunk ").append(id).append(" ===\n")
                    .append(content).append("\n\n");
        }
        if (!skipped.isEmpty()) {
            sb.append("（达到单次读取 token 预算，以下片段未读取：")
                    .append(String.join(", ", skipped))
                    .append("；可分批再次调用读取）\n");
        }
        return sb.toString().trim();
    }
}
