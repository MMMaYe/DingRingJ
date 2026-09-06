package com.dingring.app.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

/**
 * Agent 创建/修改统一请求（合并自 CreateAgentRequest + UpdateAgentRequest）。
 *
 * <p>使用 Bean Validation Groups 区分场景：
 * <ul>
 *   <li>{@link Create} —— 创建场景，Controller 用 @Validated(Create.class) 触发</li>
 *   <li>{@link Update} —— 修改场景，Controller 用 @Validated(Update.class) 触发</li>
 * </ul>
 * 创建时必须提供 API Key；修改时留空表示保留现有密钥。
 */
@Data
public class SaveAgentRequest {

    /** 创建场景校验组 */
    public interface Create {}

    /** 修改场景校验组 */
    public interface Update {}

    @NotBlank(message = "Agent 花名不能为空", groups = {Create.class, Update.class})
    private String name;

    private String profilePicture;

    private String description;

    @NotBlank(message = "Base URL 不能为空", groups = {Create.class, Update.class})
    private String baseUrl;

    /** API Key：创建必填；修改留空表示保留现有值。 */
    @NotBlank(message = "API Key 不能为空", groups = Create.class)
    private String apiKey;

    @NotBlank(message = "模型名不能为空", groups = {Create.class, Update.class})
    private String modelName;

    /** 调用方式：API（直接调用 LLM API）/ CLI（调用 CLI 工具如 Claude Code），默认 API */
    private String callType;

    private String systemPrompt;

    /** 扩展配置（temperature 默认 0.7，maxTokens 默认 100000） */
    private Map<String, Object> feature;
}
