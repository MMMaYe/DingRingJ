package com.dingring.infrastructure.persistence.mapper;

import com.dingring.domain.user.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户表 Mapper（现阶段单用户，预留）。
 * <p>SQL 与结果映射见 resources/mapper/UserMapper.xml
 */
@Mapper
public interface UserMapper {

    User findById(Long id);

    int insert(User user);
}
