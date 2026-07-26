package com.dingring.domain.event;

import lombok.Getter;

/**
 * 新主题创建事件（订阅方：前端更新主题面板）。
 */
@Getter
public class TopicCreated extends DomainEvent {

    private final Long topicId;
    private final Long groupId;
    private final String title;

    public TopicCreated(Long topicId, Long groupId, String title) {
        this.topicId = topicId;
        this.groupId = groupId;
        this.title = title;
    }
}
