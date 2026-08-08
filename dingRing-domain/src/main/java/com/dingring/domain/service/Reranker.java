package com.dingring.domain.service;

import java.util.List;

/**
 * 文档重排端口（依赖倒置，infrastructure 层用 LLM 实现）。
 * <p>向量召回 Top-20 后，用 LLM 对候选文档按相关性打分，取 Top-5。
 */
public interface Reranker {

    /**
     * 对候选文档按相关性重排。
     *
     * @param query      检索文本
     * @param candidates 候选文档列表（向量召回结果）
     * @return 按相关性排序的 Top-5 文档
     */
    List<ScoredDocument> rerank(String query, List<String> candidates);

    /**
     * 带分数的重排文档。
     *
     * @param content 文档内容
     * @param score   相关性分数（0-10）
     */
    record ScoredDocument(String content, double score) {}
}
