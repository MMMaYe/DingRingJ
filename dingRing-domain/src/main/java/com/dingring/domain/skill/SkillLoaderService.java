package com.dingring.domain.skill;

import java.util.List;

/**
 * SKILL 加载服务端口（依赖倒置，infrastructure 层实现）。
 * <p>负责把 Skill 定义解析为可执行的工具能力集合，供 ReactAgent 挂载。
 */
public interface SkillLoaderService {

    /**
     * 加载指定 Agent 可用的全部技能（全局 + 绑定该 Agent）。
     *
     * @param agentId Agent ID
     * @return 技能列表（已按 ACTIVE 过滤）
     */
    List<Skill> loadAgentSkills(Long agentId);
}
