package com.dingring.infrastructure.rag.splitter;

import com.dingring.infrastructure.rag.parser.MarkdownBlock;

import java.util.List;

public interface SemanticBoundaryDetector {

    List<List<String>> split(List<String> sentences, int maxTokens, int minTokens, TokenCounter tokenCounter);
}
