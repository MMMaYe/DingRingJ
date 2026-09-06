package com.dingring.infrastructure.rag;

import com.dingring.domain.knowledgebase.File;
import com.dingring.domain.knowledgebase.FileRepository;
import com.dingring.domain.knowledgebase.IngestionRun;
import com.dingring.domain.knowledgebase.IngestionRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link IngestionRecoveryWorker} 启动恢复单测。
 * <p>覆盖：在途 run 释放旧槽拉起新 run、文件已删只收尾、恢复异常兜底释放槽位。
 */
@DisplayName("IngestionRecoveryWorker 摄入启动恢复")
class IngestionRecoveryWorkerTest {

    private IngestionRunRepository runRepository;
    private FileRepository fileRepository;
    private DocumentIngestionPipeline pipeline;
    private IngestionRecoveryWorker worker;

    @BeforeEach
    void setUp() {
        runRepository = mock(IngestionRunRepository.class);
        fileRepository = mock(FileRepository.class);
        pipeline = mock(DocumentIngestionPipeline.class);
        worker = new IngestionRecoveryWorker(runRepository, fileRepository, pipeline);
    }

    private IngestionRun inFlightRun() {
        IngestionRun run = new IngestionRun();
        run.setRunId("run-old");
        run.setFileId(1L);
        run.setDocContentHash("hash-1");
        run.setDocumentVersion(2);
        run.setExecutor(IngestionRun.EXECUTOR_BUILTIN);
        run.setStatus(IngestionRun.STATUS_EMBEDDING);
        run.setAttempt(0);
        run.setActiveSlot(1L);
        return run;
    }

    private File activeFile() {
        File file = new File();
        file.setId(1L);
        file.setStatus(File.STATUS_EMBEDDED);
        file.setCurrentVersion(2);
        file.setActiveRunId("run-old");
        return file;
    }

    @Test
    @DisplayName("在途 run：旧 run 置 FAILED 释放槽位，新 run 拉起重新摄入")
    void shouldRecoverInFlightRun() {
        IngestionRun run = inFlightRun();
        File file = activeFile();
        when(runRepository.findRecoverable()).thenReturn(List.of(run));
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));

        worker.run(new DefaultApplicationArguments());

        // 旧 run 终态收尾（释放活跃槽）
        verify(runRepository).update(argThat(r ->
                IngestionRun.STATUS_FAILED.equals(r.getStatus()) && r.getActiveSlot() == null
                        && r.getErrorMessage() != null));
        // 新 run 持槽接管
        verify(runRepository).save(argThat(r ->
                IngestionRun.STATUS_UPLOAD_CREATED.equals(r.getStatus())
                        && r.getActiveSlot() != null && r.getAttempt() == 1
                        && r.getDocumentVersion().equals(2)));
        // 管道以新 run 重新摄入
        verify(pipeline).ingest(any(File.class), argThat(r -> r.getAttempt() == 1));
        assertThat(file.getActiveRunId()).isNotEqualTo("run-old");
    }

    @Test
    @DisplayName("文件已删：run 只收尾，不拉起新摄入")
    void shouldOnlyCloseRunWhenFileDeleted() {
        IngestionRun run = inFlightRun();
        when(runRepository.findRecoverable()).thenReturn(List.of(run));
        when(fileRepository.findById(1L)).thenReturn(Optional.empty());

        worker.run(new DefaultApplicationArguments());

        verify(runRepository).update(argThat(r ->
                IngestionRun.STATUS_FAILED.equals(r.getStatus()) && r.getActiveSlot() == null));
        verify(runRepository, never()).save(any(IngestionRun.class));
        verify(pipeline, never()).ingest(any(), any());
    }

    @Test
    @DisplayName("恢复异常：兜底释放槽位不阻断启动")
    void shouldReleaseSlotOnRecoveryFailure() {
        IngestionRun run = inFlightRun();
        when(runRepository.findRecoverable()).thenReturn(List.of(run));
        when(fileRepository.findById(1L)).thenReturn(Optional.of(activeFile()));
        // run 更新成功但拉新 run 时抛异常（如 DB 抖动）
        when(runRepository.save(any(IngestionRun.class))).thenThrow(new RuntimeException("db down"));

        worker.run(new DefaultApplicationArguments());

        // 异常路径兜底：run 再次被置终态释放槽位（closeRun 幂等收尾）
        verify(runRepository).update(argThat(r ->
                IngestionRun.STATUS_FAILED.equals(r.getStatus()) && r.getActiveSlot() == null));
        verify(pipeline, never()).ingest(any(), any());
    }

    @Test
    @DisplayName("无可恢复 run：零副作用直接返回")
    void shouldNoopWhenNothingToRecover() {
        when(runRepository.findRecoverable()).thenReturn(List.of());

        worker.run(new DefaultApplicationArguments());

        verify(runRepository, never()).update(any(IngestionRun.class));
        verify(runRepository, never()).save(any(IngestionRun.class));
    }

    @Test
    @DisplayName("扫描异常：仅告警不外抛（启动不被对账基础设施故障阻断）")
    void shouldSwallowScanFailure() {
        when(runRepository.findRecoverable()).thenThrow(new RuntimeException("mysql down"));

        worker.run(new DefaultApplicationArguments());

        verify(pipeline, never()).ingest(any(), any());
    }
}
