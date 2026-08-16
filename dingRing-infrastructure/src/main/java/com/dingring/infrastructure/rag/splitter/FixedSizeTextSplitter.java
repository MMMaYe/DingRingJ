package com.dingring.infrastructure.rag.splitter;

import com.dingring.infrastructure.rag.config.RagProperties;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Fixed-size 固定字符滑窗切片器（P2，默认 512 字符 / 64 重叠）。
 * <p>策略：步进 chunkSize-overlap 取窗，相邻块共享 overlap 字符保证边界语义连续；
 * 短尾块保留（不足步进时取到文本末尾），短于 chunkSize 的文本整块返回。
 * <p>按 String.length()（字符）而非字节计数：中文场景 1 字 = 1 块单位，直观可控。
 * <p>md 按标题结构切片（SAA MarkdownDocumentParser + Header 切分）列为后续演进，本次严格 Fixed-size。
 */
@Component
@ConditionalOnProperty(name = "dingring.rag.enabled", havingValue = "true", matchIfMissing = true)
public class FixedSizeTextSplitter implements DocumentTransformer {

    private final int chunkSize;
    private final int overlap;

    public FixedSizeTextSplitter(RagProperties ragProperties) {
        this(ragProperties.getChunk().getFixedSize(), ragProperties.getChunk().getOverlap());
    }

    FixedSizeTextSplitter(int chunkSize, int overlap) {
        // 滑窗步进 = chunkSize - overlap，非正数会死循环，构造期快速失败
        if (chunkSize <= 0 || overlap < 0 || overlap >= chunkSize) {
            throw new IllegalArgumentException(
                    "非法切片参数: chunkSize=" + chunkSize + " overlap=" + overlap + "（要求 0 <= overlap < chunkSize）");
        }
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    @Override
    public List<Document> apply(List<Document> documents) {
        List<Document> chunks = new ArrayList<>();
        for (Document doc : documents) {
            splitOne(doc, chunks);
        }
        return chunks;
    }

    private void splitOne(Document doc, List<Document> out) {
        String text = doc.getText() == null ? "" : doc.getText();
        int len = text.length();
        if (len <= chunkSize) {
            out.add(new Document(text, new HashMap<>(doc.getMetadata())));
            return;
        }
        int step = chunkSize - overlap;
        for (int start = 0; start < len; start += step) {
            int end = Math.min(start + chunkSize, len);
            out.add(new Document(text.substring(start, end), new HashMap<>(doc.getMetadata())));
            if (end == len) {
                break;  // 已覆盖文末，避免尾部再加一个不足 overlap 的小块
            }
        }
    }
}
