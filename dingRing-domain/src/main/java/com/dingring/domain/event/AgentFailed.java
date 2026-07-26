package com.dingring.domain.event;

import lombok.Getter;

/**
 * Agent LLM 调用失败且已降级事件（订阅方：日志记录 + 前端通知）。
 */
@Getter
public class AgentFailed extends DomainEvent {

    private final Long groupId;
    private final Long topicId;
    private final Long agentId;
    private final String agentName;
    private final String errorMessage;
    private final Long fallbackAgentId;
    private final String fallbackAgentName;

    public AgentFailed(Long groupId, Long topicId, Long agentId, String agentName,
                       String errorMessage, Long fallbackAgentId, String fallbackAgentName) {
        this.groupId = groupId;
        this.topicId = topicId;
        this.agentId = agentId;
        this.agentName = agentName;
        this.errorMessage = errorMessage;
        this.fallbackAgentId = fallbackAgentId;
        this.fallbackAgentName = fallbackAgentName;
    }
}
