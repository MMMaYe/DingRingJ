package com.dingring.infrastructure.rag.retrieval;

import com.dingring.domain.service.RetrievalCandidate;
import com.dingring.infrastructure.rag.config.RagProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF 融合与 MMR 选择（7.3/7.4，纯算法无 IO，可单测）。
 * <p>RRF（Reciprocal Rank Fusion）：score = Σ 1/(K + rank)。
 * 两路通道分数量纲不同（cosine vs word_similarity）不可直接比大小，
 * 只用排名融合——这正是 RRF 的核心优势。
 * <p>去重：以 PG 行 id（candidateId）为主键——dense 与 lexical 召回同一 chunk 时
 * 行 id 相同，双通道排名都计入该 chunk 的 RRF 分数（排名互补增强）。
 * <p>MMR（Maximal Marginal Relevance）：λ*relevance - (1-λ)*max_sim(selected)，
 * 在相关性与多样性间权衡，避免目录里同章节近重复 chunk 刷屏。
 */
@Component
@ConditionalOnProperty(name = "dingring.rag.enabled", havingValue = "true", matchIfMissing = true)
public class RrfFuser {

    private final RagProperties ragProperties;

    public RrfFuser(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    /**
     * 融合 dense 与 lexical 两路候选。
     *
     * @param dense   向量召回（按相似度降序）
     * @param lexical 词法召回（按 word_similarity 降序）
     * @return RRF 分数降序的前 rrfTopK 个候选（score 字段为 RRF 分数）
     */
    public List<RetrievalCandidate> fuse(List<RetrievalCandidate> dense, List<RetrievalCandidate> lexical) {
        int k = ragProperties.getRetrieval().getRrfK();
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, RetrievalCandidate> candidates = new LinkedHashMap<>();

        accumulate(dense, k, scores, candidates);
        accumulate(lexical, k, scores, candidates);

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(ragProperties.getRetrieval().getRrfTopK())
                .map(e -> withScore(candidates.get(e.getKey()), e.getValue()))
                .toList();
    }

    private static void accumulate(List<RetrievalCandidate> channel, int k,
                                   Map<String, Double> scores, Map<String, RetrievalCandidate> candidates) {
        if (channel == null) {
            return;
        }
        for (int i = 0; i < channel.size(); i++) {
            String id = channel.get(i).candidateId();
            if (id == null) {
                continue;
            }
            // rank 从 1 起：通道内排名越靠前贡献越大
            scores.merge(id, 1.0 / (k + i + 1), Double::sum);
            candidates.putIfAbsent(id, channel.get(i));
        }
    }

    private static RetrievalCandidate withScore(RetrievalCandidate c, double score) {
        return new RetrievalCandidate(c.candidateId(), c.chunkId(), c.content(), c.fileName(),
                c.headingPath(), c.sourceLocation(), score, c.metadata());
    }

    /**
     * MMR 多样性选择。
     *
     * @param ranked     rerank 后的相关性降序列表（score = relevance 0-1）
     * @param embeddings candidateId → 候选向量（缺失向量的候选按与已选无相似度处理）
     * @param topK      输出条数
     * @return MMR 顺序的 topK 候选
     */
    public List<RetrievalCandidate> selectDiverse(List<RetrievalCandidate> ranked,
                                                  Map<String, float[]> embeddings, int topK) {
        if (ranked == null || ranked.size() <= topK) {
            return ranked == null ? List.of() : ranked;
        }
        double lambda = ragProperties.getRetrieval().getMmrLambda();
        List<RetrievalCandidate> selected = new ArrayList<>(topK);
        List<RetrievalCandidate> rest = new ArrayList<>(ranked);

        while (!rest.isEmpty() && selected.size() < topK) {
            RetrievalCandidate best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (RetrievalCandidate c : rest) {
                float[] vec = embeddings.get(c.candidateId());
                double redundancy = 0;
                if (vec != null && vec.length > 0) {
                    for (RetrievalCandidate s : selected) {
                        float[] sv = embeddings.get(s.candidateId());
                        if (sv != null && sv.length == vec.length) {
                            redundancy = Math.max(redundancy, cosine(vec, sv));
                        }
                    }
                }
                double mmr = lambda * c.score() - (1 - lambda) * redundancy;
                if (mmr > bestScore) {
                    bestScore = mmr;
                    best = c;
                }
            }
            selected.add(best);
            rest.remove(best);
        }
        return selected;
    }

    /** 余弦相似度（向量已归一化时等于点积；pgvector 存储即归一化向量） */
    static double cosine(float[] a, float[] b) {
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
