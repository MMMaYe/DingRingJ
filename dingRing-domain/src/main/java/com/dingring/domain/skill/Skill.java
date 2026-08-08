package com.dingring.domain.skill;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * SKILL（技能）实体。
 * <p>Phase F：技能 = 工具组 + 附加系统提示词，Agent 可挂载多个技能组合能力。
 * <p>与「Agent」的关联：技能可全局生效（scope=GLOBAL）或绑定特定 Agent（scope=AGENT）。
 */
@Data
public class Skill {

    /** 作用域：全局（所有 Agent 可用） */
    public static final String SCOPE_GLOBAL = "GLOBAL";
    /** 作用域：绑定 Agent（仅指定 Agent 可用） */
    public static final String SCOPE_AGENT = "AGENT";

    /** 状态：启用 */
    public static final String STATUS_ACTIVE = "ACTIVE";
    /** 状态：停用 */
    public static final String STATUS_INACTIVE = "INACTIVE";

    private Long id;
    /** 技能名称（唯一标识，如 "rag-search"） */
    private String name;
    /** 技能描述（用于提示词注入） */
    private String description;
    /** 工具集标识（逗号分隔，如 "knowledge_search,user_profile"） */
    private String toolNames;
    /** 附加系统提示词（挂载到 Agent 的 systemPrompt 之后） */
    private String systemPrompt;
    /** 作用域：GLOBAL / AGENT */
    private String scope;
    /** scope=AGENT 时绑定 Agent ID */
    private Long agentId;
    /** 状态：ACTIVE / INACTIVE */
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 解析工具集为列表 */
    public List<String> toolNameList() {
        if (toolNames == null || toolNames.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(toolNames.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
