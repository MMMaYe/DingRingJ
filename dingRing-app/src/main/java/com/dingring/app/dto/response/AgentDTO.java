package com.dingring.app.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Agent 信息 DTO。
 * 敏感凭据永不通过列表或详情接口返回。
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
