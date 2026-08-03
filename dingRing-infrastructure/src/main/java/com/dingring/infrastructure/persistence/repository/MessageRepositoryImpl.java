package com.dingring.infrastructure.persistence.repository;

import com.dingring.domain.group.GroupMessage;
import com.dingring.domain.group.MessageRepository;
import com.dingring.domain.group.SenderType;
import com.dingring.infrastructure.persistence.mapper.MessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 群消息仓储实现。
 */
@Repository
@RequiredArgsConstructor
public class MessageRepositoryImpl implements MessageRepository {

    private final MessageMapper messageMapper;

    @Override
    public Optional<GroupMessage> findById(Long id) {
        return Optional.ofNullable(messageMapper.findById(id));
    }

    @Override
    public List<GroupMessage> findByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return messageMapper.findByIds(ids);
    }

    @Override
    public Long save(GroupMessage message) {
        LocalDateTime now = LocalDateTime.now();
        message.setCreateTime(now);
        message.setUpdateTime(now);
        messageMapper.insert(message);
        return message.getId();
    }

    @Override
    public List<GroupMessage> findByTopicId(Long topicId, int offset, int limit) {
        return messageMapper.findByTopicId(topicId, offset, limit);
    }

    @Override
    public long countByTopicId(Long topicId) {
        return messageMapper.countByTopicId(topicId);
    }

    @Override
    public List<GroupMessage> findByGroupId(Long groupId, int offset, int limit) {
        return messageMapper.findByGroupId(groupId, offset, limit);
    }

    @Override
    public long countByGroupId(Long groupId) {
        return messageMapper.countByGroupId(groupId);
    }

    @Override
    public List<GroupMessage> findRecentByTopicId(Long topicId, int limit) {
        return messageMapper.findRecentByTopicId(topicId, limit);
    }

    @Override
    public List<GroupMessage> findRecentByGroupId(Long groupId, int limit) {
        return messageMapper.findRecentByGroupId(groupId, limit);
    }

    @Override
    public long countByTopicIdAndSender(Long topicId, Long senderId, SenderType senderType) {
        return messageMapper.countByTopicIdAndSender(topicId, senderId, senderType.name());
    }

    @Override
    public long countByTopicIdAndSenderType(Long topicId, SenderType senderType) {
        return messageMapper.countByTopicIdAndSenderType(topicId, senderType.name());
    }

    @Override
    public Optional<GroupMessage> findLastByGroupId(Long groupId) {
        return Optional.ofNullable(messageMapper.findLastByGroupId(groupId));
    }

    @Override
    public List<GroupMessage> findRecentChatByGroupId(Long groupId, int limit) {
        return messageMapper.findRecentChatByGroupId(groupId, limit);
    }

    @Override
    public int updateTopicId(List<Long> messageIds, Long topicId) {
        if (messageIds == null || messageIds.isEmpty()) {
            return 0;
        }
        return messageMapper.updateTopicId(messageIds, topicId);
    }
}
