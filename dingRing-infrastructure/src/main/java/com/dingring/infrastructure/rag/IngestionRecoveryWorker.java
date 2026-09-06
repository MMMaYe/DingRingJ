package com.dingring.infrastructure.rag;

import com.dingring.common.util.LogHelper;
import com.dingring.domain.knowledgebase.File;
import com.dingring.domain.knowledgebase.FileRepository;
import com.dingring.domain.knowledgebase.IngestionRun;
import com.dingring.domain.knowledgebase.IngestionRunRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 摄入启动恢复 worker（F6-B，9.1）。
 * <p>解决的问题：摄入是 @Async 内存任务，应用重启（发版/崩溃）会让在途 run 永远停在
 * 中间状态（如 EMBEDDING）——不占活跃槽但文件也不是终态，既不能检索也不能重试。
 * <p>策略：启动时查 findRecoverable()（持有活跃槽 + 非终态 + 非 CLEANING_WAITING），
 * 对每条 run 直接走 {@code retryIngestion} 等价逻辑拉起新一轮摄入：
 * <ul>
 *   <li>run 侧：失败原因标记"应用重启中断"，释放活跃槽（写入终态 FAILED）</li>
 *   <li>file 侧：清 activeRunId，由新 run 接管</li>
 *   <li>新 run 复用 file 当前 version——幂等清理（管道写向量前清同 version 半成品向量）
 *     保证不产生重复 chunk</li>
 * </ul>
 * CLEANING_WAITING 不恢复：外部执行方（MCP）的提交服务有独立唤醒逻辑，重启代劳会与
 * "提交时落 clean 版本再唤醒"的协议冲突。
 * <p>容错：恢复失败仅告警（run 已置 FAILED 终态，用户可走前端"重试"按钮手动恢复），
 * 不能因恢复问题阻断应用启动。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "dingring.rag.enabled", havingValue = "true", matchIfMissing = true)
public class IngestionRecoveryWorker implements ApplicationRunner {

    private final IngestionRunRepository runRepository;
    private final FileRepository fileRepository;
    private final DocumentIngestionPipeline ingestionPipeline;

    public IngestionRecoveryWorker(IngestionRunRepository runRepository,
                                   FileRepository fileRepository,
                                   DocumentIngestionPipeline ingestionPipeline) {
        this.runRepository = runRepository;
        this.fileRepository = fileRepository;
        this.ingestionPipeline = ingestionPipeline;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            List<IngestionRun> recoverable = runRepository.findRecoverable();
            if (recoverable.isEmpty()) {
                return;
            }
            LogHelper.printLog(IngestionRecoveryWorker.class, "run", "KB_RECOVERY",
                    "发现中断摄入任务，开始恢复", "count={}", recoverable.size());
            int recovered = 0;
            for (IngestionRun run : recoverable) {
                if (recoverOne(run)) {
                    recovered++;
                }
            }
            LogHelper.printLog(IngestionRecoveryWorker.class, "run", "KB_RECOVERY",
                    "恢复完成", "total={} recovered={}", recoverable.size(), recovered);
        } catch (Exception e) {
            // 对账/恢复基础设施异常不能阻断启动：run 留在原状态，等下一次重启或人工重试
            LogHelper.printWarnLog(IngestionRecoveryWorker.class, "run", "KB_RECOVERY",
                    "恢复扫描失败（不阻断启动）", "错误: {}", e.getMessage());
        }
    }

    /** 单条 run 恢复：置终态释放槽位 → 拉起新 run 重新摄入（幂等，不产生重复向量） */
    private boolean recoverOne(IngestionRun run) {
        try {
            File file = fileRepository.findById(run.getFileId()).orElse(null);
            if (file == null || File.STATUS_DELETING.equals(file.getStatus())) {
                // 文件已删/删除中：只收尾 run（向量已由删除流程清理），不再摄入
                closeRun(run, "文件已删除，run 收尾");
                return true;
            }

            // 旧 run 置终态释放活跃槽（新 run 创建前必须无活跃任务，见 9.1 单文件单活跃 run 约束）
            run.setStatus(IngestionRun.STATUS_FAILED);
            run.setErrorMessage("应用重启中断，自动恢复");
            run.setActiveSlot(null);
            runRepository.update(run);

            // 新 run：version 沿用 file.currentVersion（重复摄入由管道幂等清理兜底）
            IngestionRun newRun = new IngestionRun();
            newRun.setRunId(java.util.UUID.randomUUID().toString());
            newRun.setFileId(file.getId());
            newRun.setDocContentHash(run.getDocContentHash());
            newRun.setDocumentVersion(file.getCurrentVersion() == null ? 1 : file.getCurrentVersion());
            newRun.setExecutor(IngestionRun.EXECUTOR_BUILTIN);
            newRun.setStatus(IngestionRun.STATUS_UPLOAD_CREATED);
            newRun.setAttempt((run.getAttempt() == null ? 0 : run.getAttempt()) + 1);
            newRun.setActiveSlot(file.getId());
            runRepository.save(newRun);

            file.setStatus(File.STATUS_UPLOADED);
            file.setErrorMsg(null);
            file.setActiveRunId(newRun.getRunId());
            fileRepository.update(file);

            ingestionPipeline.ingest(file, newRun);
            LogHelper.printLog(IngestionRecoveryWorker.class, "recoverOne", "KB_RECOVERY",
                    "任务已拉起恢复", "fileId={} oldRun={} newRun={}",
                    file.getId(), run.getRunId(), newRun.getRunId());
            return true;
        } catch (Exception e) {
            LogHelper.printWarnLog(IngestionRecoveryWorker.class, "recoverOne", "KB_RECOVERY",
                    "单条恢复失败（run 已置终态，可前端手动重试）", "runId={} 错误: {}",
                    run.getRunId(), e.getMessage());
            // 兜底：确保异常路径下槽位被释放，避免文件永久卡在"有活跃任务"
            closeRun(run, "恢复异常: " + e.getMessage());
            return false;
        }
    }

    /** 终态收尾（幂等）：不再摄入的场景只释放槽位与 file 的 activeRunId */
    private void closeRun(IngestionRun run, String reason) {
        if (!run.isTerminal()) {
            run.setStatus(IngestionRun.STATUS_FAILED);
            run.setErrorMessage(reason);
            run.setActiveSlot(null);
            runRepository.update(run);
        }
        if (run.getRunId() != null) {
            fileRepository.findById(run.getFileId()).ifPresent(f -> {
                if (run.getRunId().equals(f.getActiveRunId())) {
                    f.setActiveRunId(null);
                    fileRepository.update(f);
                }
            });
        }
    }
}
