package com.dingring.app.service;

import com.dingring.common.exception.ParamException;
import com.dingring.domain.knowledgebase.DocumentVersion;
import com.dingring.domain.knowledgebase.DocumentVersionRepository;
import com.dingring.domain.knowledgebase.File;
import com.dingring.domain.knowledgebase.FileRepository;
import com.dingring.domain.knowledgebase.IngestionRun;
import com.dingring.domain.knowledgebase.IngestionRunRepository;
import com.dingring.domain.knowledgebase.KnowledgeBase;
import com.dingring.domain.knowledgebase.KnowledgeBaseRepository;
import com.dingring.domain.group.GroupRepository;
import com.dingring.domain.service.RagService;
import com.dingring.infrastructure.rag.DocumentIngestionPipeline;
import com.dingring.infrastructure.rag.FileStorageService;
import com.dingring.infrastructure.rag.VectorStoreCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link KnowledgeBaseAppService} P3 用例：run 驱动上传 + 删除竞态失效。
 */
@DisplayName("KnowledgeBaseAppService run 驱动上传与删除失效")
class KnowledgeBaseAppServiceTest {

    @TempDir
    Path tempDir;

    private KnowledgeBaseRepository knowledgeBaseRepository;
    private FileRepository fileRepository;
    private DocumentIngestionPipeline ingestionPipeline;
    private FileStorageService fileStorageService;
    private RagService ragService;
    private VectorStoreCleaner vectorStoreCleaner;
    private GroupRepository groupRepository;
    private IngestionRunRepository ingestionRunRepository;
    private DocumentVersionRepository documentVersionRepository;
    private KnowledgeBaseAppService service;

    @BeforeEach
    void setUp() {
        knowledgeBaseRepository = mock(KnowledgeBaseRepository.class);
        fileRepository = mock(FileRepository.class);
        ingestionPipeline = mock(DocumentIngestionPipeline.class);
        fileStorageService = mock(FileStorageService.class);
        ragService = mock(RagService.class);
        vectorStoreCleaner = mock(VectorStoreCleaner.class);
        groupRepository = mock(GroupRepository.class);
        ingestionRunRepository = mock(IngestionRunRepository.class);
        documentVersionRepository = mock(DocumentVersionRepository.class);
        service = new KnowledgeBaseAppService(knowledgeBaseRepository, fileRepository,
                ingestionPipeline, fileStorageService, ragService, vectorStoreCleaner,
                groupRepository, ingestionRunRepository, documentVersionRepository);
    }

    private KnowledgeBase kb(Long id) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(id);
        kb.setName("kb-" + id);
        kb.setStatus(KnowledgeBase.STATUS_ACTIVE);
        return kb;
    }

    @Test
    @DisplayName("删除知识库按 kbId 联动清理向量")
    void shouldCleanVectorsOnKbDelete() {
        when(knowledgeBaseRepository.findById(1L)).thenReturn(Optional.of(kb(1L)));
        when(fileRepository.findByKnowledgeBaseId(1L)).thenReturn(List.of());

        service.delete(1L);

        verify(vectorStoreCleaner).deleteByKbId(1L);
        verify(knowledgeBaseRepository).deleteById(1L);
    }

    @Test
    @DisplayName("删除文件先失效活跃 run 再清理向量")
    void shouldInvalidateRunBeforeCleaningVectors() {
        File file = new File();
        file.setId(9L);
        file.setKnowledgeBaseId(1L);
        file.setPath("/tmp/x.md");
        file.setActiveRunId("run-9");
        IngestionRun run = new IngestionRun();
        run.setRunId("run-9");
        run.setFileId(9L);
        run.setActiveSlot(9L);
        run.setStatus(IngestionRun.STATUS_EMBEDDING);
        when(fileRepository.findById(9L)).thenReturn(Optional.of(file));
        when(ingestionRunRepository.findByRunId("run-9")).thenReturn(Optional.of(run));

        service.deleteFile(1L, 9L);

        ArgumentCaptor<IngestionRun> captor = ArgumentCaptor.forClass(IngestionRun.class);
        verify(ingestionRunRepository).update(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(IngestionRun.STATUS_CANCELLED);
        assertThat(captor.getValue().getActiveSlot()).isNull();
        assertThat(file.getStatus()).isEqualTo(File.STATUS_DELETING);
        verify(vectorStoreCleaner).deleteByFileId(9L);
        verify(fileRepository).deleteById(9L);
    }

    @Test
    @DisplayName("上传非 .md 文件被拒绝且不落盘不摄入")
    void shouldRejectNonMarkdownUpload() {
        when(knowledgeBaseRepository.findById(1L)).thenReturn(Optional.of(kb(1L)));
        MockMultipartFile pdf = new MockMultipartFile(
                "file", "spec.pdf", "application/pdf", "fake".getBytes());

        assertThatThrownBy(() -> service.upload(1L, pdf))
                .isInstanceOf(ParamException.class)
                .hasMessageContaining(".md");
        verify(fileStorageService, never()).store(any());
        verify(ingestionPipeline, never()).ingest(any(), any());
    }

    @Test
    @DisplayName("上传 .md 创建 run 与版本记录并触发 run 驱动摄入")
    void shouldAcceptMarkdownUploadWithRunAndVersion() throws Exception {
        when(knowledgeBaseRepository.findById(1L)).thenReturn(Optional.of(kb(1L)));
        Path stored = tempDir.resolve("note.md");
        Files.writeString(stored, "# 标题\n内容");
        when(fileStorageService.store(any())).thenReturn(stored.toString());
        MockMultipartFile md = new MockMultipartFile(
                "file", "note.md", "text/markdown", "# 标题\n内容".getBytes());

        assertThatCode(() -> service.upload(1L, md)).doesNotThrowAnyException();

        verify(fileStorageService).store(any());
        verify(documentVersionRepository).save(any());
        ArgumentCaptor<IngestionRun> runCaptor = ArgumentCaptor.forClass(IngestionRun.class);
        verify(ingestionRunRepository).save(runCaptor.capture());
        IngestionRun run = runCaptor.getValue();
        assertThat(run.getDocContentHash()).isNotBlank();
        assertThat(run.getActiveSlot()).isEqualTo(run.getFileId());
        assertThat(run.getStatus()).isEqualTo(IngestionRun.STATUS_UPLOAD_CREATED);
        verify(ingestionPipeline).ingest(any(), any());
    }

    @Test
    @DisplayName("勾选清洗+内置执行方：run 以 CLEANING_RUNNING 进入管道（否则管道清洗分支永不触发）")
    void shouldCreateCleaningRunningRunWhenCleanRequested() throws Exception {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "cleaningEnabledByDefault", true);
        when(knowledgeBaseRepository.findById(1L)).thenReturn(Optional.of(kb(1L)));
        Path stored = tempDir.resolve("note.md");
        Files.writeString(stored, "# 标题\n内容");
        when(fileStorageService.store(any())).thenReturn(stored.toString());
        MockMultipartFile md = new MockMultipartFile(
                "file", "note.md", "text/markdown", "# 标题\n内容".getBytes());

        service.upload(1L, md, true);

        ArgumentCaptor<IngestionRun> runCaptor = ArgumentCaptor.forClass(IngestionRun.class);
        verify(ingestionRunRepository).save(runCaptor.capture());
        assertThat(runCaptor.getValue().getStatus()).isEqualTo(IngestionRun.STATUS_CLEANING_RUNNING);
        verify(ingestionPipeline).ingest(any(), any());
    }

    @Test
    @DisplayName("重试未清洗版本：重新以 CLEANING_RUNNING 摄入（对齐上传清洗语义）")
    void shouldRetryWithCleaningWhenVersionHasNoCleanArtifact() throws Exception {
        File file = new File();
        file.setId(9L);
        file.setName("note.md");
        file.setPath(tempDir.resolve("note.md").toString());
        Files.writeString(tempDir.resolve("note.md"), "# 标题\n内容");
        file.setCurrentVersion(1);
        // cleaningStatus=null 表示当初勾选了清洗（SKIPPED 才是未勾选）
        when(fileRepository.findById(9L)).thenReturn(Optional.of(file));
        when(ingestionRunRepository.findActiveByFileId(9L)).thenReturn(Optional.empty());
        DocumentVersion version = new DocumentVersion();
        version.setFileId(9L);
        version.setDocumentVersion(1);
        version.setCleanPath(null);
        when(documentVersionRepository.findByFileAndVersion(9L, 1)).thenReturn(Optional.of(version));

        service.retryIngestion(9L);

        ArgumentCaptor<IngestionRun> runCaptor = ArgumentCaptor.forClass(IngestionRun.class);
        verify(ingestionRunRepository).save(runCaptor.capture());
        assertThat(runCaptor.getValue().getStatus()).isEqualTo(IngestionRun.STATUS_CLEANING_RUNNING);
        verify(ingestionPipeline).ingest(any(), any());
    }

    @Test
    @DisplayName("重试已有 clean 产物的版本：直接摄入不重洗")
    void shouldRetryWithoutCleaningWhenCleanArtifactExists() throws Exception {
        File file = new File();
        file.setId(9L);
        file.setPath(tempDir.resolve("note.md").toString());
        Files.writeString(tempDir.resolve("note.md"), "# 标题\n内容");
        file.setCurrentVersion(1);
        when(fileRepository.findById(9L)).thenReturn(Optional.of(file));
        when(ingestionRunRepository.findActiveByFileId(9L)).thenReturn(Optional.empty());
        DocumentVersion version = new DocumentVersion();
        version.setFileId(9L);
        version.setDocumentVersion(1);
        version.setCleanPath("/tmp/already-clean.md");
        when(documentVersionRepository.findByFileAndVersion(9L, 1)).thenReturn(Optional.of(version));

        service.retryIngestion(9L);

        ArgumentCaptor<IngestionRun> runCaptor = ArgumentCaptor.forClass(IngestionRun.class);
        verify(ingestionRunRepository).save(runCaptor.capture());
        assertThat(runCaptor.getValue().getStatus()).isEqualTo(IngestionRun.STATUS_UPLOAD_CREATED);
    }
}
