package com.dingring.domain.event;

import lombok.Getter;

import java.util.List;

/**
 * 消息已发送事件（订阅方：调度域触发评分）。
 */
@Getter
public class MessageSent extends DomainEvent {

    private final Long messageId;
    private final Long groupId;
    private final Long topicId;
    private final Long senderId;
    private final String senderType;
    private final String content;
    private final Long replyToMessageId;
    private final List<Long> mentionedAgentIds;

    public MessageSent(Long messageId, Long groupId, Long topicId, Long senderId, String senderType,
                       String content, Long replyToMessageId, List<Long> mentionedAgentIds) {
        this.messageId = messageId;
        this.groupId = groupId;
        this.topicId = topicId;
        this.senderId = senderId;
        this.senderType = senderType;
        this.content = content;
        this.replyToMessageId = replyToMessageId;
        this.mentionedAgentIds = mentionedAgentIds;
    }
}
