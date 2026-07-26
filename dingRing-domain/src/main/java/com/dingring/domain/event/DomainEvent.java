package com.dingring.domain.event;

import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 领域事件基类。每个事件包含事件类型、发生时间、事件数据（payload）。
 * v1.0 通过 Spring ApplicationEvent 进程内发布订阅。
 */
@Getter
public abstract class DomainEvent {

    private final LocalDateTime timestamp = LocalDateTime.now();

    /** 事件类型（默认取类名） */
    public String eventType() {
        return getClass().getSimpleName();
    }
}
