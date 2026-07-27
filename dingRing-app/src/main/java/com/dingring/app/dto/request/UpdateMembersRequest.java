package com.dingring.app.dto.request;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * PUT /api/groups/{id}/members 更新群成员请求。
 * <p>采用整体覆盖语义：传入的 agentIds 完整替换原有 Agent 成员配置，
 * 群主 USER 成员由后端自动保留，无需前端传入。
 */
@Data
public class UpdateMembersRequest {

    /** 成员 Agent ID 列表（完整覆盖，任意 Agent 均可参与讨论与总结） */
    @NotEmpty(message = "至少保留一个成员 Agent")
    private List<Long> agentIds;
}
