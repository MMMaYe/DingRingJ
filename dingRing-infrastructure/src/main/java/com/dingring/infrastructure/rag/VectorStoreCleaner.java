package com.dingring.infrastructure.rag;

import com.dingring.common.util.LogHelper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * kb_store 向量清理器（P2）。
 * <p>解决 Phase E 遗留：删除文件/知识库时向量条目因无 fileId 溯源而残留。
 * PgVectorStore 无按 metadata 删除 API，直接用 vectorJdbcTemplate 走 PG json 操作符。
 * <p>容错：清理失败仅告警不外抛——MySQL 元数据已删，残留向量不参与有效检索，
 * 阻断删除主流程的代价（用户删不掉文件）大于残留冗余。
 */
@Component
@ConditionalOnProperty(name = "dingring.rag.enabled", havingValue = "true", matchIfMissing = true)
public class VectorStoreCleaner {

    private final JdbcTemplate vectorJdbcTemplate;

    public VectorStoreCleaner(@Qualifier("vectorJdbcTemplate") JdbcTemplate vectorJdbcTemplate) {
        this.vectorJdbcTemplate = vectorJdbcTemplate;
    }

    /** 删除指定文件的全部切片向量（metadata.fileId 为摄入管道写入） */
    public void deleteByFileId(Long fileId) {
        execute("DELETE FROM kb_store WHERE metadata->>'fileId' = ?", String.valueOf(fileId), "fileId", fileId);
    }

    /** 删除指定知识库的全部向量（删除知识库时一次清理） */
    public void deleteByKbId(Long kbId) {
        execute("DELETE FROM kb_store WHERE metadata->>'kbId' = ?", String.valueOf(kbId), "kbId", kbId);
    }

    private void execute(String sql, String param, String label, Long id) {
        try {
            int deleted = vectorJdbcTemplate.update(sql, param);
            LogHelper.printLog(VectorStoreCleaner.class, "execute", "KB_VECTOR_CLEAN",
                    "向量清理完成", "{}={} 删除条数={}", label, id, deleted);
        } catch (Exception e) {
            LogHelper.printWarnLog(VectorStoreCleaner.class, "execute", "KB_VECTOR_CLEAN",
                    "向量清理失败不阻断", "{}={} 错误: {}", label, id, e.getMessage());
        }
    }
}
