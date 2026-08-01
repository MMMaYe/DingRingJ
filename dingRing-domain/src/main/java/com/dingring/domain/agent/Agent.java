package com.dingring.domain.agent;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Agent 聚合根（表 agent）。配置花名、头像、LLM 端点、人设。
 */
@Data
public class Agent {

    private Long id;
    /** Agent 花名 */
    private String name;
    /** 头像 URL */
    private String profilePicture;
    /** 性格描述 */
    private String description;
    /** LLM API 端点 */
    private String baseUrl;
    /** API 密钥 */
    private String apiKey;
    /** 模型名（如 deepseek-chat, claude-3-sonnet） */
    private String modelName;
    /** 调用方式：API（直接调用 LLM API）/ CLI（调用 CLI 工具如 Claude Code） */
    private String callType;
    /** 系统提示词 / 人设 */
    private String systemPrompt;
    /** 扩展字段（temperature, maxTokens 等） */
    private Map<String, Object> feature;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    private static final double DEFAULT_TEMPERATURE = 1.0;
    private static final int DEFAULT_MAX_TOKENS = 4096;

    public double temperature() {
        if (feature != null && feature.get("temperature") instanceof Number n) {
            return n.doubleValue();
        }
        return DEFAULT_TEMPERATURE;
    }

    public int maxTokens() {
        if (feature != null && feature.get("maxTokens") instanceof Number n) {
            return n.intValue();
        }
        return DEFAULT_MAX_TOKENS;
    }
}
