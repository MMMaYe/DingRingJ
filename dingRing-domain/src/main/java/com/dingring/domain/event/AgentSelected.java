package com.dingring.domain.event;

import lombok.Getter;

/**
 * 已决策出发言 Agent 事件（订阅方：推送"正在输入"到前端）。
 */
@Getter
public class AgentSelected extends DomainEvent {

    private final Long groupId;
    private final Long topicId;
    private final Long agentId;
    private final String agentName;
    /** MENTIONED / REPLIED / FREE_SCHEDULE */
    private final String reason;
    private final int score;

    public AgentSelected(Long groupId, Long topicId, Long agentId, String agentName, String reason, int score) {
        this.groupId = groupId;
        this.topicId = topicId;
        this.agentId = agentId;
        this.agentName = agentName;
        this.reason = reason;
        this.score = score;
    }
}
