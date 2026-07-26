package com.dingring.app.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

/**
 * PUT /api/agents/{id} 修改 Agent 请求（apiKey 为 null 表示不修改）。
 */
@Data
public class UpdateAgentRequest {

    @NotBlank(message = "Agent 花名不能为空")
    private String name;

    private String profilePicture;

    private String description;

    @NotBlank(message = "Base URL 不能为空")
    private String baseUrl;

    /** null 表示不修改原 Key */
    private String apiKey;

    @NotBlank(message = "模型名不能为空")
    private String modelName;

    private String systemPrompt;

    private Map<String, Object> feature;
}
