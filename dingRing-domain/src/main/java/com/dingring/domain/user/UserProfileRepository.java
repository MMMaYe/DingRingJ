package com.dingring.domain.user;

import java.util.Optional;

/**
 * 用户画像仓储接口。
 */
public interface UserProfileRepository {

    /** 查询当前生效的画像（status = 1） */
    Optional<UserProfile> findByUserId(Long userId);

    /** 保存为新版本：将旧 status 置为 0，再插入 status=1 的新记录 */
    void saveNewVersion(UserProfile profile);
}
