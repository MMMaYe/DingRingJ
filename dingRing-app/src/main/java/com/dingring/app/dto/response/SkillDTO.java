package com.dingring.app.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * SKILL 信息 DTO（Phase F）。
 * <p>toolNameList 由 toolNames 逗号分割解析，便于前端直接渲染工具清单。
 */
@Data
@Builder
public class SkillDTO {

    private Long id;
    private String name;
    private String description;
    /** 工具集标识（逗号分隔的原始串） */
    private String toolNames;
    /** 解析后的工具名列表 */
    private List<String> toolNameList;
    private String systemPrompt;
    private String scope;
    private Long agentId;
    /** scope=SCENE 时绑定的沉淀场景键（conclude/card/topic-profile） */
    private String sceneKey;
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
