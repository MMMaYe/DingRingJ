package com.dingring.infrastructure.persistence.repository;

import com.dingring.domain.discussion.Topic;
import com.dingring.domain.discussion.TopicRepository;
import com.dingring.infrastructure.persistence.mapper.TopicMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 主题仓储实现（乐观锁更新）。
 */
@Repository
@RequiredArgsConstructor
public class TopicRepositoryImpl implements TopicRepository {

    private final TopicMapper topicMapper;

    @Override
    public Optional<Topic> findById(Long id) {
        return Optional.ofNullable(topicMapper.findById(id));
    }

    @Override
    public Optional<Topic> findActiveByGroupId(Long groupId) {
        return Optional.ofNullable(topicMapper.findActiveByGroupId(groupId));
    }

    @Override
    public List<Topic> findByGroupId(Long groupId) {
        return topicMapper.findByGroupId(groupId);
    }

    @Override
    public List<Topic> findClosedByGroupId(Long groupId) {
        return topicMapper.findClosedByGroupId(groupId);
    }

    @Override
    public List<Topic> findConcludingBefore(LocalDateTime threshold) {
        return topicMapper.findConcludingBefore(threshold);
    }

    @Override
    public List<Topic> findClosedSince(LocalDateTime since) {
        return topicMapper.findClosedSince(since);
    }

    @Override
    public List<Topic> findAllClosed() {
        return topicMapper.findAllClosed();
    }

    @Override
    public Long save(Topic topic) {
        LocalDateTime now = LocalDateTime.now();
        topic.setCreateTime(now);
        topic.setUpdateTime(now);
        if (topic.getVersion() == null) {
            topic.setVersion(0);
        }
        topicMapper.insert(topic);
        return topic.getId();
    }

    @Override
    public boolean update(Topic topic) {
        topic.setUpdateTime(LocalDateTime.now());
        boolean success = topicMapper.updateWithVersion(topic) > 0;
        if (success) {
            topic.setVersion(topic.getVersion() + 1);
        }
        return success;
    }
}
