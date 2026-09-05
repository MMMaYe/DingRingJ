package com.dingring.infrastructure.persistence;

import com.dingring.domain.skill.Skill;
import com.dingring.domain.skill.SkillRepository;
import com.dingring.infrastructure.persistence.mapper.SkillMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * SKILL 仓储实现（Phase F）。
 * <p>时间戳由仓储统一维护；update/delete 返回是否命中，便于幂等判断。
 */
@Repository
@RequiredArgsConstructor
public class SkillRepositoryImpl implements SkillRepository {

    private final SkillMapper skillMapper;

    @Override
    public Skill save(Skill skill) {
        LocalDateTime now = LocalDateTime.now();
        skill.setCreateTime(now);
        skill.setUpdateTime(now);
        skillMapper.insert(skill);
        return skill;
    }

    @Override
    public Optional<Skill> findById(Long id) {
        return Optional.ofNullable(skillMapper.findById(id));
    }

    @Override
    public Optional<Skill> findByName(String name) {
        return Optional.ofNullable(skillMapper.findByName(name));
    }

    @Override
    public List<Skill> findAll() {
        return skillMapper.findAll();
    }

    @Override
    public List<Skill> findByAgentId(Long agentId) {
        return skillMapper.findByAgentId(agentId);
    }

    @Override
    public List<Skill> findActiveGlobal() {
        return skillMapper.findActiveGlobal();
    }

    @Override
    public List<Skill> findActiveBySceneKey(String sceneKey) {
        return skillMapper.findActiveBySceneKey(sceneKey);
    }

    @Override
    public boolean update(Skill skill) {
        skill.setUpdateTime(LocalDateTime.now());
        return skillMapper.update(skill) > 0;
    }

    @Override
    public boolean deleteById(Long id) {
        return skillMapper.deleteById(id) > 0;
    }
}
