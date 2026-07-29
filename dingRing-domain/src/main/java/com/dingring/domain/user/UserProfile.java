package com.dingring.domain.user;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 用户画像（表 user_profile）：从闲聊中提炼的表达习惯/情绪基调/决策偏好/思考方式。
 * <p>用户维度跨群全局一份（UNIQUE user_id），注入所有群的 Agent system prompt。
 */
@Data
public class UserProfile {

    private Long id;
    /** 用户 ID（跨群唯一） */
    private Long userId;
    /** 画像文本（LLM 增量合并维护） */
    private String profileText;
    /** 扩展字段（JSON） */
    private Map<String, Object> feature;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
