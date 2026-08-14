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
    /** 未配置 feature.maxTokens 时的全局默认输出上限（统一 100000） */
    private static final int DEFAULT_MAX_TOKENS = 100_000;

    public double temperature() {
        if (feature != null && feature.get("temperature") instanceof Number n) {
            return n.doubleValue();
        }
        return DEFAULT_TEMPERATURE;
    }

    public int maxTokens() {
        return maxTokens(DEFAULT_MAX_TOKENS);
    }

    /**
     * 返回输出 token 上限：Agent 显式配置优先，否则使用调用方提供的默认值。
     * <p>调用方可为普通发言配置更大的预算，同时保持路由、摘要等场景的专用预算不变。
     */
    public int maxTokens(int fallback) {
        if (feature != null && feature.get("maxTokens") instanceof Number n) {
            return n.intValue();
        }
        return fallback;
    }

    /**
     * 是否路由判定器（不参与群讨论，仅专职做 CHAT/DISCUSS/CONCLUDE 意图分类）。
     * <p>来源：feature.routeJudge 标记。Agent 管理列表/加群选择器均会过滤掉此类 Agent。
     */
    public boolean isRouteJudge() {
        return feature != null && Boolean.TRUE.equals(feature.get("routeJudge"));
    }
}
