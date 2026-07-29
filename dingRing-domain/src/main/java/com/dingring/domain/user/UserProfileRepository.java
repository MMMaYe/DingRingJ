package com.dingring.domain.user;

import java.util.Optional;

/**
 * 用户画像仓储接口。
 */
public interface UserProfileRepository {

    Optional<UserProfile> findByUserId(Long userId);

    /** 按 user_id 唯一键插入或覆盖更新画像文本 */
    void upsert(UserProfile profile);
}
