package com.dingring.infrastructure.persistence.mapper;

import com.dingring.domain.skill.Skill;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * SKILL MyBatis Mapper（Phase F）。
 */
@Mapper
public interface SkillMapper {

    int insert(Skill skill);

    Skill findById(@Param("id") Long id);

    Skill findByName(@Param("name") String name);

    List<Skill> findAll();

    List<Skill> findByAgentId(@Param("agentId") Long agentId);

    List<Skill> findActiveGlobal();

    List<Skill> findActiveBySceneKey(@Param("sceneKey") String sceneKey);

    int update(Skill skill);

    int deleteById(@Param("id") Long id);
}
