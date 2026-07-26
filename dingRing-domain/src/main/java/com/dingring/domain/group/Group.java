package com.dingring.domain.group;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 群聚合根（表 chat_group）。
 */
@Data
public class Group {

    private Long id;
    /** 群名称 */
    private String name;
    /** 群主用户 ID */
    private Long ownerId;
    /** 成员列表（JSON 存储，含 USER 与 AGENT） */
    private List<GroupMember> groupMember;
    /** 知识库配置（v1.0 预留） */
    private Map<String, Object> knowledgeBaseConfig;
    /** 扩展字段 */
    private Map<String, Object> feature;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 普通成员 Agent ID 列表（不含专家） */
    public List<Long> memberAgentIds() {
        if (groupMember == null) {
            return List.of();
        }
        return groupMember.stream()
                .filter(m -> m.isAgent() && !m.isExpert())
                .map(GroupMember::getId)
                .toList();
    }

    /** 专家 Agent ID */
    public Optional<Long> expertAgentId() {
        if (groupMember == null) {
            return Optional.empty();
        }
        return groupMember.stream()
                .filter(m -> m.isAgent() && m.isExpert())
                .map(GroupMember::getId)
                .findFirst();
    }
}
