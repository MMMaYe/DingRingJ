package com.dingring.infrastructure.persistence.mapper;

import com.dingring.domain.discussion.Topic;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 主题表 Mapper。
 */
@Mapper
public interface TopicMapper {

    String COLUMNS = "id, chat_group_id, title, status, conclusion, closed_at, closed_by, version, create_time, update_time";

    @Select("SELECT " + COLUMNS + " FROM topic WHERE id = #{id}")
    @Results(id = "topicMap", value = {
            @Result(column = "id", property = "id", id = true),
            @Result(column = "chat_group_id", property = "chatGroupId"),
            @Result(column = "title", property = "title"),
            @Result(column = "status", property = "status"),
            @Result(column = "conclusion", property = "conclusion"),
            @Result(column = "closed_at", property = "closedAt"),
            @Result(column = "closed_by", property = "closedBy"),
            @Result(column = "version", property = "version"),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "update_time", property = "updateTime")
    })
    Topic findById(Long id);

    @Select("SELECT " + COLUMNS + " FROM topic WHERE chat_group_id = #{groupId} AND status = 'IN_PROGRESS' LIMIT 1")
    @ResultMap("topicMap")
    Topic findActiveByGroupId(Long groupId);

    @Select("SELECT " + COLUMNS + " FROM topic WHERE chat_group_id = #{groupId} ORDER BY create_time DESC, id DESC")
    @ResultMap("topicMap")
    List<Topic> findByGroupId(Long groupId);

    @Select("SELECT " + COLUMNS + " FROM topic WHERE chat_group_id = #{groupId} AND status = 'CLOSED' "
            + "ORDER BY closed_at ASC, id ASC")
    @ResultMap("topicMap")
    List<Topic> findClosedByGroupId(Long groupId);

    @Insert("INSERT INTO topic (chat_group_id, title, status, conclusion, closed_at, closed_by, version, create_time, update_time) "
            + "VALUES (#{chatGroupId}, #{title}, #{status}, #{conclusion}, #{closedAt}, #{closedBy}, #{version}, "
            + "#{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Topic topic);

    /** 乐观锁更新：version 匹配才生效，成功后 version + 1 */
    @Update("UPDATE topic SET title = #{title}, status = #{status}, conclusion = #{conclusion}, "
            + "closed_at = #{closedAt}, closed_by = #{closedBy}, version = version + 1, update_time = #{updateTime} "
            + "WHERE id = #{id} AND version = #{version}")
    int updateWithVersion(Topic topic);
}
