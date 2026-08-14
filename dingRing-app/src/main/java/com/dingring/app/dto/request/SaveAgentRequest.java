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
 * 当前所有必填字段在两组中校验规则一致（含 apiKey），未来若场景差异可按组分别配置。
 *
 * <p>合并理由：原 CreateAgentRequest 与 UpdateAgentRequest 9 个字段中仅 apiKey 校验不同，
 * 其余完全重复。合并后消除重复定义，未来新增字段只需改一处。
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

    /** API Key：创建和修改都必填（修改时也需重新输入，不保留原 Key 语义） */
    @NotBlank(message = "API Key 不能为空", groups = {Create.class, Update.class})
    private String apiKey;

    @NotBlank(message = "模型名不能为空", groups = {Create.class, Update.class})
    private String modelName;

    /** 调用方式：API（直接调用 LLM API）/ CLI（调用 CLI 工具如 Claude Code），默认 API */
    private String callType;

    private String systemPrompt;

    /** 扩展配置（temperature 默认 0.7，maxTokens 默认 100000） */
    private Map<String, Object> feature;
}
