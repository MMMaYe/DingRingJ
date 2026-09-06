package com.dingring.infrastructure.rag.parser;

import java.util.List;
import java.util.Map;

public record MarkdownBlock(
        int index,
        BlockType type,
        String text,
        List<String> headingPath,
        int startLine,
        int endLine,
        int startOffset,
        int endOffset,
        String language,
        Map<String, Object> attributes) {

    public MarkdownBlock {
        headingPath = headingPath == null ? List.of() : List.copyOf(headingPath);
        language = language == null ? "" : language;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
