package com.dingring.app.dto.test;

import com.dingring.domain.service.LlmService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * LLM 调试接口请求（/chat 与 /chat-stream 共用）。
 * 直接把 Agent 的 baseUrl/apiKey/modelName 放在请求体里，不依赖 DB 里已有的 Agent 记录，
 * 这样 Postman 里用生产参数复制粘贴即可发起真实调用，不用额外改 DB。
 */
@Data
public class LlmDebugChatRequest {

    /** Agent 花名（仅用于日志和响应回显，允许留空用默认值） */
    private String agentName;

    /** LLM 端点，例如 https://api.siliconflow.cn/v1 */
    @NotBlank(message = "baseUrl 不能为空")
    private String baseUrl;

    @NotBlank(message = "apiKey 不能为空")
    private String apiKey;

    @NotBlank(message = "modelName 不能为空")
    private String modelName;

    /** Agent 默认采样温度（可被 override.temperature 覆盖） */
    private Double temperature;

    /** Agent 默认 maxTokens（可被 override.maxTokens 覆盖） */
    private Integer maxTokens;

    /** 系统提示词（对应 chat 方法第二个参数） */
    private String systemPrompt;

    /** 对话消息（时间升序，role=USER/ASSISTANT） */
    @NotEmpty(message = "messages 不能为空")
    @Valid
    private List<Turn> messages;

    /**
     * 单次调用参数覆盖（对应 LlmService.CallOptions）。
     * 不传则完全沿用上面的 temperature/maxTokens + 全局默认读超时。
     */
    private OverrideOptions override;

    @Data
    public static class Turn {
        @NotBlank(message = "role 不能为空（USER / ASSISTANT）")
        private String role;
        @NotBlank(message = "content 不能为空")
        private String content;

        public LlmService.ChatTurn toChatTurn() {
            if ("ASSISTANT".equalsIgnoreCase(role)) {
                return LlmService.ChatTurn.assistant(content);
            }
            return LlmService.ChatTurn.user(content);
        }
    }

    @Data
    public static class OverrideOptions {
        /** 覆盖 temperature（分类任务建议 0） */
        private Double temperature;
        /** 覆盖 maxTokens（分类输出 JSON 建议限小） */
        private Integer maxTokens;
        /** 覆盖读超时秒数（短任务建议 15） */
        private Long readTimeoutSeconds;

        public LlmService.CallOptions toCallOptions() {
            return new LlmService.CallOptions(temperature, maxTokens, readTimeoutSeconds);
        }
    }
}
