package com.dingring.infrastructure.persistence.mapper;

import com.dingring.domain.group.Group;
import com.dingring.infrastructure.persistence.typehandler.GroupMemberListTypeHandler;
import com.dingring.infrastructure.persistence.typehandler.JsonMapTypeHandler;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 群表 Mapper（低配版 DDD：直接操作领域对象）。
 */
@Mapper
public interface GroupMapper {

    String COLUMNS = "id, name, owner_id, group_member, knowledge_base_config, feature, create_time, update_time";

    @Select("SELECT " + COLUMNS + " FROM chat_group WHERE id = #{id}")
    @Results(id = "groupMap", value = {
            @Result(column = "id", property = "id", id = true),
            @Result(column = "name", property = "name"),
            @Result(column = "owner_id", property = "ownerId"),
            @Result(column = "group_member", property = "groupMember", typeHandler = GroupMemberListTypeHandler.class),
            @Result(column = "knowledge_base_config", property = "knowledgeBaseConfig", typeHandler = JsonMapTypeHandler.class),
            @Result(column = "feature", property = "feature", typeHandler = JsonMapTypeHandler.class),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "update_time", property = "updateTime")
    })
    Group findById(Long id);

    @Select("SELECT " + COLUMNS + " FROM chat_group ORDER BY update_time DESC")
    @Results(id = "groupMapAll", value = {
            @Result(column = "id", property = "id", id = true),
            @Result(column = "name", property = "name"),
            @Result(column = "owner_id", property = "ownerId"),
            @Result(column = "group_member", property = "groupMember", typeHandler = GroupMemberListTypeHandler.class),
            @Result(column = "knowledge_base_config", property = "knowledgeBaseConfig", typeHandler = JsonMapTypeHandler.class),
            @Result(column = "feature", property = "feature", typeHandler = JsonMapTypeHandler.class),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "update_time", property = "updateTime")
    })
    List<Group> findAll();

    @Insert("INSERT INTO chat_group (name, owner_id, group_member, knowledge_base_config, feature, create_time, update_time) "
            + "VALUES (#{name}, #{ownerId}, "
            + "#{groupMember,typeHandler=com.dingring.infrastructure.persistence.typehandler.GroupMemberListTypeHandler}, "
            + "#{knowledgeBaseConfig,typeHandler=com.dingring.infrastructure.persistence.typehandler.JsonMapTypeHandler}, "
            + "#{feature,typeHandler=com.dingring.infrastructure.persistence.typehandler.JsonMapTypeHandler}, "
            + "#{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Group group);

    @Update("UPDATE chat_group SET name = #{name}, owner_id = #{ownerId}, "
            + "group_member = #{groupMember,typeHandler=com.dingring.infrastructure.persistence.typehandler.GroupMemberListTypeHandler}, "
            + "knowledge_base_config = #{knowledgeBaseConfig,typeHandler=com.dingring.infrastructure.persistence.typehandler.JsonMapTypeHandler}, "
            + "feature = #{feature,typeHandler=com.dingring.infrastructure.persistence.typehandler.JsonMapTypeHandler}, "
            + "update_time = #{updateTime} WHERE id = #{id}")
    int update(Group group);

    @Delete("DELETE FROM chat_group WHERE id = #{id}")
    int deleteById(Long id);
}
