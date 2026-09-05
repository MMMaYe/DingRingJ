package com.dingring.infrastructure.skill;

import com.dingring.domain.skill.Skill;
import com.dingring.domain.skill.SkillLoaderService;
import com.dingring.domain.skill.SkillRepository;
import com.dingring.domain.skill.SkillScene;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * SKILL 加载服务实现（Phase F）。
 * <p>组合「全局技能 + 绑定指定 Agent 的技能」，仅返回 ACTIVE 状态。
 * <p>绑定技能按 scope=AGENT + agent_id 查询（管理列表接口与加载接口共用，
 * 因此状态过滤放在本服务做，避免查询语义被管理场景污染）。
 * <p>Phase G：新增沉淀场景技能加载（scope=SCENE，查询语义与 Agent 装配天然隔离）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillLoaderServiceImpl implements SkillLoaderService {

    private final SkillRepository skillRepository;

    @Override
    public List<Skill> loadAgentSkills(Long agentId) {
        List<Skill> skills = new ArrayList<>();
        skills.addAll(skillRepository.findActiveGlobal());
        if (agentId != null) {
            skills.addAll(skillRepository.findByAgentId(agentId).stream()
                    .filter(skill -> Skill.STATUS_ACTIVE.equals(skill.getStatus()))
                    .toList());
        }
        return skills;
    }

    @Override
    public List<Skill> loadSceneSkills(SkillScene scene) {
        // 实时查库、无缓存：沉淀是分钟级异步任务，每次生成查一次 skill 表的成本可忽略，
        // 换来 CRUD/启停即时生效（下一次生成即用新规范），无需失效广播（设计 §4.2）。
        // 排序由 SQL ORDER BY name 保证（见 SkillMapper.findActiveBySceneKey），Java 侧无需再排。
        return skillRepository.findActiveBySceneKey(scene.key());
    }
}
