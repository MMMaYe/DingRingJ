package com.dingring.infrastructure.persistence.mapper;

import com.dingring.domain.user.User;
import com.dingring.infrastructure.persistence.typehandler.JsonMapTypeHandler;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

/**
 * 用户表 Mapper（现阶段单用户，预留）。
 */
@Mapper
public interface UserMapper {

    @Select("SELECT id, name, profile_picture, feature, create_time, update_time FROM `user` WHERE id = #{id}")
    @Results({
            @Result(column = "id", property = "id", id = true),
            @Result(column = "name", property = "name"),
            @Result(column = "profile_picture", property = "profilePicture"),
            @Result(column = "feature", property = "feature", typeHandler = JsonMapTypeHandler.class),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "update_time", property = "updateTime")
    })
    User findById(Long id);

    @Insert("INSERT INTO `user` (name, profile_picture, feature, create_time, update_time) "
            + "VALUES (#{name}, #{profilePicture}, "
            + "#{feature,typeHandler=com.dingring.infrastructure.persistence.typehandler.JsonMapTypeHandler}, "
            + "#{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(User user);
}
