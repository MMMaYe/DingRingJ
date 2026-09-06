package com.dingring.domain.service;

import java.util.List;

public record RetrievalResult(
        String rawQuery,
        String rewrittenQuery,
        List<RetrievalCandidate> candidates,
        boolean cacheHit,
        String fallbackReason) {

    public RetrievalResult {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    public static RetrievalResult empty(String query) {
        return new RetrievalResult(query, query, List.of(), false, null);
    }
}
