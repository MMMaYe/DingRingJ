package com.dingring.infrastructure.rag;

import com.alibaba.cloud.ai.parser.markdown.MarkdownDocumentParser;
import com.alibaba.cloud.ai.parser.tika.TikaDocumentParser;
import com.dingring.domain.knowledgebase.File;
import com.dingring.infrastructure.rag.splitter.FixedSizeTextSplitter;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * Legacy 摄入路径（P2 固定滑窗），dingring.rag.chunk.strategy=fixed 时使用。
 * 保留旧解析/切片行为作为评测基线与回退，不写 P3 溯源 metadata。
 */
@Component
public class LegacyIngestionSupport {

    private final TikaDocumentParser tikaDocumentParser;
    private final MarkdownDocumentParser markdownDocumentParser;
    private final FixedSizeTextSplitter textSplitter;

    public LegacyIngestionSupport(TikaDocumentParser tikaDocumentParser,
                                   MarkdownDocumentParser markdownDocumentParser,
                                   FixedSizeTextSplitter textSplitter) {
        this.tikaDocumentParser = tikaDocumentParser;
        this.markdownDocumentParser = markdownDocumentParser;
        this.textSplitter = textSplitter;
    }

    public List<Document> parseAndSplit(File file) throws IOException {
        List<Document> documents;
        try (InputStream is = Files.newInputStream(Paths.get(file.getPath()))) {
            if (File.TYPE_MARKDOWN.equals(file.getFileType())) {
                documents = markdownDocumentParser.parse(is);
            } else {
                documents = tikaDocumentParser.parse(is);
            }
        }
        return textSplitter.apply(documents);
    }
}
