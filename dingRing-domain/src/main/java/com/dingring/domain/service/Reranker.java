package com.dingring.domain.service;

import java.util.List;

/**
 * 文档重排端口（依赖倒置，infrastructure 层实现）。
 * <p>P3 前为单阶段：向量召回 Top-20 后，用 LLM 对候选文档按相关性打分，取 Top-5。
 * <p>P3 起主链路走 {@link #rerankStructured}（7.4：SiliconFlow Qwen3-Reranker-4B，
 * relevance_score 0-1）；旧 {@link #rerank} 保留为 legacy 兼容（retrieve 旧链路仍可用）。
 */
public interface Reranker {

    /**
     * 对候选文档按相关性重排（legacy 单阶段链路）。
     *
     * @param query      检索文本
     * @param candidates 候选文档列表（向量召回结果）
     * @return 按相关性排序的 Top-5 文档
     */
    List<ScoredDocument> rerank(String query, List<String> candidates);

    /**
     * 结构化重排（P3 多阶段召回 7.4）：带完整溯源信息的候选重排。
     * <p>实现方把 relevance_score（0-1）写回候选的 score 字段并按降序返回。
     *
     * @param query      检索文本（已规范化/改写后的 query）
     * @param candidates 候选列表（RRF 融合后）
     * @return 重排结果；null 表示不支持或调用失败——调用方回退 RRF 顺序（不抛异常）
     */
    default RerankOutcome rerankStructured(String query, List<RetrievalCandidate> candidates) {
        return null;
    }

    /** 带分数的重排文档（legacy）。 */
    record ScoredDocument(String content, double score) {}

    /**
     * 结构化重排结果。
     *
     * @param ranked 按相关性降序的候选（score 已更新为 relevance_score 0-1）
     * @param tokensIn  rerank API 用量（输入 token，用于成本核算；无值传 0）
     * @param tokensOut rerank API 用量（输出 token，用于成本核算；无值传 0）
     */
    record RerankOutcome(List<RetrievalCandidate> ranked, long tokensIn, long tokensOut) {
        public RerankOutcome {
            ranked = ranked == null ? List.of() : List.copyOf(ranked);
        }
    }
}
