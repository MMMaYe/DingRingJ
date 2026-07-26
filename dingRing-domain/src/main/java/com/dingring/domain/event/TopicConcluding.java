package com.dingring.domain.event;

import lombok.Getter;

/**
 * 触发结束讨论事件（订阅方：阻止新消息进入此 Topic、前端状态提示）。
 */
@Getter
public class TopicConcluding extends DomainEvent {

    private final Long topicId;
    private final Long groupId;
    private final String title;

    public TopicConcluding(Long topicId, Long groupId, String title) {
        this.topicId = topicId;
        this.groupId = groupId;
        this.title = title;
    }
}
