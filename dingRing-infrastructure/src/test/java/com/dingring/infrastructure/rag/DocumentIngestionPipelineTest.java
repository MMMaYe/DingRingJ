package com.dingring.infrastructure.rag;

import com.alibaba.cloud.ai.parser.markdown.MarkdownDocumentParser;
import com.alibaba.cloud.ai.parser.tika.TikaDocumentParser;
import com.dingring.domain.knowledgebase.File;
import com.dingring.domain.knowledgebase.FileRepository;
import com.dingring.infrastructure.rag.splitter.FixedSizeTextSplitter;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DocumentIngestionPipeline} 按 fileType 分流解析单测。
 * <p>不启动 Spring 容器：全部依赖 mock 后直接构造注入，验证分流与 metadata 合并行为。
 */
@DisplayName("DocumentIngestionPipeline 解析分流")
class DocumentIngestionPipelineTest {

    @TempDir
    Path tempDir;

    private final VectorStore vectorStore = mock(VectorStore.class);
    private final FileRepository fileRepository = mock(FileRepository.class);
    private final TikaDocumentParser tikaParser = mock(TikaDocumentParser.class);
    private final MarkdownDocumentParser markdownParser = mock(MarkdownDocumentParser.class);
    private final FixedSizeTextSplitter splitter = mock(FixedSizeTextSplitter.class);

    /** 切片器 mock 透传（identity）：本测试只验证解析分流与 metadata 合并，切片逻辑由 FixedSizeTextSplitterTest 覆盖 */
    @BeforeEach
    void setUp() {
        when(splitter.apply(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private DocumentIngestionPipeline pipeline() {
        return new DocumentIngestionPipeline(vectorStore, fileRepository, tikaParser, markdownParser, splitter);
    }

    private File mdFile(Path dir) throws IOException {
        Path path = dir.resolve("doc.md");
        Files.writeString(path, "# 标题\n正文内容");
        File file = new File();
        file.setId(1L);
        file.setName("doc.md");
        file.setPath(path.toString());
        file.setFileType(File.TYPE_MARKDOWN);
        file.setKnowledgeBaseId(10L);
        file.setStatus(File.STATUS_UPLOADED);
        return file;
    }

    @Test
    @DisplayName("md 文件走 MarkdownDocumentParser，且 category 与 kbId/fileId metadata 合并入库")
    void mdFileShouldBeParsedByMarkdownParser() throws IOException {
        // parser 返回带结构 metadata 的块（模拟 header 切分输出）
        Document headerChunk = new Document("标题下正文", Map.of("category", "header_1"));
        when(markdownParser.parse(any())).thenReturn(List.of(headerChunk));

        File file = mdFile(tempDir);
        pipeline().ingest(file);

        // 分流：仅 markdown parser 被调用
        verify(markdownParser).parse(any());
        verify(tikaParser, never()).parse(any());
        // 入库：单批 add，metadata 同时含 parser 结构字段与管道溯源字段（无 key 冲突）
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        Map<String, Object> metadata = captor.getValue().get(0).getMetadata();
        assertThat(metadata).containsEntry("category", "header_1")
                .containsEntry("kbId", 10L)
                .containsEntry("fileId", 1L)
                .containsEntry("chunkIndex", 0);
        // 状态机：最终 READY
        ArgumentCaptor<File> fileCaptor = ArgumentCaptor.forClass(File.class);
        verify(fileRepository, times(3)).update(fileCaptor.capture());
        assertThat(fileCaptor.getValue().getStatus()).isEqualTo(File.STATUS_READY);
    }

    @Test
    @DisplayName("非 md 文件走 TikaDocumentParser 兜底")
    void nonMdFileShouldFallbackToTikaParser() throws IOException {
        when(tikaParser.parse(any())).thenReturn(List.of(new Document("纯文本内容")));

        Path path = tempDir.resolve("note.txt");
        Files.writeString(path, "纯文本内容");
        File file = new File();
        file.setId(2L);
        file.setName("note.txt");
        file.setPath(path.toString());
        file.setFileType(File.TYPE_TXT);
        file.setKnowledgeBaseId(10L);
        file.setStatus(File.STATUS_UPLOADED);

        pipeline().ingest(file);

        verify(tikaParser).parse(any());
        verify(markdownParser, never()).parse(any());
        verify(vectorStore).add(anyList());
        assertThat(file.getStatus()).isEqualTo(File.STATUS_READY);
    }

    @Test
    @DisplayName("解析结果为空：文件标记 FAILED 且不入库")
    void emptyParseResultShouldMarkFailed() throws IOException {
        when(markdownParser.parse(any())).thenReturn(List.of());

        File file = mdFile(tempDir);
        pipeline().ingest(file);

        assertThat(file.getStatus()).isEqualTo(File.STATUS_FAILED);
        assertThat(file.getErrorMsg()).contains("文件内容为空");
        verify(vectorStore, never()).add(anyList());
    }
}
