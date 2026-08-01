package com.dingring.app.orchestrator;

import com.dingring.domain.agent.Agent;
import com.dingring.infrastructure.aop.Event;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 调度算法：为每个候选 Agent 评分，选最高分者发言（见技术方案 6.7）。
 * <p>专家 Agent 不参与普通调度，候选列表由调用方保证只含普通成员 Agent。
 */
@Component
public class SpeakerScheduler {

    /** @提及短路分 */
    private static final int MENTION_SCORE = 1000;
    /** 引用提升分 */
    private static final int REPLY_BONUS = 500;
    /** 自由基础分 */
    private static final int BASE_SCORE = 100;
    /** 轮次均衡惩罚系数 */
    private static final int SPEAK_PENALTY = 10;
    /** 随机扰动上界（不含） */
    private static final int RANDOM_BOUND = 21;

    /**
     * 选出发言 Agent（按分数降序）。
     *
     * @param candidates 候选 Agent（普通成员，不含专家）
     * @return 评分结果降序列表（首位即发言者；空列表 = 无候选）
     */
    @Event(eventCode = "RANK_SPEAKERS", eventName = "为候选Agent评分")
    public List<ScoredAgent> rank(List<Agent> candidates, MessageContext ctx) {
        return candidates.stream()
                .map(agent -> new ScoredAgent(agent, calculateScore(agent, ctx), reasonOf(agent, ctx)))
                .sorted(Comparator.comparingInt(ScoredAgent::score).reversed())
                .toList();
    }

    /**
     * 最终分数 = @提及分 + 引用提升分 + 自由基础分 - 轮次均衡惩罚 + 随机扰动
     * <p>优先级规则（短路，从上到下）：
     * <ol>
     * <li>@提及 → 直接返回 1000（最高优先级，短路）</li>
     * <li>引用回复 → +500</li>
     * <li>自由基础分 → 100</li>
     * <li>轮次均衡惩罚 → -speakCount * 10</li>
     * <li>随机扰动 → +random(0, 20)（模拟"有的同事在忙其他事"）</li>
     * </ol>
     */
    int calculateScore(Agent agent, MessageContext ctx) {
        // 规则1：@提及短路
        if (ctx.isMentioned(agent.getId())) {
            return MENTION_SCORE;
        }
        int score = BASE_SCORE; // 规则3：自由基础分
        // 规则2：引用提升
        if (agent.getId().equals(ctx.getRepliedToAgentId())) {
            score += REPLY_BONUS;
        }
        // 规则4：轮次均衡惩罚
        score -= (int) ctx.speakCountOf(agent.getId()) * SPEAK_PENALTY;
        // 规则5：随机扰动
        score += ThreadLocalRandom.current().nextInt(0, RANDOM_BOUND);
        return score;
    }

    private String reasonOf(Agent agent, MessageContext ctx) {
        if (ctx.isMentioned(agent.getId())) {
            return "MENTIONED";
        }
        if (agent.getId().equals(ctx.getRepliedToAgentId())) {
            return "REPLIED";
        }
        return "FREE_SCHEDULE";
    }

    /** 评分结果 */
    public record ScoredAgent(Agent agent, int score, String reason) {
    }
}
