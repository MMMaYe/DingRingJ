package com.dingring.infrastructure.persistence.repository;

import com.dingring.domain.user.UserProfile;
import com.dingring.domain.user.UserProfileRepository;
import com.dingring.infrastructure.persistence.mapper.UserProfileMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 用户画像仓储实现。
 */
@Repository
@RequiredArgsConstructor
public class UserProfileRepositoryImpl implements UserProfileRepository {

    private final UserProfileMapper userProfileMapper;

    @Override
    public Optional<UserProfile> findByUserId(Long userId) {
        return Optional.ofNullable(userProfileMapper.findByUserId(userId));
    }

    @Override
    public void upsert(UserProfile profile) {
        LocalDateTime now = LocalDateTime.now();
        if (profile.getCreateTime() == null) {
            profile.setCreateTime(now);
        }
        profile.setUpdateTime(now);
        userProfileMapper.upsert(profile);
    }
}
