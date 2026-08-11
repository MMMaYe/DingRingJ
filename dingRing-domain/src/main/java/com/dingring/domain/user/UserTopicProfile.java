package com.dingring.domain.user;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 话题级用户表现画像（表 user_topic_profile）：用户在特定话题下的能力表现。
 * <p>与全局画像 {@link UserProfile} 互补：全局画像描述跨话题的长期特征（表达习惯/情绪基调），
 * 话题级画像描述用户在具体话题（如"缓存方案选型"）下的理解程度与薄弱点。
 * <p>每次 TopicClosed 追加一条记录（不加唯一约束），多条记录 = 用户在该话题的进步轨迹，
 * 话题重启时按 topicTitle 回溯历史注入 Agent 上下文，让 Agent 针对性引导用户提升。
 */
@Data
public class UserTopicProfile {

    private Long id;
    /** 用户 ID */
    private Long userId;
    /** 话题 ID */
    private Long topicId;
    /** 群 ID */
    private Long groupId;
    /** 话题标题（按标题回溯历史） */
    private String topicTitle;
    /** 理解程度：BEGINNER/INTERMEDIATE/ADVANCED */
    private String understandingLevel;
    /** 薄弱点（具体到行为，非笼统评价） */
    private String weakPoints;
    /** 亮点 */
    private String strongPoints;
    /** 建议提升方向（可操作的建议） */
    private String suggestedFocus;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
