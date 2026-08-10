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
 * <p>评分公式：自由(100) + @提及(+800) + 引用回复(+500) - 轮次惩罚(*10) + 随机(0~20)，统一排序无短路
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
        @DisplayName("@提及 +800 大权重加分（基础 100 + 提及 800 + 随机 0~20 = [900, 920]）")
        void mentionedAgentShouldGetMentionBoost() {
            Agent a = agent(10L, "老王");
            MessageContext ctx = MessageContext.builder()
                    .mentionedAgentIds(List.of(10L))
                    .speakCounts(Map.of())
                    .build();

            int score = scheduler.calculateScore(a, ctx);

            assertThat(score).isBetween(900, 920);
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
        @DisplayName("@提及加分与引用叠加（不再短路：100 + 800 + 500 - 50 + 随机 = [1350, 1370]）")
        void mentionShouldStackWithReplyBonus() {
            Agent a = agent(10L, "老王");
            MessageContext ctx = MessageContext.builder()
                    .mentionedAgentIds(List.of(10L))
                    .repliedToAgentId(10L)
                    .speakCounts(Map.of(10L, 5L))
                    .build();

            int score = scheduler.calculateScore(a, ctx);

            // @提及 +800 与引用 +500 叠加生效，轮次惩罚 50 也生效（无短路）
            assertThat(score).isBetween(1350, 1370);
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
            assertThat(ranked.get(0).score()).isBetween(900, 920);
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

            // 按分数降序：老王(MENTIONED,900~920) > 小李(REPLIED,600~620) > 小张(FREE,100~120)
            assertThat(ranked.get(0).agent().getName()).isEqualTo("老王");
            assertThat(ranked.get(0).reason()).isEqualTo("MENTIONED");
            assertThat(ranked.get(1).agent().getName()).isEqualTo("小李");
            assertThat(ranked.get(1).reason()).isEqualTo("REPLIED");
            assertThat(ranked.get(2).agent().getName()).isEqualTo("小张");
            assertThat(ranked.get(2).reason()).isEqualTo("FREE_SCHEDULE");
        }
    }
}
