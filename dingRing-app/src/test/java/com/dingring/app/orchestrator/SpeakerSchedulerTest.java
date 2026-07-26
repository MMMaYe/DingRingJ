package com.dingring.app.orchestrator;

import com.dingring.domain.agent.Agent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SpeakerScheduler} 调度算法单元测试。
 * <p>评分公式：@提及(1000) > 引用回复(+500) > 自由(100) - 轮次惩罚(*10) + 随机(0~20)
 */
@DisplayName("SpeakerScheduler 调度算法")
class SpeakerSchedulerTest {

    private final SpeakerScheduler scheduler = new SpeakerScheduler();

    private Agent agent(Long id, String name) {
        Agent a = new Agent();
        a.setId(id);
        a.setName(name);
        return a;
    }

    @Nested
    @DisplayName("calculateScore 评分")
    class CalculateScore {

        @Test
        @DisplayName("@提及短路返回 1000")
        void mentionedAgentShouldGetMentionScore() {
            Agent a = agent(10L, "老王");
            MessageContext ctx = MessageContext.builder()
                    .mentionedAgentIds(List.of(10L))
                    .speakCounts(Map.of())
                    .build();

            int score = scheduler.calculateScore(a, ctx);

            assertThat(score).isEqualTo(1000);
        }

        @Test
        @DisplayName("引用回复 +500")
        void repliedAgentShouldGetReplyBonus() {
            Agent a = agent(10L, "老王");
            MessageContext ctx = MessageContext.builder()
                    .repliedToAgentId(10L)
                    .speakCounts(Map.of())
                    .build();

            int score = scheduler.calculateScore(a, ctx);

            // 基础 100 + 引用 500 + 随机 0~20 = [600, 620]
            assertThat(score).isBetween(600, 620);
        }

        @Test
        @DisplayName("自由调度：基础分 100 + 随机 0~20")
        void freeScheduleShouldGetBasePlusRandom() {
            Agent a = agent(10L, "老王");
            MessageContext ctx = MessageContext.builder()
                    .speakCounts(Map.of())
                    .build();

            int score = scheduler.calculateScore(a, ctx);

            assertThat(score).isBetween(100, 120);
        }

        @Test
        @DisplayName("已发言 3 次时惩罚 30 分")
        void speakCountShouldPenaltyScore() {
            Agent a = agent(10L, "老王");
            MessageContext ctx = MessageContext.builder()
                    .speakCounts(Map.of(10L, 3L))
                    .build();

            int score = scheduler.calculateScore(a, ctx);

            // 100 - 30 + 随机 0~20 = [70, 90]
            assertThat(score).isBetween(70, 90);
        }

        @Test
        @DisplayName("@提及优先级高于引用回复（即使被引用也不加 500）")
        void mentionShouldShortCircuitReplyBonus() {
            Agent a = agent(10L, "老王");
            MessageContext ctx = MessageContext.builder()
                    .mentionedAgentIds(List.of(10L))
                    .repliedToAgentId(10L)
                    .speakCounts(Map.of(10L, 5L))
                    .build();

            int score = scheduler.calculateScore(a, ctx);

            // @提及短路，引用与惩罚都不生效
            assertThat(score).isEqualTo(1000);
        }
    }

    @Nested
    @DisplayName("rank 排序")
    class Rank {

        @Test
        @DisplayName("被@的 Agent 排第一")
        void mentionedAgentShouldRankFirst() {
            Agent a1 = agent(10L, "老王");
            Agent a2 = agent(11L, "小李");
            MessageContext ctx = MessageContext.builder()
                    .mentionedAgentIds(List.of(11L))
                    .speakCounts(Map.of())
                    .build();

            List<SpeakerScheduler.ScoredAgent> ranked = scheduler.rank(List.of(a1, a2), ctx);

            assertThat(ranked.get(0).agent()).isEqualTo(a2);
            assertThat(ranked.get(0).reason()).isEqualTo("MENTIONED");
            assertThat(ranked.get(0).score()).isEqualTo(1000);
        }

        @Test
        @DisplayName("被引用的 Agent 排名靠前")
        void repliedAgentShouldRankHigh() {
            Agent a1 = agent(10L, "老王");
            Agent a2 = agent(11L, "小李");
            MessageContext ctx = MessageContext.builder()
                    .repliedToAgentId(11L)
                    .speakCounts(Map.of())
                    .build();

            List<SpeakerScheduler.ScoredAgent> ranked = scheduler.rank(List.of(a1, a2), ctx);

            assertThat(ranked.get(0).agent()).isEqualTo(a2);
            assertThat(ranked.get(0).reason()).isEqualTo("REPLIED");
        }

        @Test
        @DisplayName("空候选列表返回空列表")
        void emptyCandidatesShouldReturnEmpty() {
            MessageContext ctx = MessageContext.builder().speakCounts(Map.of()).build();

            assertThat(scheduler.rank(List.of(), ctx)).isEmpty();
        }

        @Test
        @DisplayName("reason 字段正确反映命中规则")
        void reasonShouldReflectRule() {
            Agent mentioned = agent(10L, "老王");
            Agent replied = agent(11L, "小李");
            Agent free = agent(12L, "小张");
            MessageContext ctx = MessageContext.builder()
                    .mentionedAgentIds(List.of(10L))
                    .repliedToAgentId(11L)
                    .speakCounts(Map.of())
                    .build();

            List<SpeakerScheduler.ScoredAgent> ranked = scheduler.rank(List.of(free, replied, mentioned), ctx);

            // 按分数降序：老王(MENTIONED,1000) > 小李(REPLIED,600~620) > 小张(FREE,100~120)
            assertThat(ranked.get(0).agent().getName()).isEqualTo("老王");
            assertThat(ranked.get(0).reason()).isEqualTo("MENTIONED");
            assertThat(ranked.get(1).agent().getName()).isEqualTo("小李");
            assertThat(ranked.get(1).reason()).isEqualTo("REPLIED");
            assertThat(ranked.get(2).agent().getName()).isEqualTo("小张");
            assertThat(ranked.get(2).reason()).isEqualTo("FREE_SCHEDULE");
        }
    }
}
