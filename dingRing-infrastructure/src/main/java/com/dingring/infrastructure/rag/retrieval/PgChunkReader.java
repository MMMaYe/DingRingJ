package com.dingring.infrastructure.rag.retrieval;

import com.alibaba.fastjson.JSONObject;
import com.dingring.common.util.LogHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * kb_store 直读器（P3 检索侧）：按 chunkId 批量取 chunk 全文与 embedding。
 * <p>服务于多阶段检索的两个环节（7.3）：
 * <ul>
 *   <li>邻居扩展：RRF 融合后按 chunkId 确定性格式（{fileId}-v{version}-{index}）
 *       计算兄弟 chunk 的 chunkId，批量取回补充候选池；</li>
 *   <li>MMR：候选间向量相似度需要 embedding，dense 召回结果（Document）不携带向量，
 *       统一从 PG 取回。</li>
 * </ul>
 * <p>chunkId 是逻辑键（摄入时写入 metadata），PG 主键 id 仅作物理行标识——
 * 检索目录展示与 Redis 缓存都以 chunkId 为准，重摄入同 chunk 语义稳定。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "dingring.rag.enabled", havingValue = "true", matchIfMissing = true)
public class PgChunkReader {

    private final JdbcTemplate vectorJdbcTemplate;

    public PgChunkReader(@Qualifier("vectorJdbcTemplate") JdbcTemplate vectorJdbcTemplate) {
        this.vectorJdbcTemplate = vectorJdbcTemplate;
    }

    /** chunk 物理记录：embedding 供 MMR，metadata 供候选构建 */
    public record ChunkRecord(String rowId, String chunkId, String content,
                              Map<String, Object> metadata, float[] embedding) {}

    /**
     * 按 chunkId 批量读取。
     *
     * @return chunkId → 记录；未命中的 chunkId 不在 Map 中；异常返回空 Map（降级不阻塞检索）
     */
    public Map<String, ChunkRecord> findByChunkIds(Collection<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return Map.of();
        }
        List<String> ids = List.copyOf(chunkIds);
        String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
        String sql = "SELECT id::text, metadata->>'chunkId' AS chunk_id, content, metadata::text, "
                + "embedding::text AS embedding FROM kb_store WHERE metadata->>'chunkId' IN (" + placeholders + ")";
        try {
            Map<String, ChunkRecord> result = new HashMap<>();
            vectorJdbcTemplate.query(sql, (rs, rowNum) -> {
                String chunkId = rs.getString("chunk_id");
                result.put(chunkId, new ChunkRecord(
                        rs.getString("id"),
                        chunkId,
                        rs.getString("content"),
                        JSONObject.parseObject(rs.getString("metadata")),
                        parseVector(rs.getString("embedding"))));
                return result;
            }, ids.toArray());
            return result;
        } catch (Exception e) {
            LogHelper.printWarnLog(PgChunkReader.class, "findByChunkIds", "RAG_CHUNK_READ",
                    "chunk 批量读取失败（跳过邻居扩展/MMR 降级）", "数量={} 错误: {}", ids.size(), e.getMessage());
            return Map.of();
        }
    }

    /**
     * 按物理行 id 批量读取（MMR 向量取回：dense/lexical 候选只带行 id）。
     *
     * @return rowId → 记录；异常返回空 Map（MMR 退化为相关性截断）
     */
    public Map<String, ChunkRecord> findByRowIds(Collection<String> rowIds) {
        if (rowIds == null || rowIds.isEmpty()) {
            return Map.of();
        }
        List<String> ids = List.copyOf(rowIds);
        String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
        String sql = "SELECT id::text, metadata->>'chunkId' AS chunk_id, content, metadata::text, "
                + "embedding::text AS embedding FROM kb_store WHERE id::text IN (" + placeholders + ")";
        try {
            Map<String, ChunkRecord> result = new HashMap<>();
            vectorJdbcTemplate.query(sql, (rs, rowNum) -> {
                String rowId = rs.getString("id");
                result.put(rowId, new ChunkRecord(
                        rowId,
                        rs.getString("chunk_id"),
                        rs.getString("content"),
                        JSONObject.parseObject(rs.getString("metadata")),
                        parseVector(rs.getString("embedding"))));
                return result;
            }, ids.toArray());
            return result;
        } catch (Exception e) {
            LogHelper.printWarnLog(PgChunkReader.class, "findByRowIds", "RAG_CHUNK_READ",
                    "行 id 批量读取失败（MMR 降级为截断）", "数量={} 错误: {}", ids.size(), e.getMessage());
            return Map.of();
        }
    }

    /** pgvector 的 text 表示 "[0.1,0.2,...]" → float[]；空/畸形返回空数组 */
    static float[] parseVector(String raw) {
        if (raw == null || raw.isBlank() || "null".equals(raw)) {
            return new float[0];
        }
        String body = raw.trim();
        if (body.startsWith("[") && body.endsWith("]")) {
            body = body.substring(1, body.length() - 1);
        }
        if (body.isBlank()) {
            return new float[0];
        }
        String[] parts = body.split(",");
        float[] vector = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            vector[i] = Float.parseFloat(parts[i].trim());
        }
        return vector;
    }
}
