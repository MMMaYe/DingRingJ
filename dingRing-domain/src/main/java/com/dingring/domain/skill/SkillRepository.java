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

    /**
     * 查询指定沉淀场景的全部启用技能。
     * <p>运行时 loader 查询（scope='SCENE' AND scene_key=? AND status='ACTIVE'，
     * 按 name 排序保证拼接稳定——多技能叠加的输出顺序确定性依赖于此）。
     *
     * @param sceneKey 场景键（{@link SkillScene#key()}）
     * @return 启用中的场景技能列表（按 name 排序）
     */
    List<Skill> findActiveBySceneKey(String sceneKey);

    boolean update(Skill skill);

    boolean deleteById(Long id);
}
