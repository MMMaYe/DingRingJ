package com.dingring.domain.group;

import java.util.List;
import java.util.Optional;

/**
 * 群消息仓储接口。
 */
public interface MessageRepository {

    Optional<GroupMessage> findById(Long id);

    List<GroupMessage> findByIds(List<Long> ids);

    Long save(GroupMessage message);

    /** 按主题分页查询（时间升序） */
    List<GroupMessage> findByTopicId(Long topicId, int offset, int limit);

    long countByTopicId(Long topicId);

    /** 按群分页查询（时间升序） */
    List<GroupMessage> findByGroupId(Long groupId, int offset, int limit);

    long countByGroupId(Long groupId);

    /** 滑动窗口：主题内最近 N 条（返回时间升序） */
    List<GroupMessage> findRecentByTopicId(Long topicId, int limit);

    /** 滑动窗口：群内最近 N 条（返回时间升序，闲聊场景） */
    List<GroupMessage> findRecentByGroupId(Long groupId, int limit);

    /** 主题内某发送者的发言次数（轮次均衡用） */
    long countByTopicIdAndSender(Long topicId, Long senderId, SenderType senderType);

    /** 主题内某类发送者的发言总数（终止判定用，如 AGENT 总发言数） */
    long countByTopicIdAndSenderType(Long topicId, SenderType senderType);

    /** 群最后一条消息（列表预览用） */
    Optional<GroupMessage> findLastByGroupId(Long groupId);

    /** 群内最近 N 条未归属主题的闲聊消息（返回时间升序，追溯建题/闲聊上下文用） */
    List<GroupMessage> findRecentChatByGroupId(Long groupId, int limit);

    /**
     * 批量回填消息的 topic_id（追溯建题用）。
     *
     * @return 实际更新条数
     */
    int updateTopicId(List<Long> messageIds, Long topicId);
}
