package com.dingring.app.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Agent 信息 DTO。
 *
 * <p>apiKey 字段的可见性策略：
 * <ul>
 *   <li>列表接口（{@code GET /api/agents}）：不返回 apiKey（toDto 不填充，序列化为 null）</li>
 *   <li>详情接口（{@code GET /api/agents/{id}}）：返回 apiKey（toDetailDto 填充）
 *       —— 编辑场景需要回填，避免用户每次重新输入</li>
 * </ul>
 */
@Data
@Builder
public class AgentDTO {

    private Long id;
    private String name;
    private String profilePicture;
    private String description;
    private String baseUrl;
    private String modelName;
    /** 调用方式：API / CLI */
    private String callType;
    private String systemPrompt;
    /** temperature / maxTokens 等 */
    private Map<String, Object> feature;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    /**
     * API Key：仅详情接口返回，列表接口为 null。
     * 编辑场景需要回填，避免用户每次重新输入。
     */
    private String apiKey;
}
