package com.dingring.domain.event;

import lombok.Getter;

/**
 * 主题已关闭事件（结论生成成功；订阅方：知识卡片域生成卡片）。
 */
@Getter
public class TopicClosed extends DomainEvent {

    private final Long topicId;
    private final Long groupId;
    private final String title;
    private final String conclusion;
    private final long messageCount;
    /** USER / MAX_ROUNDS */
    private final String triggeredBy;
    private final Long expertAgentId;

    public TopicClosed(Long topicId, Long groupId, String title, String conclusion,
                       long messageCount, String triggeredBy, Long expertAgentId) {
        this.topicId = topicId;
        this.groupId = groupId;
        this.title = title;
        this.conclusion = conclusion;
        this.messageCount = messageCount;
        this.triggeredBy = triggeredBy;
        this.expertAgentId = expertAgentId;
    }
}
