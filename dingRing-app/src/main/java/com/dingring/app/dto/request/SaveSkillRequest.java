package com.dingring.app.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * SKILL 创建/修改统一请求（Phase F）。
 * <p>scope/status 为可选字段，缺省时由 AppService 填充默认值
 * （scope=GLOBAL，status=ACTIVE），agentId 仅 scope=AGENT 时必填。
 */
@Data
public class SaveSkillRequest {

    @NotBlank(message = "技能名称不能为空")
    private String name;

    private String description;

    /** 工具集标识（逗号分隔，如 "searchKnowledge,queryUserProfile"） */
    private String toolNames;

    /** 附加系统提示词 */
    private String systemPrompt;

    /** 作用域：GLOBAL / AGENT，缺省 GLOBAL */
    private String scope;

    /** scope=AGENT 时绑定 Agent ID */
    private Long agentId;

    /** 状态：ACTIVE / INACTIVE，缺省 ACTIVE */
    private String status;
}
