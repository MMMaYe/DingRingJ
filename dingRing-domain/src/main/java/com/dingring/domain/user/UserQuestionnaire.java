package com.dingring.domain.user;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 用户问卷答案（表 user_questionnaire）：画像主数据的事实源。
 * <p>版本化写回：重填时标记旧记录失效、插入新记录（version+1），保留填写轨迹；
 * 消费视图（画像文本）由 QuestionnaireProfileAssembler 从当前有效版本拼接。
 */
@Data
public class UserQuestionnaire {

    /** 记录状态：有效 */
    public static final Integer STATUS_ACTIVE = 1;
    /** 记录状态：失效（历史版本） */
    public static final Integer STATUS_EXPIRED = 0;

    private Long id;
    /** 用户 ID */
    private Long userId;
    /** 答案（题目 key → 枚举编码；TEXT 题为原文，与文案解耦） */
    private Map<String, Object> answers;
    /** 填写版本号（每次重填 +1） */
    private Integer version;
    /** 记录状态：1=有效, 0=失效 */
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
