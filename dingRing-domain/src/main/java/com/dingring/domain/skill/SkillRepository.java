package com.dingring.domain.skill;

import java.util.List;
import java.util.Optional;

/**
 * SKILL 仓储端口（依赖倒置，infrastructure 层用 MyBatis 实现）。
 */
public interface SkillRepository {

    Skill save(Skill skill);

    Optional<Skill> findById(Long id);

    Optional<Skill> findByName(String name);

    List<Skill> findAll();

    List<Skill> findByAgentId(Long agentId);

    List<Skill> findActiveGlobal();

    boolean update(Skill skill);

    boolean deleteById(Long id);
}
