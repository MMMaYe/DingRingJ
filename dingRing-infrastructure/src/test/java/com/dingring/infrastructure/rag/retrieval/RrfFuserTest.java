package com.dingring.infrastructure.rag.retrieval;

import com.dingring.domain.service.RetrievalCandidate;
import com.dingring.infrastructure.rag.config.RagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RrfFuser} P3 用例（7.3/7.4）：RRF 融合排名互补、双通道去重、
 * topK 截断、MMR 多样性选择。
 */
@DisplayName("RRF 融合与 MMR 选择")
class RrfFuserTest {

    private RagProperties ragProperties;
    private RrfFuser fuser;

    @BeforeEach
    void setUp() {
        ragProperties = new RagProperties();
        fuser = new RrfFuser(ragProperties);
    }

    private static RetrievalCandidate candidate(String id, String content, double score) {
        return new RetrievalCandidate(id, id + "-chunk", content, "doc.md",
                List.of("章节"), "L1-L10", score, Map.of());
    }

    @Test
    @DisplayName("双通道命中同一候选：RRF 分数合并，排名高于单通道候选")
    void shouldMergeScoreWhenBothChannelsHit() {
        // a 在 dense 第 1 + lexical 第 1；b 只在 dense 第 2
        List<RetrievalCandidate> dense = List.of(candidate("a", "A", 0.9), candidate("b", "B", 0.8));
        List<RetrievalCandidate> lexical = List.of(candidate("a", "A", 0.95));

        List<RetrievalCandidate> fused = fuser.fuse(dense, lexical);

        assertThat(fused).hasSize(2);
        assertThat(fused.get(0).candidateId()).isEqualTo("a");
        // a = 1/(60+1) + 1/(60+1) > b = 1/(60+2)
        assertThat(fused.get(0).score()).isGreaterThan(fused.get(1).score());
        assertThat(fused.get(0).score()).isEqualTo(2.0 / 61);
    }

    @Test
    @DisplayName("lexical 单通道覆盖 dense 盲区：仅 lexical 命中的候选也参与融合")
    void shouldIncludeLexicalOnlyCandidate() {
        List<RetrievalCandidate> dense = List.of(candidate("a", "A", 0.9));
        List<RetrievalCandidate> lexical = List.of(candidate("x", "X", 0.6));

        List<RetrievalCandidate> fused = fuser.fuse(dense, lexical);

        assertThat(fused).extracting(RetrievalCandidate::candidateId).containsExactlyInAnyOrder("a", "x");
    }

    @Test
    @DisplayName("融合结果按 rrfTopK 截断（默认 40）")
    void shouldTruncateToRrfTopK() {
        ragProperties.getRetrieval().setRrfTopK(3);
        List<RetrievalCandidate> dense = List.of(
                candidate("a", "A", 0.9), candidate("b", "B", 0.8),
                candidate("c", "C", 0.7), candidate("d", "D", 0.6),
                candidate("e", "E", 0.5));

        List<RetrievalCandidate> fused = fuser.fuse(dense, List.of());

        assertThat(fused).hasSize(3);
    }

    @Test
    @DisplayName("MMR：近重复高相关候选被压制，多样候选保留")
    void shouldPreferDiverseCandidates() {
        // rel 顺序：a(0.95) b(0.94) c(0.60)；a/b 向量相同（重复），c 正交
        List<RetrievalCandidate> ranked = List.of(
                candidate("a", "A", 0.95), candidate("b", "B", 0.94), candidate("c", "C", 0.60));
        float[] same = {1f, 0f};
        float[] orthogonal = {0f, 1f};
        Map<String, float[]> embeddings = Map.of("a", same, "b", same, "c", orthogonal);

        List<RetrievalCandidate> selected = fuser.selectDiverse(ranked, embeddings, 2);

        // λ=0.7：b 的 MMR = 0.7*0.94 - 0.3*1(与a重复) = 0.358 < c 的 0.7*0.6 = 0.42
        assertThat(selected).extracting(RetrievalCandidate::candidateId).containsExactly("a", "c");
    }

    @Test
    @DisplayName("MMR：候选数不超 topK 时原样返回")
    void shouldReturnAllWhenUnderTopK() {
        List<RetrievalCandidate> ranked = List.of(candidate("a", "A", 0.9), candidate("b", "B", 0.8));

        List<RetrievalCandidate> selected = fuser.selectDiverse(ranked, Map.of(), 5);

        assertThat(selected).hasSize(2);
    }

    @Test
    @DisplayName("cosine：正交为 0，相同为 1")
    void shouldComputeCosine() {
        assertThat(RrfFuser.cosine(new float[]{1, 0}, new float[]{0, 1})).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(RrfFuser.cosine(new float[]{0.5f, 0.5f}, new float[]{0.5f, 0.5f})).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(RrfFuser.cosine(new float[0], new float[]{1})).isEqualTo(0.0);
    }
}
