package com.dingring.app.orchestrator;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 一次调度的消息上下文（评分输入）。
 */
@Data
@Builder
public class MessageContext {

    private Long groupId;
    private Long topicId;
    /** 触发调度的消息内容 */
    private String content;
    /** 消息中 @提及 的 Agent ID 列表 */
    private List<Long> mentionedAgentIds;
    /** 引用回复的目标 Agent ID（未引用 Agent 消息则 null） */
    private Long repliedToAgentId;
    /** Topic 内各 Agent 已发言次数（轮次均衡用） */
    private Map<Long, Long> speakCounts;

    public long speakCountOf(Long agentId) {
        if (speakCounts == null) {
            return 0;
        }
        return speakCounts.getOrDefault(agentId, 0L);
    }

    public boolean isMentioned(Long agentId) {
        return mentionedAgentIds != null && mentionedAgentIds.contains(agentId);
    }
}
