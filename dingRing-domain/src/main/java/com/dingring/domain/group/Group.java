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

    /** knowledge_base_config 中存放绑定知识库 ID 列表的 key（写入与读取单一来源） */
    public static final String KB_IDS_KEY = "kbIds";

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
    /** 逻辑删除标记（0 正常 / 1 已删） */
    private Integer deleted;
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

    /**
     * 群绑定的知识库 ID 列表（RAG 检索只注入绑定库）。
     * <p>JSON 反序列化数字为 Integer，必须经 Number.longValue() 归一为 Long，
     * 否则与前端回显及过滤表达式比较会出现类型不一致。
     */
    public List<Long> boundKbIds() {
        Object raw = knowledgeBaseConfig == null ? null : knowledgeBaseConfig.get(KB_IDS_KEY);
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Number.class::isInstance)
                .map(v -> ((Number) v).longValue())
                .toList();
    }
}
