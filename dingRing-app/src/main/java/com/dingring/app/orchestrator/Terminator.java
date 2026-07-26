package com.dingring.app.orchestrator;

import com.dingring.domain.group.MessageRepository;
import com.dingring.domain.group.SenderType;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 终止判定：@专家 或 达到最大轮次时触发讨论收束（见技术方案 4.5）。
 * <p>轮次 = 当前 Topic 内 Agent 发言总条数。
 */
@Component
@RequiredArgsConstructor
public class Terminator {

    private final MessageRepository messageRepository;

    /** 最大讨论轮次（Agent 发言条数上限，可配置） */
    @Value("${dingring.orchestrator.max-rounds:20}")
    private int maxRounds;

    /** 每条用户消息触发的 Agent 自动接续发言条数 */
    @Value("${dingring.orchestrator.auto-replies:2}")
    private int autoReplies;

    /** 是否已达最大轮次（触发自动收束，triggeredBy=MAX_ROUNDS） */
    public boolean reachedMaxRounds(Long topicId) {
        if (topicId == null) {
            return false;
        }
        return countAgentMessages(topicId) >= maxRounds;
    }

    /** 当前轮次（Agent 发言条数） */
    public long currentRound(Long topicId) {
        return topicId == null ? 0 : countAgentMessages(topicId);
    }

    public int getMaxRounds() {
        return maxRounds;
    }

    public int getAutoReplies() {
        return autoReplies;
    }

    private long countAgentMessages(Long topicId) {
        return messageRepository.countByTopicIdAndSenderType(topicId, SenderType.AGENT);
    }
}
