package com.dingring.infrastructure.persistence.mapper;

import com.dingring.domain.user.UserProfile;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户画像表 Mapper。
 * <p>SQL 与结果映射见 resources/mapper/UserProfileMapper.xml
 */
@Mapper
public interface UserProfileMapper {

    UserProfile findByUserId(Long userId);

    /** 按 user_id 唯一键插入或覆盖更新（ON DUPLICATE KEY UPDATE） */
    int upsert(UserProfile profile);
}
