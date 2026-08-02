package com.dingring.infrastructure.persistence.mapper;

import com.dingring.domain.agent.Agent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Agent 表 Mapper。
 * <p>SQL 与结果映射见 resources/mapper/AgentMapper.xml
 */
@Mapper
public interface AgentMapper {

    Agent findById(Long id);

    List<Agent> findByIds(@Param("ids") List<Long> ids);

    List<Agent> findAll();

    /**
     * 查找 feature.routeJudge=true 的 Agent（feature 列是 TEXT 存 JSON 字符串，
     * 用 JSON_EXTRACT 提取布尔值）。
     */
    Agent findRouteJudge();

    int insert(Agent agent);

    int update(Agent agent);

    int deleteById(Long id);
}
