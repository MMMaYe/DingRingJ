package com.dingring.infrastructure.rag;

import com.dingring.domain.knowledgebase.DocumentVersionRepository;
import com.dingring.domain.knowledgebase.File;
import com.dingring.domain.knowledgebase.FileRepository;
import com.dingring.domain.knowledgebase.IngestionRun;
import com.dingring.domain.knowledgebase.IngestionRunRepository;
import com.dingring.infrastructure.rag.parser.MarkdownStructureParser;
import com.dingring.infrastructure.rag.splitter.MarkdownSemanticChunker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DocumentIngestionPipeline} 结构感知摄入单测。
 * <p>不启动 Spring 容器：全部依赖 mock 后直接构造注入，
 * 验证 metadata 完整性、run 幂等门控与删除竞态防护。
 */
@DisplayName("DocumentIngestionPipeline 结构感知摄入")
class DocumentIngestionPipelineTest {

    @TempDir
    Path tempDir;

    private final VectorStore vectorStore = mock(VectorStore.class);
    private final FileRepository fileRepository = mock(FileRepository.class);
    private final IngestionRunRepository runRepository = mock(IngestionRunRepository.class);
    private final DocumentVersionRepository versionRepository = mock(DocumentVersionRepository.class);
    private final LegacyIngestionSupport legacy = mock(LegacyIngestionSupport.class);

    private DocumentIngestionPipeline pipeline;

    /** 确定性假 token 计数：中文 1 字 = 1 token，ASCII 2 字符 = 1 token */
    private static final com.dingring.infrastructure.rag.splitter.TokenCounter FAKE =
            text -> {
                if (text == null || text.isEmpty()) {
                    return 0;
                }
                int ascii = 0;
                int wide = 0;
                for (int i = 0; i < text.length(); i++) {
                    if (text.charAt(i) > 127) {
                        wide++;
                    } else {
                        ascii++;
                    }
                }
                return wide + (ascii + 1) / 2;
            };

    @BeforeEach
    void setUp() {
        // cleaningEnabled=false：本测试聚焦切片/入库逻辑，关闭内置清洗分支；
        // vectorStoreCleaner mock 不 stub（幂等清理调用无断言，默认 no-op）
        pipeline = new DocumentIngestionPipeline(vectorStore, fileRepository, runRepository,
                versionRepository, new MarkdownStructureParser(),
                new MarkdownSemanticChunker(FAKE, 600, 120, 900, 1800), legacy, "md-semantic",
                false, null, org.mockito.Mockito.mock(VectorStoreCleaner.class));
    }

    private File mdFile(Path dir, String content) throws IOException {
        Path path = dir.resolve("doc.md");
        Files.writeString(path, content);
        File file = new File();
        file.setId(1L);
        file.setName("doc.md");
        file.setPath(path.toString());
        file.setFileType(File.TYPE_MARKDOWN);
        file.setKnowledgeBaseId(10L);
        file.setStatus(File.STATUS_UPLOADED);
        return file;
    }

    private IngestionRun activeRun(File file, String docContentHash) {
        IngestionRun run = new IngestionRun();
        run.setId(100L);
        run.setRunId("run-1");
        run.setFileId(file.getId());
        run.setDocContentHash(docContentHash);
        run.setDocumentVersion(1);
        run.setExecutor(IngestionRun.EXECUTOR_BUILTIN);
        run.setStatus(IngestionRun.STATUS_UPLOAD_CREATED);
        run.setAttempt(0);
        run.setActiveSlot(file.getId());
        return run;
    }

    @Test
    @DisplayName("md 摄入写入完整溯源 metadata 且 run 置 READY")
    void mdIngestWritesTraceableMetadataAndCompletesRun() throws IOException {
        File file = mdFile(tempDir, "# 活动管理\n正文段落。\n");
        IngestionRun run = activeRun(file, com.dingring.common.util.HashUtil.sha256(
                Files.readString(tempDir.resolve("doc.md"))));
        when(runRepository.findByRunId("run-1")).thenReturn(Optional.of(run));
        when(versionRepository.findByFileAndVersion(1L, 1)).thenReturn(Optional.empty());

        pipeline.ingest(file, run);

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        Map<String, Object> metadata = captor.getValue().get(0).getMetadata();
        assertThat(metadata)
                .containsEntry("kbId", 10L)
                .containsEntry("fileId", 1L)
                .containsEntry("documentVersion", 1)
                .containsEntry("chunkingVersion", "md-semantic-v1")
                .containsEntry("active", true)
                .containsKey("docContentHash")
                .containsKey("chunkContentHash")
                .containsKey("headingPath")
                .containsKey("blockTypes")
                .containsKey("sourceStartLine");
        assertThat(captor.getValue().get(0).getText()).contains("文档：doc", "章节：");
        assertThat(run.getStatus()).isEqualTo(IngestionRun.STATUS_READY);
        assertThat(run.getActiveSlot()).isNull();
        assertThat(file.getStatus()).isEqualTo(File.STATUS_READY);
    }

    @Test
    @DisplayName("hash 与 run 不一致时拒绝摄入并置 FAILED（文件被替换）")
    void mismatchedHashShouldFailRun() throws IOException {
        File file = mdFile(tempDir, "# 标题\n正文");
        IngestionRun run = activeRun(file, "0000000000000000000000000000000000000000000000000000000000000000");

        pipeline.ingest(file, run);

        assertThat(run.getStatus()).isEqualTo(IngestionRun.STATUS_FAILED);
        assertThat(file.getStatus()).isEqualTo(File.STATUS_FAILED);
        verify(vectorStore, never()).add(anyList());
    }

    @Test
    @DisplayName("run 被取消后停止向量写入（删除竞态防护）")
    void cancelledRunStopsVectorWrites() throws IOException {
        File file = mdFile(tempDir, "# 标题\n正文内容足够长。");
        IngestionRun run = activeRun(file, com.dingring.common.util.HashUtil.sha256(
                Files.readString(tempDir.resolve("doc.md"))));
        // 模拟删除已将 run 置 CANCELLED
        run.setStatus(IngestionRun.STATUS_CANCELLED);
        run.setActiveSlot(null);
        when(runRepository.findByRunId("run-1")).thenReturn(Optional.of(run));

        pipeline.ingest(file, run);

        verify(vectorStore, never()).add(anyList());
        assertThat(file.getStatus()).isNotEqualTo(File.STATUS_READY);
    }

    @Test
    @DisplayName("legacy 策略走 LegacyIngestionSupport")
    void legacyStrategyDelegatesToLegacySupport() throws IOException {
        DocumentIngestionPipeline legacyPipeline = new DocumentIngestionPipeline(
                vectorStore, fileRepository, runRepository, versionRepository,
                new MarkdownStructureParser(),
                new MarkdownSemanticChunker(FAKE, 600, 120, 900, 1800), legacy, "fixed",
                false, null, org.mockito.Mockito.mock(VectorStoreCleaner.class));
        File file = mdFile(tempDir, "# 标题\n正文");
        when(legacy.parseAndSplit(file)).thenReturn(List.of(new Document("正文", Map.of())));

        legacyPipeline.ingest(file, null);

        verify(legacy).parseAndSplit(file);
        verify(vectorStore).add(anyList());
        assertThat(file.getStatus()).isEqualTo(File.STATUS_READY);
    }

    @Test
    @DisplayName("空内容标记 FAILED 且不入库")
    void emptyContentShouldMarkFailed() throws IOException {
        File file = mdFile(tempDir, "");
        IngestionRun run = activeRun(file, com.dingring.common.util.HashUtil.sha256(""));

        pipeline.ingest(file, run);

        assertThat(file.getStatus()).isEqualTo(File.STATUS_FAILED);
        verify(vectorStore, never()).add(anyList());
    }
}
