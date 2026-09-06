package com.dingring.app.service;

import com.dingring.common.exception.BizException;
import com.dingring.common.exception.ParamException;
import com.dingring.common.util.HashUtil;
import com.dingring.domain.knowledgebase.DocumentVersion;
import com.dingring.domain.knowledgebase.DocumentVersionRepository;
import com.dingring.domain.knowledgebase.File;
import com.dingring.domain.knowledgebase.FileRepository;
import com.dingring.domain.knowledgebase.IngestionRun;
import com.dingring.domain.knowledgebase.IngestionRunRepository;
import com.dingring.infrastructure.rag.DocumentIngestionPipeline;
import com.dingring.infrastructure.rag.cleaning.CleaningSubmissionValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CleaningSubmissionService} P3 用例（3.2/3.4.2）：
 * 协议校验、hash 过期防护、状态拒绝、READY 幂等、成功唤醒管道。
 */
@DisplayName("清洗提交服务：协议/hash/幂等/状态门禁")
class CleaningSubmissionServiceTest {

    @TempDir
    Path tempDir;

    private FileRepository fileRepository;
    private IngestionRunRepository runRepository;
    private DocumentVersionRepository versionRepository;
    private DocumentIngestionPipeline pipeline;
    private CleaningSubmissionService service;

    private Path rawFile;
    private File file;
    private IngestionRun run;

    @BeforeEach
    void setUp() throws Exception {
        fileRepository = mock(FileRepository.class);
        runRepository = mock(IngestionRunRepository.class);
        versionRepository = mock(DocumentVersionRepository.class);
        pipeline = mock(DocumentIngestionPipeline.class);
        // 真实校验器：端到端验证协议分支（cleanMarkdown 非空/JSON 非法）
        service = new CleaningSubmissionService(fileRepository, runRepository, versionRepository,
                new CleaningSubmissionValidator(500000), pipeline);

        rawFile = Files.writeString(tempDir.resolve("doc.md"), "# 原文\n内容", StandardCharsets.UTF_8);

        file = new File();
        file.setId(12L);
        file.setName("doc.md");
        file.setPath(rawFile.toString());
        file.setKnowledgeBaseId(1L);
        file.setCurrentVersion(3);

        run = new IngestionRun();
        run.setRunId("run-1");
        run.setFileId(12L);
        run.setDocContentHash(HashUtil.sha256(rawFile));
        run.setDocumentVersion(3);
        run.setStatus(IngestionRun.STATUS_CLEANING_WAITING);
        run.setActiveSlot(12L);

        when(fileRepository.findById(12L)).thenReturn(Optional.of(file));
        when(runRepository.findActiveByFileId(12L)).thenReturn(Optional.of(run));
        DocumentVersion version = new DocumentVersion();
        version.setFileId(12L);
        version.setDocumentVersion(3);
        version.setDocContentHash(run.getDocContentHash());
        version.setRawPath(rawFile.toString());
        when(versionRepository.findByFileAndVersion(12L, 3)).thenReturn(Optional.of(version));
    }

    private static String submissionJson(String title, String markdown) {
        return "{\"documentTitle\":\"" + title + "\",\"cleanMarkdown\":\"" + markdown + "\"}";
    }

    @Test
    @DisplayName("成功提交：落盘 clean 版本、更新版本记录、唤醒摄入管道")
    void shouldSubmitAndWakePipeline() throws Exception {
        String result = service.submit(12L, submissionJson("标题", "# 清洗后"));

        assertThat(result).contains("提交成功");
        // clean 版本落盘（raw 不可变，clean 独立存储）
        assertThat(Files.readString(Path.of(rawFile + ".clean.md"), StandardCharsets.UTF_8))
                .isEqualTo("# 清洗后");
        // run 进入 PARSED 并记录提交时间
        ArgumentCaptor<IngestionRun> runCaptor = ArgumentCaptor.forClass(IngestionRun.class);
        verify(runRepository).update(runCaptor.capture());
        assertThat(runCaptor.getValue().getStatus()).isEqualTo(IngestionRun.STATUS_PARSED);
        assertThat(runCaptor.getValue().getSubmittedAt()).isNotNull();
        // 版本记录更新：cleanPath + executor=external-mcp + 清洗版本
        ArgumentCaptor<DocumentVersion> versionCaptor = ArgumentCaptor.forClass(DocumentVersion.class);
        verify(versionRepository).update(versionCaptor.capture());
        assertThat(versionCaptor.getValue().getCleanPath()).isEqualTo(rawFile + ".clean.md");
        assertThat(versionCaptor.getValue().getCleaningModel()).isEqualTo("external-mcp");
        // 唤醒摄入管道（读 clean 版本入库）
        verify(pipeline).ingest(file, run);
        assertThat(file.getCleaningStatus()).isEqualTo(File.CLEANING_CLEANED);
    }

    @Test
    @DisplayName("documentTitle 缺省取文件名（agent 未提供时服务端兜底）")
    void shouldFallbackTitleToFileName() {
        service.submit(12L, submissionJson("", "# 清洗后"));
        ArgumentCaptor<DocumentVersion> captor = ArgumentCaptor.forClass(DocumentVersion.class);
        verify(versionRepository).update(captor.capture());
        assertThat(captor.getValue().getDocumentTitle()).isEqualTo("doc");
    }

    @Test
    @DisplayName("READY 状态重复提交幂等：直接返回首次结果，不重复触发管道")
    void shouldBeIdempotentWhenReady() {
        run.setStatus(IngestionRun.STATUS_READY);

        String result = service.submit(12L, submissionJson("标题", "# 清洗后"));

        assertThat(result).contains("幂等");
        verify(pipeline, never()).ingest(any(), any());
    }

    @Test
    @DisplayName("非待清洗状态拒绝：迟到提交不覆盖新版本")
    void shouldRejectWhenNotWaiting() {
        run.setStatus(IngestionRun.STATUS_FAILED);

        assertThatThrownBy(() -> service.submit(12L, submissionJson("标题", "# 清洗后")))
                .isInstanceOf(ParamException.class)
                .hasMessageContaining("非待清洗状态");
        verify(pipeline, never()).ingest(any(), any());
    }

    @Test
    @DisplayName("无活跃 run 拒绝：文件无清洗任务时提交被拒")
    void shouldRejectWhenNoActiveRun() {
        when(runRepository.findActiveByFileId(12L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submit(12L, submissionJson("标题", "# 清洗后")))
                .isInstanceOf(ParamException.class)
                .hasMessageContaining("无活跃清洗任务");
    }

    @Test
    @DisplayName("hash 过期防护：原始文件被替换后提交被拒")
    void shouldRejectStaleSubmissionWhenFileReplaced() throws Exception {
        Files.writeString(rawFile, "# 已替换的新内容", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.submit(12L, submissionJson("标题", "# 清洗后")))
                .isInstanceOf(ParamException.class)
                .hasMessageContaining("已过期");
        verify(pipeline, never()).ingest(any(), any());
    }

    @Test
    @DisplayName("协议校验：cleanMarkdown 为空拒绝，任务保持 WAITING 可重提")
    void shouldRejectInvalidProtocol() {
        assertThatThrownBy(() -> service.submit(12L, "{\"documentTitle\":\"t\",\"cleanMarkdown\":\"\"}"))
                .isInstanceOf(ParamException.class)
                .hasMessageContaining("不符合协议");
        assertThatThrownBy(() -> service.submit(12L, "not-json"))
                .isInstanceOf(ParamException.class)
                .hasMessageContaining("不符合协议");
        // 拒绝后 run 状态不变（保持 CLEANING_WAITING，可修正后重新提交）
        verify(runRepository, never()).update(any());
    }

    @Test
    @DisplayName("文件不存在抛业务异常")
    void shouldThrowWhenFileMissing() {
        when(fileRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submit(99L, submissionJson("标题", "# x")))
                .isInstanceOf(BizException.class);
    }
}
