package com.dingring.infrastructure.rag;

import com.dingring.common.util.LogHelper;
import com.dingring.domain.knowledgebase.File;
import com.dingring.domain.knowledgebase.FileRepository;
import com.dingring.domain.knowledgebase.IngestionRun;
import com.dingring.domain.knowledgebase.IngestionRunRepository;
import com.dingring.domain.knowledgebase.KnowledgeBaseRepository;
import com.dingring.domain.discussion.TopicRepository;
import com.dingring.infrastructure.rag.config.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 孤儿向量对账任务（F6-B，9.2）。
 * <p>解决的问题：删除流程的向量清理失败（VectorStoreCleaner 仅告警不阻断）、版本切换残留、
 * 删除中断等都会让 kb_store/topic_id_store 留下 MySQL 元数据已不存在的向量——
 * 这些向量检索时仍可能被命中（权限/来源均已失效），必须定期清理。
 * <p>检查项（对应设计文档 9.2）：
 * <ul>
 *   <li>kb_store 中 fileId 不在 kb_file 表的向量 → 删除（文件已删）</li>
 *   <li>kb_store 中 kbId 不在 knowledge_base 表的向量 → 删除（知识库已删）</li>
 *   <li>kb_store 中 active=false 的向量 → 删除（旧版本已被 active 切换替代）</li>
 *   <li>READY 文件的实际向量数与 chunk_count 不一致 → 告警（数据源：摄入中断/部分失败）</li>
 *   <li>topic_id_store 中 topicId 不在 topic 表的向量 → 删除（话题已删）</li>
 *   <li>CLEANING_* 状态超期未完成 → 仅告警（设计约束：不自动降级，等用户干预）</li>
 * </ul>
 * <p>容错：单项失败不影响其他项（逐项 try-catch）；PG 不可用时整轮跳过。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "dingring.rag.enabled", havingValue = "true", matchIfMissing = true)
public class VectorStoreReconciliationJob {

    private final JdbcTemplate vectorJdbcTemplate;
    private final FileRepository fileRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final TopicRepository topicRepository;
    private final IngestionRunRepository runRepository;
    private final VectorStoreCleaner vectorStoreCleaner;
    private final RagProperties ragProperties;

    public VectorStoreReconciliationJob(
            @Qualifier("vectorJdbcTemplate") JdbcTemplate vectorJdbcTemplate,
            FileRepository fileRepository,
            KnowledgeBaseRepository knowledgeBaseRepository,
            TopicRepository topicRepository,
            IngestionRunRepository runRepository,
            VectorStoreCleaner vectorStoreCleaner,
            RagProperties ragProperties) {
        this.vectorJdbcTemplate = vectorJdbcTemplate;
        this.fileRepository = fileRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.topicRepository = topicRepository;
        this.runRepository = runRepository;
        this.vectorStoreCleaner = vectorStoreCleaner;
        this.ragProperties = ragProperties;
    }

    /** 对账入口：间隔由 dingring.rag.reconciliation.interval-seconds 配置（默认 1 小时） */
    @Scheduled(fixedDelayString = "${dingring.rag.reconciliation.interval-ms:3600000}",
            initialDelayString = "${dingring.rag.reconciliation.initial-delay-ms:120000}")
    public void reconcile() {
        if (!ragProperties.getReconciliation().isEnabled()) {
            return;
        }
        long start = System.currentTimeMillis();
        try {
            reconcileKbStore();
            reconcileTopicStore();
            warnCleaningStuck();
            LogHelper.printLog(VectorStoreReconciliationJob.class, "reconcile", "KB_RECONCILE",
                    "对账完成", "耗时ms={}", System.currentTimeMillis() - start);
        } catch (Exception e) {
            // PG 不可用等基础设施异常：本轮跳过，等下轮定时重试
            LogHelper.printWarnLog(VectorStoreReconciliationJob.class, "reconcile", "KB_RECONCILE",
                    "对账轮次失败（PG 不可用则跳过本轮）", "错误: {}", e.getMessage());
        }
    }

    /** kb_store 对账：孤儿文件/知识库 + 失活版本 + READY 一致性 */
    private void reconcileKbStore() {
        Set<Long> validFileIds = new HashSet<>();
        for (File file : fileRepository.findAll()) {
            validFileIds.add(file.getId());
        }
        Set<Long> validKbIds = new HashSet<>(
                knowledgeBaseRepository.findAll().stream().map(kb -> kb.getId()).toList());

        // 1. 孤儿 fileId（文件已删但向量残留）
        List<Long> storeFileIds = distinctMetadataIds("fileId");
        for (Long fileId : storeFileIds) {
            if (!validFileIds.contains(fileId)) {
                vectorStoreCleaner.deleteByFileId(fileId);
            }
        }

        // 2. 孤儿 kbId（知识库已删但向量残留；正常删除流程已清理，此处兜底）
        List<Long> storeKbIds = distinctMetadataIds("kbId");
        for (Long kbId : storeKbIds) {
            if (!validKbIds.contains(kbId)) {
                vectorStoreCleaner.deleteByKbId(kbId);
            }
        }

        // 3. 失活版本向量（active=false：新版本已激活，旧向量待清理）
        try {
            int deleted = vectorJdbcTemplate.update(
                    "DELETE FROM kb_store WHERE (metadata->>'active')::boolean = false");
            if (deleted > 0) {
                LogHelper.printLog(VectorStoreReconciliationJob.class, "reconcileKbStore", "KB_RECONCILE",
                        "失活版本向量已清理", "删除条数={}", deleted);
            }
        } catch (Exception e) {
            LogHelper.printWarnLog(VectorStoreReconciliationJob.class, "reconcileKbStore", "KB_RECONCILE",
                    "失活向量清理失败", "错误: {}", e.getMessage());
        }

        // 4. READY 一致性：实际向量数与 chunk_count 不符仅告警（不自动删，防止误杀摄入中的文件）
        for (File file : fileRepository.findAll()) {
            if (!File.STATUS_READY.equals(file.getStatus()) || file.getCurrentVersion() == null) {
                continue;
            }
            int actual = vectorStoreCleaner.countByFileIdAndVersion(file.getId(), file.getCurrentVersion());
            Integer expected = file.getChunkCount();
            if (actual >= 0 && expected != null && actual != expected) {
                LogHelper.printWarnLog(VectorStoreReconciliationJob.class, "reconcileKbStore", "KB_RECONCILE",
                        "READY 文件向量数与 chunk_count 不一致（需人工核查或重试摄入）",
                        "fileId={} version={} expected={} actual={}",
                        file.getId(), file.getCurrentVersion(), expected, actual);
            }
        }
    }

    /** topic_id_store 对账：话题已删但向量残留 */
    private void reconcileTopicStore() {
        Set<Long> validTopicIds = new HashSet<>(topicRepository.findAllIds());
        List<Long> storeTopicIds;
        try {
            storeTopicIds = vectorJdbcTemplate.queryForList(
                    "SELECT DISTINCT (metadata->>'topicId')::bigint FROM topic_id_store", Long.class);
        } catch (Exception e) {
            LogHelper.printWarnLog(VectorStoreReconciliationJob.class, "reconcileTopicStore", "KB_RECONCILE",
                    "topic 向量表读取失败跳过", "错误: {}", e.getMessage());
            return;
        }
        for (Long topicId : storeTopicIds) {
            if (!validTopicIds.contains(topicId)) {
                try {
                    int deleted = vectorJdbcTemplate.update(
                            "DELETE FROM topic_id_store WHERE metadata->>'topicId' = ?",
                            String.valueOf(topicId));
                    LogHelper.printLog(VectorStoreReconciliationJob.class, "reconcileTopicStore", "KB_RECONCILE",
                            "孤儿话题向量已清理", "topicId={} 删除条数={}", topicId, deleted);
                } catch (Exception e) {
                    LogHelper.printWarnLog(VectorStoreReconciliationJob.class, "reconcileTopicStore",
                            "KB_RECONCILE", "孤儿话题向量清理失败", "topicId={} 错误: {}",
                            topicId, e.getMessage());
                }
            }
        }
    }

    /** 清洗任务超期告警：长期 CLEANING_* 仅提示（9.2 明确不自动降级），由用户取消或重试 */
    private void warnCleaningStuck() {
        long thresholdMinutes = ragProperties.getReconciliation().getCleaningWarnThresholdMinutes();
        LocalDateTime threshold = LocalDateTime.now().minus(Duration.ofMinutes(thresholdMinutes));
        for (IngestionRun run : runRepository.findCleaningRuns()) {
            LocalDateTime reference = run.getSubmittedAt() != null ? run.getSubmittedAt() : run.getCreatedAt();
            if (reference != null && reference.isBefore(threshold)) {
                LogHelper.printWarnLog(VectorStoreReconciliationJob.class, "warnCleaningStuck", "KB_RECONCILE",
                        "清洗任务长期未完成（可在前端取消或等待外部提交）",
                        "fileId={} runId={} status={} since={}",
                        run.getFileId(), run.getRunId(), run.getStatus(), reference);
            }
        }
    }

    /** kb_store 的 metadata 键去重 id 列表（如 fileId/kbId）。
     * 注意 metadata 列是 json 类型（无 jsonb 的 ? 操作符），谓词用 ->> IS NOT NULL */
    private List<Long> distinctMetadataIds(String key) {
        return vectorJdbcTemplate.queryForList(
                "SELECT DISTINCT (metadata->>'" + key + "')::bigint FROM kb_store "
                        + "WHERE metadata->>'" + key + "' IS NOT NULL", Long.class);
    }
}
