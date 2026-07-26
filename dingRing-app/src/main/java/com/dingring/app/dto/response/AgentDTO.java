package com.dingring.app.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * GET /api/agents Agent 信息（不含 apiKey）。
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
}
