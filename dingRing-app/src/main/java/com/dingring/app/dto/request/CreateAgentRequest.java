package com.dingring.app.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

/**
 * POST /api/agents 创建 Agent 请求。
 */
@Data
public class CreateAgentRequest {

    @NotBlank(message = "Agent 花名不能为空")
    private String name;

    private String profilePicture;

    private String description;

    @NotBlank(message = "Base URL 不能为空")
    private String baseUrl;

    @NotBlank(message = "API Key 不能为空")
    private String apiKey;

    @NotBlank(message = "模型名不能为空")
    private String modelName;

    /** 调用方式：API（直接调用 LLM API）/ CLI（调用 CLI 工具如 Claude Code），默认 API */
    private String callType;

    private String systemPrompt;

    /** 扩展配置（temperature 默认 0.7，maxTokens 默认 4096） */
    private Map<String, Object> feature;
}
