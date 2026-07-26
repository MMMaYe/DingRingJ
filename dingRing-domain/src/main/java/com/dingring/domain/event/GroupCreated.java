package com.dingring.domain.event;

import lombok.Getter;

/**
 * 群创建成功事件（订阅方：日志记录）。
 */
@Getter
public class GroupCreated extends DomainEvent {

    private final Long groupId;
    private final String groupName;

    public GroupCreated(Long groupId, String groupName) {
        this.groupId = groupId;
        this.groupName = groupName;
    }
}
