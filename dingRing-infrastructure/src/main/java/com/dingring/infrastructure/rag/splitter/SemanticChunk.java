package com.dingring.infrastructure.rag.splitter;

import com.dingring.infrastructure.rag.parser.BlockType;

import java.util.List;
import java.util.Set;

public record SemanticChunk(
        int index,
        String content,
        List<String> headingPath,
        Set<BlockType> blockTypes,
        int sourceStartLine,
        int sourceEndLine,
        int sourceStartOffset,
        int sourceEndOffset,
        String language,
        String parentChunkId) {

    public SemanticChunk {
        headingPath = headingPath == null ? List.of() : List.copyOf(headingPath);
        blockTypes = blockTypes == null ? Set.of() : Set.copyOf(blockTypes);
        language = language == null ? "" : language;
    }
}
