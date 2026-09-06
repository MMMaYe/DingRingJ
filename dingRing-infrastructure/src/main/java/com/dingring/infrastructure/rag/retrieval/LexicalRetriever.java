package com.dingring.infrastructure.rag.retrieval;

import com.alibaba.fastjson.JSONObject;
import com.dingring.common.util.LogHelper;
import com.dingring.domain.service.RetrievalCandidate;
import com.dingring.infrastructure.rag.config.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Lexical 通道（7.3）：pg_trgm word_similarity 直查 kb_store。
 * <p>为什么选 word_similarity 而不是 similarity：查询通常短（主题标题+近期消息）而 chunk 长，
 * 整串相似度必然被长度稀释；word_similarity 找 content 中与 query 最匹配的片段，
 * 恰好就是"长文档里有没有这个词"的检索语义。&lt;% 操作符走 GIN trigram 索引
 * （002_p3_postgresql.sql 的 kb_store_content_trgm_idx）。
 * <p>阈值用 pg_trgm.word_similarity_threshold 会话默认值 0.6，不额外 SET：
 * lexical 通道的定位是精确词/代码类名/错误码增强，0.6 保证低噪声；
 * 中文语义匹配由 dense 通道兜底，RRF 融合后还有 rerank 把关。
 * <p>元数据口径与摄入管道一致：kbId IN 绑定库 + active=true 只查活跃版本。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "dingring.rag.enabled", havingValue = "true", matchIfMissing = true)
public class LexicalRetriever {

    private final JdbcTemplate vectorJdbcTemplate;
    private final RagProperties ragProperties;

    public LexicalRetriever(@Qualifier("vectorJdbcTemplate") JdbcTemplate vectorJdbcTemplate,
                            RagProperties ragProperties) {
        this.vectorJdbcTemplate = vectorJdbcTemplate;
        this.ragProperties = ragProperties;
    }

    /**
     * 词法检索 Top-N。
     *
     * @param query 已规范化的检索文本
     * @param kbIds 群绑定的知识库 ID
     * @return 候选列表（score = word_similarity 0-1）；查询为空或异常返回空列表
     */
    public List<RetrievalCandidate> search(String query, List<Long> kbIds) {
        if (query == null || query.isBlank() || kbIds == null || kbIds.isEmpty()) {
            return List.of();
        }
        int topK = ragProperties.getRetrieval().getLexicalTopK();
        String placeholders = String.join(",", kbIds.stream().map(id -> "?").toList());
        // SELECT 列显式输出 word_similarity 作为 score（rs.getDouble(4)）；<% 走 trigram GIN 索引过滤
        String sql = "SELECT id::text, content, metadata::text, word_similarity(?, content) AS score "
                + "FROM kb_store "
                + "WHERE (metadata->>'kbId')::bigint IN (" + placeholders + ") "
                + "AND (metadata->>'active')::boolean = true "
                + "AND ? <% content "
                + "ORDER BY score DESC LIMIT ?";
        Object[] args = buildArgs(query, kbIds, topK);
        try {
            List<RetrievalCandidate> result = vectorJdbcTemplate.query(sql, (rs, rowNum) -> {
                Map<String, Object> metadata = JSONObject.parseObject(rs.getString("metadata"));
                return new RetrievalCandidate(
                        rs.getString("id"),
                        (String) metadata.get("chunkId"),
                        rs.getString("content"),
                        (String) metadata.getOrDefault("fileName", ""),
                        headingPath(metadata.get("headingPath")),
                        sourceLocation(metadata),
                        rs.getDouble(4),
                        metadata);
            }, args);
            return result == null ? List.of() : result;
        } catch (Exception e) {
            // pg_trgm 扩展未装 / 索引未建时 SQL 会失败：lexical 是增强通道，降级为空不阻塞检索
            LogHelper.printWarnLog(LexicalRetriever.class, "search", "RAG_LEXICAL",
                    "词法检索失败降级为空（检查 pg_trgm 扩展与 GIN 索引）", "错误: {}", e.getMessage());
            return List.of();
        }
    }

    private Object[] buildArgs(String query, List<Long> kbIds, int topK) {
        // 参数顺序：query(score 列) + kbIds... + query(<% 条件) + topK
        Object[] args = new Object[kbIds.size() + 3];
        args[0] = query;
        for (int i = 0; i < kbIds.size(); i++) {
            args[i + 1] = kbIds.get(i);
        }
        args[kbIds.size() + 1] = query;
        args[kbIds.size() + 2] = topK;
        return args;
    }

    @SuppressWarnings("unchecked")
    private static List<String> headingPath(Object raw) {
        if (raw instanceof List<?> list) {
            return (List<String>) list;
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
}
