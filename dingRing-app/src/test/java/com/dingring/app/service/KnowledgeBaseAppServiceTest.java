package com.dingring.app.service;

import com.dingring.app.dto.request.CreateKbRequest;
import com.dingring.common.exception.ParamException;
import com.dingring.domain.knowledgebase.File;
import com.dingring.domain.knowledgebase.FileRepository;
import com.dingring.domain.knowledgebase.KnowledgeBase;
import com.dingring.domain.knowledgebase.KnowledgeBaseRepository;
import com.dingring.domain.service.RagService;
import com.dingring.infrastructure.rag.DocumentIngestionPipeline;
import com.dingring.infrastructure.rag.FileStorageService;
import com.dingring.infrastructure.rag.VectorStoreCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link KnowledgeBaseAppService} P2 用例：删除联动向量清理 + 上传 md 双检校验。
 */
@DisplayName("KnowledgeBaseAppService 删除联动与上传 md 双检")
class KnowledgeBaseAppServiceTest {

    private KnowledgeBaseRepository knowledgeBaseRepository;
    private FileRepository fileRepository;
    private DocumentIngestionPipeline ingestionPipeline;
    private FileStorageService fileStorageService;
    private RagService ragService;
    private VectorStoreCleaner vectorStoreCleaner;
    private KnowledgeBaseAppService service;

    @BeforeEach
    void setUp() {
        knowledgeBaseRepository = mock(KnowledgeBaseRepository.class);
        fileRepository = mock(FileRepository.class);
        ingestionPipeline = mock(DocumentIngestionPipeline.class);
        fileStorageService = mock(FileStorageService.class);
        ragService = mock(RagService.class);
        vectorStoreCleaner = mock(VectorStoreCleaner.class);
        service = new KnowledgeBaseAppService(knowledgeBaseRepository, fileRepository,
                ingestionPipeline, fileStorageService, ragService, vectorStoreCleaner);
    }

    private KnowledgeBase kb(Long id) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(id);
        kb.setName("kb-" + id);
        kb.setScope(KnowledgeBase.SCOPE_GLOBAL);
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
    @DisplayName("删除文件按 fileId 联动清理向量")
    void shouldCleanVectorsOnFileDelete() {
        File file = new File();
        file.setId(9L);
        file.setKnowledgeBaseId(1L);
        file.setPath("/tmp/x.md");
        when(fileRepository.findById(9L)).thenReturn(Optional.of(file));

        service.deleteFile(1L, 9L);

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
        verify(ingestionPipeline, never()).ingest(any(), any(), any());
    }

    @Test
    @DisplayName("上传 .md 文件正常流转（落盘->元数据->异步摄入）")
    void shouldAcceptMarkdownUpload() {
        when(knowledgeBaseRepository.findById(1L)).thenReturn(Optional.of(kb(1L)));
        when(fileStorageService.store(any())).thenReturn("/tmp/x.md");
        MockMultipartFile md = new MockMultipartFile(
                "file", "note.md", "text/markdown", "# 标题\n内容".getBytes());

        assertThatCode(() -> service.upload(1L, md)).doesNotThrowAnyException();
        verify(ingestionPipeline).ingest(any(), eq(KnowledgeBase.SCOPE_GLOBAL), any());
    }
}
