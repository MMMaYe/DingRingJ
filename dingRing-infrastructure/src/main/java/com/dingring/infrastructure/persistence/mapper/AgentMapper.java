package com.dingring.infrastructure.persistence.mapper;

import com.dingring.domain.agent.Agent;
import com.dingring.infrastructure.persistence.typehandler.JsonMapTypeHandler;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * Agent 表 Mapper。
 */
@Mapper
public interface AgentMapper {

    String COLUMNS = "id, name, profile_picture, description, base_url, api_key, model_name, call_type, "
            + "system_prompt, feature, create_time, update_time";

    @Select("SELECT " + COLUMNS + " FROM agent WHERE id = #{id}")
    @Results(id = "agentMap", value = {
            @Result(column = "id", property = "id", id = true),
            @Result(column = "name", property = "name"),
            @Result(column = "profile_picture", property = "profilePicture"),
            @Result(column = "description", property = "description"),
            @Result(column = "base_url", property = "baseUrl"),
            @Result(column = "api_key", property = "apiKey"),
            @Result(column = "model_name", property = "modelName"),
            @Result(column = "call_type", property = "callType"),
            @Result(column = "system_prompt", property = "systemPrompt"),
            @Result(column = "feature", property = "feature", typeHandler = JsonMapTypeHandler.class),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "update_time", property = "updateTime")
    })
    Agent findById(Long id);

    @Select("<script>SELECT " + COLUMNS + " FROM agent WHERE id IN "
            + "<foreach collection='ids' item='item' open='(' separator=',' close=')'>#{item}</foreach>"
            + "</script>")
    @ResultMap("agentMap")
    List<Agent> findByIds(@Param("ids") List<Long> ids);

    @Select("SELECT " + COLUMNS + " FROM agent ORDER BY id ASC")
    @ResultMap("agentMap")
    List<Agent> findAll();

    @Insert("INSERT INTO agent (name, profile_picture, description, base_url, api_key, model_name, call_type, "
            + "system_prompt, feature, create_time, update_time) "
            + "VALUES (#{name}, #{profilePicture}, #{description}, #{baseUrl}, #{apiKey}, #{modelName}, #{callType}, "
            + "#{systemPrompt}, "
            + "#{feature,typeHandler=com.dingring.infrastructure.persistence.typehandler.JsonMapTypeHandler}, "
            + "#{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Agent agent);

    @Update("UPDATE agent SET name = #{name}, profile_picture = #{profilePicture}, description = #{description}, "
            + "base_url = #{baseUrl}, api_key = #{apiKey}, model_name = #{modelName}, call_type = #{callType}, "
            + "system_prompt = #{systemPrompt}, "
            + "feature = #{feature,typeHandler=com.dingring.infrastructure.persistence.typehandler.JsonMapTypeHandler}, "
            + "update_time = #{updateTime} WHERE id = #{id}")
    int update(Agent agent);

    @Delete("DELETE FROM agent WHERE id = #{id}")
    int deleteById(Long id);
}
