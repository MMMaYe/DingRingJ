package com.dingring.domain.service;

import java.util.List;
import java.util.Map;

public record RetrievalCandidate(
        String candidateId,
        String chunkId,
        String content,
        String fileName,
        List<String> headingPath,
        String sourceLocation,
        double score,
        Map<String, Object> metadata) {

    public RetrievalCandidate {
        headingPath = headingPath == null ? List.of() : List.copyOf(headingPath);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
