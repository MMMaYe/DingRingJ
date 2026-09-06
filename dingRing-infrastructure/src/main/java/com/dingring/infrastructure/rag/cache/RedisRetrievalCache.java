package com.dingring.infrastructure.rag.cache;

import com.alibaba.fastjson.JSON;
import com.dingring.common.util.LogHelper;
import com.dingring.domain.service.RetrievalCandidate;
import com.dingring.infrastructure.rag.config.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Redis 两级检索缓存（7.7）。
 * <p>Level 1 目录缓存：rag:retrieval:{groupId}:{sha256(query)} → 候选目录 JSON（TTL 1 天）。
 * 同群同 query 免整个检索管道（dense+lexical+rerank），学习群高频重问同一概念的场景命中率高。
 * <p>Level 2 全文缓存：rag:chunk:{chunkId} → chunk 全文（TTL 2 天）。
 * kb_read_in_redis 工具读的就是这一级；目录级短 TTL 包住全文级长 TTL——
 * 目录过期后全文还在，同一 chunk 再次入选时直接命中，无需回 PG。
 * <p>键里无用户维度（目录/全文与用户无关），groupId 天然隔离多群串数据。
 * <p>降级语义：Redis 不可用是常态而非异常（未部署 Redis 的环境），
 * 任何操作失败静默返回未命中——检索直查 PG，工具返回不可用提示，主流程零影响。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "dingring.rag.enabled", havingValue = "true", matchIfMissing = true)
public class RedisRetrievalCache {

    static final String CATALOG_PREFIX = "rag:retrieval:";
    static final String CHUNK_PREFIX = "rag:chunk:";

    private final StringRedisTemplate redisTemplate;
    private final RagProperties ragProperties;

    public RedisRetrievalCache(StringRedisTemplate redisTemplate, RagProperties ragProperties) {
        this.redisTemplate = redisTemplate;
        this.ragProperties = ragProperties;
    }

    /** 缓存是否启用（配置开关；Redis 连接可用性在每次操作时惰性发现） */
    public boolean enabled() {
        return ragProperties.getRetrieval().isCacheEnabled();
    }

    /**
     * 读目录缓存。
     *
     * @param queryHash 规范化 query 的 sha256（同一规范化结果才允许命中缓存）
     * @return 候选列表；未命中/降级返回 null
     */
    public List<RetrievalCandidate> getCatalog(Long groupId, String queryHash) {
        if (!enabled() || groupId == null) {
            return null;
        }
        try {
            String json = redisTemplate.opsForValue().get(catalogKey(groupId, queryHash));
            return json == null ? null : JSON.parseArray(json, RetrievalCandidate.class);
        } catch (Exception e) {
            LogHelper.printWarnLog(RedisRetrievalCache.class, "getCatalog", "RAG_CACHE",
                    "目录缓存读取失败降级", "错误: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 写目录缓存（TTL 1 天）。
     */
    public void putCatalog(Long groupId, String queryHash, List<RetrievalCandidate> catalog) {
        if (!enabled() || groupId == null || catalog == null || catalog.isEmpty()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(catalogKey(groupId, queryHash),
                    JSON.toJSONString(catalog),
                    Duration.ofSeconds(ragProperties.getRetrieval().getCatalogTtlSeconds()));
        } catch (Exception e) {
            LogHelper.printWarnLog(RedisRetrievalCache.class, "putCatalog", "RAG_CACHE",
                    "目录缓存写入失败降级", "错误: {}", e.getMessage());
        }
    }

    /**
     * 批量写 chunk 全文（TTL 2 天）。
     * <p>无值 chunkId 跳过（目录存在但全文缓存过期的部分命中由工具侧兜底 PG）。
     */
    public void putChunks(Map<String, String> chunks) {
        if (!enabled() || chunks == null || chunks.isEmpty()) {
            return;
        }
        try {
            redisTemplate.opsForValue().multiSet(chunks);
            Duration ttl = Duration.ofSeconds(ragProperties.getRetrieval().getChunkTtlSeconds());
            // multiSet 无 TTL 重载：逐 key expire（数量 = 目录条目上限 15，可接受）
            for (String chunkId : chunks.keySet()) {
                redisTemplate.expire(CHUNK_PREFIX + chunkId, ttl);
            }
        } catch (Exception e) {
            LogHelper.printWarnLog(RedisRetrievalCache.class, "putChunks", "RAG_CACHE",
                    "全文缓存写入失败降级", "数量={} 错误: {}", chunks.size(), e.getMessage());
        }
    }

    /**
     * 服务端拼前缀批量读全文（kb_read_in_redis 的 MGET 语义）。
     *
     * @return chunkId → 全文；未命中的 chunkId 不在 Map 中；降级返回空 Map
     */
    public Map<String, String> getChunks(List<String> chunkIds) {
        if (!enabled() || chunkIds == null || chunkIds.isEmpty()) {
            return Map.of();
        }
        try {
            List<String> keys = chunkIds.stream().map(id -> CHUNK_PREFIX + id).toList();
            List<String> values = redisTemplate.opsForValue().multiGet(keys);
            Map<String, String> result = new HashMap<>();
            if (values != null) {
                for (int i = 0; i < chunkIds.size() && i < values.size(); i++) {
                    if (values.get(i) != null) {
                        result.put(chunkIds.get(i), values.get(i));
                    }
                }
            }
            return result;
        } catch (Exception e) {
            LogHelper.printWarnLog(RedisRetrievalCache.class, "getChunks", "RAG_CACHE",
                    "全文缓存读取失败降级", "数量={} 错误: {}", chunkIds.size(), e.getMessage());
            return Map.of();
        }
    }

    private static String catalogKey(Long groupId, String queryHash) {
        return CATALOG_PREFIX + groupId + ":" + queryHash;
    }
}
