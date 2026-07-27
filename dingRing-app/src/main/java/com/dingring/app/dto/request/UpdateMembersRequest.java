package com.dingring.app.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * PUT /api/groups/{id}/members 更新群成员请求。
 * <p>采用整体覆盖语义：传入的 agentIds / expertAgentId 完整替换原有 Agent 成员配置，
 * 群主 USER 成员由后端自动保留，无需前端传入。
 */
@Data
public class UpdateMembersRequest {

    /** 普通 Agent ID 列表（完整覆盖） */
    @NotEmpty(message = "至少保留一个普通成员 Agent")
    private List<Long> agentIds;

    /** 专家 Agent ID */
    @NotNull(message = "必须指定专家 Agent")
    private Long expertAgentId;
}
