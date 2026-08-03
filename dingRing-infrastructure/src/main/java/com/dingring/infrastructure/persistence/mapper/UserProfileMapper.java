package com.dingring.infrastructure.persistence.mapper;

import com.dingring.domain.user.UserProfile;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户画像表 Mapper。
 * <p>SQL 与结果映射见 resources/mapper/UserProfileMapper.xml
 */
@Mapper
public interface UserProfileMapper {

    /** 查询当前生效的画像（status = 1） */
    UserProfile findByUserId(Long userId);

    /** 将指定用户当前生效的画像置为失效（status = 0） */
    int expireByUserId(Long userId);

    /** 插入新记录 */
    int insert(UserProfile profile);
}
