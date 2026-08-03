package com.dingring.domain.user;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 用户画像（表 user_profile）：从闲聊中提炼的表达习惯/情绪基调/决策偏好/思考方式。
 * <p>用户维度跨群全局一份（UNIQUE user_id），注入所有群的 Agent system prompt。
 * <p>版本化写回：每次提炼后标记旧记录为失效，插入新记录，保留历史轨迹。
 */
@Data
public class UserProfile {

    /** 记录状态：有效 */
    public static final Integer STATUS_ACTIVE = 1;
    /** 记录状态：失效（历史版本） */
    public static final Integer STATUS_EXPIRED = 0;

    private Long id;
    /** 用户 ID（跨群唯一） */
    private Long userId;
    /** 画像文本（LLM 增量合并维护） */
    private String profileText;
    /** 扩展字段（JSON） */
    private Map<String, Object> feature;
    /** 记录状态：1=有效, 0=失效 */
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
