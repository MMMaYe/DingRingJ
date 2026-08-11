package com.dingring.infrastructure.persistence.repository;

import com.dingring.domain.user.UserTopicProfile;
import com.dingring.domain.user.UserTopicProfileRepository;
import com.dingring.infrastructure.persistence.mapper.UserTopicProfileMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 话题级用户画像仓储实现（追加写，保留进步轨迹）。
 */
@Repository
@RequiredArgsConstructor
public class UserTopicProfileRepositoryImpl implements UserTopicProfileRepository {

    private final UserTopicProfileMapper userTopicProfileMapper;

    @Override
    public void save(UserTopicProfile profile) {
        LocalDateTime now = LocalDateTime.now();
        profile.setCreateTime(now);
        profile.setUpdateTime(now);
        userTopicProfileMapper.insert(profile);
    }

    @Override
    public Optional<UserTopicProfile> findByUserIdAndTopicId(Long userId, Long topicId) {
        return Optional.ofNullable(userTopicProfileMapper.findByUserIdAndTopicId(userId, topicId));
    }

    @Override
    public List<UserTopicProfile> findByUserIdAndTopicTitleOrderByCreatedAtDesc(Long userId, String topicTitle) {
        return userTopicProfileMapper.findByUserIdAndTopicTitle(userId, topicTitle);
    }
}
