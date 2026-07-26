package com.dingring.app.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * POST /api/groups 创建群请求。
 */
@Data
public class CreateGroupRequest {

    @NotBlank(message = "群名称不能为空")
    private String name;

    /** 普通 Agent ID 列表 */
    @NotEmpty(message = "至少选择一个 Agent")
    private List<Long> agentIds;

    /** 专家 Agent ID */
    @NotNull(message = "必须指定专家 Agent")
    private Long expertAgentId;
}
