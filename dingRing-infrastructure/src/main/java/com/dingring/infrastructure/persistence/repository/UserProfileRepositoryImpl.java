package com.dingring.infrastructure.persistence.repository;

import com.dingring.domain.user.UserProfile;
import com.dingring.domain.user.UserProfileRepository;
import com.dingring.infrastructure.persistence.mapper.UserProfileMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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
    @Transactional
    public void saveNewVersion(UserProfile profile) {
        userProfileMapper.expireByUserId(profile.getUserId());
        LocalDateTime now = LocalDateTime.now();
        profile.setStatus(UserProfile.STATUS_ACTIVE);
        profile.setCreateTime(now);
        profile.setUpdateTime(now);
        userProfileMapper.insert(profile);
    }
}