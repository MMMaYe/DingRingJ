package com.dingring.domain.group;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

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

    /** 成员 Agent ID 列表（所有 Agent 地位平等，均可参与讨论与总结） */
    public List<Long> memberAgentIds() {
        if (groupMember == null) {
            return List.of();
        }
        return groupMember.stream()
                .filter(GroupMember::isAgent)
                .map(GroupMember::getId)
                .toList();
    }
}
