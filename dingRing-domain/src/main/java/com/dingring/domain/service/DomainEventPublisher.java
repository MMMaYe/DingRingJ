package com.dingring.domain.service;

import com.dingring.domain.event.DomainEvent;

/**
 * 领域事件发布接口（依赖倒置，infrastructure 层用 Spring ApplicationEvent 实现）。
 */
public interface DomainEventPublisher {

    void publish(DomainEvent event);
}
