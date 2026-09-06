package com.dingring.infrastructure.rag;

import com.dingring.domain.discussion.TopicRepository;
import com.dingring.domain.knowledgebase.File;
import com.dingring.domain.knowledgebase.FileRepository;
import com.dingring.domain.knowledgebase.IngestionRun;
import com.dingring.domain.knowledgebase.IngestionRunRepository;
import com.dingring.domain.knowledgebase.KnowledgeBase;
import com.dingring.domain.knowledgebase.KnowledgeBaseRepository;
import com.dingring.infrastructure.rag.config.RagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link VectorStoreReconciliationJob} 孤儿向量对账单测。
 * <p>覆盖：孤儿 fileId/kbId 清理、失活版本删除、READY 数量不一致告警、话题孤儿清理、
 * 清洗超期告警、对账开关关闭。
 */
@DisplayName("VectorStoreReconciliationJob 孤儿向量对账")
class VectorStoreReconciliationJobTest {

    private JdbcTemplate vectorJdbcTemplate;
    private FileRepository fileRepository;
    private KnowledgeBaseRepository kbRepository;
    private TopicRepository topicRepository;
    private IngestionRunRepository runRepository;
    private VectorStoreCleaner cleaner;
    private RagProperties ragProperties;
    private VectorStoreReconciliationJob job;

    @BeforeEach
    void setUp() {
        vectorJdbcTemplate = mock(JdbcTemplate.class);
        fileRepository = mock(FileRepository.class);
        kbRepository = mock(KnowledgeBaseRepository.class);
        topicRepository = mock(TopicRepository.class);
        runRepository = mock(IngestionRunRepository.class);
        cleaner = mock(VectorStoreCleaner.class);
        ragProperties = new RagProperties();
        job = new VectorStoreReconciliationJob(vectorJdbcTemplate, fileRepository,
                kbRepository, topicRepository, runRepository, cleaner, ragProperties);
    }

    private File readyFile(Long id, Integer version, Integer chunkCount) {
        File file = new File();
        file.setId(id);
        file.setStatus(File.STATUS_READY);
        file.setCurrentVersion(version);
        file.setChunkCount(chunkCount);
        return file;
    }

    @Test
    @DisplayName("孤儿 fileId：kb_file 已无此文件，按 fileId 删除残留向量")
    void shouldDeleteOrphanFileVectors() {
        when(fileRepository.findAll()).thenReturn(List.of(readyFile(2L, 1, 10)));
        when(kbRepository.findAll()).thenReturn(List.of(new KnowledgeBase()));
        when(vectorJdbcTemplate.queryForList(
                anyString(), eq(Long.class)))
                .thenReturn(List.of(1L, 2L))   // fileId：1 为孤儿
                .thenReturn(List.of());        // kbId
        when(topicRepository.findAllIds()).thenReturn(List.of());

        job.reconcile();

        verify(cleaner).deleteByFileId(1L);
        verify(cleaner, never()).deleteByFileId(2L);
    }

    @Test
    @DisplayName("孤儿 kbId：knowledge_base 已无此库，按 kbId 删除残留向量")
    void shouldDeleteOrphanKbVectors() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(7L);
        when(fileRepository.findAll()).thenReturn(List.of());
        when(kbRepository.findAll()).thenReturn(List.of(kb));
        when(vectorJdbcTemplate.queryForList(anyString(), eq(Long.class)))
                .thenReturn(List.of())      // fileId
                .thenReturn(List.of(7L, 9L)); // kbId：9 为孤儿
        when(topicRepository.findAllIds()).thenReturn(List.of());

        job.reconcile();

        verify(cleaner).deleteByKbId(9L);
        verify(cleaner, never()).deleteByKbId(7L);
    }

    @Test
    @DisplayName("失活版本向量被删除（active=false 已被新版本替代）")
    void shouldDeleteInactiveVersionVectors() {
        when(fileRepository.findAll()).thenReturn(List.of());
        when(kbRepository.findAll()).thenReturn(List.of());
        when(vectorJdbcTemplate.queryForList(anyString(), eq(Long.class)))
                .thenReturn(List.of())
                .thenReturn(List.of());
        when(topicRepository.findAllIds()).thenReturn(List.of());

        job.reconcile();

        verify(vectorJdbcTemplate).update(
                eq("DELETE FROM kb_store WHERE (metadata->>'active')::boolean = false"));
    }

    @Test
    @DisplayName("READY 文件向量数与 chunk_count 不一致：不删除仅告警（防误杀摄入中的文件）")
    void shouldNotDeleteWhenCountMismatch() {
        when(fileRepository.findAll()).thenReturn(List.of(readyFile(5L, 2, 100)));
        when(kbRepository.findAll()).thenReturn(List.of());
        when(vectorJdbcTemplate.queryForList(anyString(), eq(Long.class)))
                .thenReturn(List.of())
                .thenReturn(List.of());
        when(topicRepository.findAllIds()).thenReturn(List.of());
        when(cleaner.countByFileIdAndVersion(5L, 2)).thenReturn(60);

        job.reconcile();

        // 数量不符：只告警，绝不能按 fileId 全删（READY 文件的向量是有效资产）
        verify(cleaner, never()).deleteByFileId(5L);
        verify(cleaner, never()).deleteByFileIdAndVersion(5L, 2);
    }

    @Test
    @DisplayName("孤儿话题向量：topic 表已无此 id，按 topicId 删除")
    void shouldDeleteOrphanTopicVectors() {
        when(fileRepository.findAll()).thenReturn(List.of());
        when(kbRepository.findAll()).thenReturn(List.of());
        when(vectorJdbcTemplate.queryForList(anyString(), eq(Long.class)))
                .thenReturn(List.of())
                .thenReturn(List.of())
                .thenReturn(List.of(10L, 11L)); // topic_id_store：11 为孤儿
        when(topicRepository.findAllIds()).thenReturn(List.of(10L));

        job.reconcile();

        verify(vectorJdbcTemplate).update(
                eq("DELETE FROM topic_id_store WHERE metadata->>'topicId' = ?"), eq("11"));
    }

    @Test
    @DisplayName("清洗任务超期：仅告警不自动降级（设计 9.2 约束）")
    void shouldWarnButNotFailStuckCleaningRuns() {
        when(fileRepository.findAll()).thenReturn(List.of());
        when(kbRepository.findAll()).thenReturn(List.of());
        when(vectorJdbcTemplate.queryForList(anyString(), eq(Long.class)))
                .thenReturn(List.of())
                .thenReturn(List.of());
        when(topicRepository.findAllIds()).thenReturn(List.of());

        IngestionRun stuck = new IngestionRun();
        stuck.setRunId("run-stuck");
        stuck.setFileId(9L);
        stuck.setStatus(IngestionRun.STATUS_CLEANING_WAITING);
        stuck.setSubmittedAt(LocalDateTime.now().minusHours(10));
        when(runRepository.findCleaningRuns()).thenReturn(List.of(stuck));

        job.reconcile();

        // 超期清洗任务不能被置 FAILED/CANCELLED（用户决策优先）
        verify(runRepository, never()).update(argThat(r -> r.getRunId().equals("run-stuck")));
    }

    @Test
    @DisplayName("对账开关关闭：零 SQL 零清理")
    void shouldSkipWhenDisabled() {
        ragProperties.getReconciliation().setEnabled(false);

        job.reconcile();

        verify(fileRepository, never()).findAll();
        verify(cleaner, never()).deleteByFileId(org.mockito.ArgumentMatchers.anyLong());
    }
}
