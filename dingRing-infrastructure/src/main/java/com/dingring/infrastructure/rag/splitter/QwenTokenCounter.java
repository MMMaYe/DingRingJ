package com.dingring.infrastructure.rag.splitter;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

@Component
public class QwenTokenCounter implements TokenCounter, AutoCloseable {

    private final Path tokenizerPath;
    private volatile HuggingFaceTokenizer tokenizer;

    public QwenTokenCounter(@Value("${dingring.rag.chunk.tokenizer-path:./models/qwen3-embedding-tokenizer.json}")
                            String tokenizerPath) {
        this.tokenizerPath = Path.of(tokenizerPath);
    }

    @Override
    public int count(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return tokenizer().encode(text).getIds().length;
    }

    private HuggingFaceTokenizer tokenizer() {
        HuggingFaceTokenizer current = tokenizer;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (tokenizer == null) {
                try {
                    tokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath);
                } catch (IOException e) {
                    throw new UncheckedIOException("无法加载 Qwen tokenizer: " + tokenizerPath, e);
                }
            }
            return tokenizer;
        }
    }

    @Override
    public void close() {
        HuggingFaceTokenizer current = tokenizer;
        if (current != null) {
            current.close();
        }
    }
}
