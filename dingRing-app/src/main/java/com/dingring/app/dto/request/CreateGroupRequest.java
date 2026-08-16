package com.dingring.app.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * POST /api/groups 创建群请求。
 */
@Data
public class CreateGroupRequest {

    @NotBlank(message = "群名称不能为空")
    private String name;

    /** 成员 Agent ID 列表（任意 Agent 均可参与讨论与总结） */
    @NotEmpty(message = "至少选择一个 Agent")
    private List<Long> agentIds;

    /** 绑定的知识库 ID 列表（可空；存入 chat_group.knowledge_base_config.kbIds） */
    private List<Long> kbIds;
}
