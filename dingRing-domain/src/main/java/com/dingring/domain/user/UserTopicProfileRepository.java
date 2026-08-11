package com.dingring.domain.user;

import java.util.List;
import java.util.Optional;

/**
 * 话题级用户画像仓储接口。
 */
public interface UserTopicProfileRepository {

    /** 保存一条话题画像记录（TopicClosed 后追加，保留进步轨迹） */
    void save(UserTopicProfile profile);

    /** 查询用户在指定话题下的画像（最近一次） */
    Optional<UserTopicProfile> findByUserIdAndTopicId(Long userId, Long topicId);

    /**
     * 按话题标题查历史（话题重启时回溯）：返回按 create_time 倒序的全部记录，
     * 首条为最近一次讨论的表现，多条记录即进步轨迹。
     */
    List<UserTopicProfile> findByUserIdAndTopicTitleOrderByCreatedAtDesc(Long userId, String topicTitle);
}
