package com.dingring.domain.skill;

import java.util.List;

/**
 * SKILL 加载服务端口（依赖倒置，infrastructure 层实现）。
 * <p>负责把 Skill 定义解析为可执行的工具能力集合，供 ReactAgent 挂载；
 * Phase G 起同时承担沉淀场景技能的加载与拼接（确定性注入，非 LLM 自主披露）。
 */
public interface SkillLoaderService {

    /**
     * 加载指定 Agent 可用的全部技能（全局 + 绑定该 Agent）。
     *
     * @param agentId Agent ID
     * @return 技能列表（已按 ACTIVE 过滤）
     */
    List<Skill> loadAgentSkills(Long agentId);

    /**
     * 加载指定沉淀场景的全部 ACTIVE 技能（按 name 排序保证拼接稳定）。
     *
     * @param scene 沉淀场景
     * @return 启用中的场景技能列表
     */
    List<Skill> loadSceneSkills(SkillScene scene);

    /**
     * 把场景技能正文确定性拼接到基础提示词之后。
     * <p>无技能/正文全空时原样返回（零行为变化，天然灰度）。
     *
     * @param scene      沉淀场景
     * @param basePrompt 基础提示词（模板渲染产物），null 按空串处理
     * @return 拼接后的完整 systemPrompt
     */
    default String applySceneSkills(SkillScene scene, String basePrompt) {
        List<Skill> skills = loadSceneSkills(scene);
        if (skills.isEmpty()) {
            return basePrompt;
        }
        StringBuilder sb = new StringBuilder(basePrompt == null ? "" : basePrompt);
        for (Skill skill : skills) {
            if (skill.getSystemPrompt() == null || skill.getSystemPrompt().isBlank()) {
                continue;
            }
            sb.append("\n\n## 附加规范（SKILL: ").append(skill.getName()).append("）\n")
              .append(skill.getSystemPrompt().trim());
        }
        return sb.toString();
    }
}
