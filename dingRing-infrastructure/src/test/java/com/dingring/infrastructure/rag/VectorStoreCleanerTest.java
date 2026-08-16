package com.dingring.infrastructure.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link VectorStoreCleaner} 向量清理单测：验证 SQL 与参数、失败不外抛。
 */
@DisplayName("VectorStoreCleaner kb_store 向量清理")
class VectorStoreCleanerTest {

    private JdbcTemplate jdbcTemplate;
    private VectorStoreCleaner cleaner;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        cleaner = new VectorStoreCleaner(jdbcTemplate);
    }

    @Test
    @DisplayName("deleteByFileId 执行 metadata->>'fileId' 条件删除")
    void shouldDeleteByFileId() {
        cleaner.deleteByFileId(12L);

        verify(jdbcTemplate).update(
                eq("DELETE FROM kb_store WHERE metadata->>'fileId' = ?"), eq("12"));
    }

    @Test
    @DisplayName("deleteByKbId 执行 metadata->>'kbId' 条件删除")
    void shouldDeleteByKbId() {
        cleaner.deleteByKbId(3L);

        verify(jdbcTemplate).update(
                eq("DELETE FROM kb_store WHERE metadata->>'kbId' = ?"), eq("3"));
    }

    @Test
    @DisplayName("清理抛异常时仅告警不外抛（删除主流程不被向量库故障阻断）")
    void shouldSwallowException() {
        when(jdbcTemplate.update(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.<Object>any()))
                .thenThrow(new RuntimeException("pg down"));

        assertThatCode(() -> cleaner.deleteByFileId(12L)).doesNotThrowAnyException();
    }
}
