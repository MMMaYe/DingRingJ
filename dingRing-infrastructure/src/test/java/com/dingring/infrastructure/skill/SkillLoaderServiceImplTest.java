package com.dingring.infrastructure.skill;

import com.dingring.domain.skill.Skill;
import com.dingring.domain.skill.SkillRepository;
import com.dingring.domain.skill.SkillScene;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SkillLoaderServiceImpl} 技能加载单测。
 * <p>验证：加载结果 = 全部启用全局技能 + 该 Agent 绑定的启用技能；停用技能被过滤。
 * <p>Phase G：场景技能加载委托仓储（传 scene.key()）；default 拼接方法
 * 的空技能/空正文/多技能顺序/null 兜底行为。
 */
@DisplayName("SkillLoaderServiceImpl 技能加载")
class SkillLoaderServiceImplTest {

    private SkillRepository skillRepository;
    private SkillLoaderServiceImpl loader;

    @BeforeEach
    void setUp() {
        skillRepository = mock(SkillRepository.class);
        loader = new SkillLoaderServiceImpl(skillRepository);
    }

    private Skill skill(Long id, String name, String scope, Long agentId, String status) {
        Skill s = new Skill();
        s.setId(id);
        s.setName(name);
        s.setScope(scope);
        s.setAgentId(agentId);
        s.setStatus(status);
        return s;
    }

    private Skill sceneSkill(String name, String sceneKey, String systemPrompt) {
        Skill s = skill(null, name, Skill.SCOPE_SCENE, null, Skill.STATUS_ACTIVE);
        s.setSceneKey(sceneKey);
        s.setSystemPrompt(systemPrompt);
        return s;
    }

    @Test
    @DisplayName("加载全局启用技能 + 绑定 Agent 的启用技能")
    void shouldCombineGlobalAndAgentSkills() {
        when(skillRepository.findActiveGlobal()).thenReturn(List.of(
                skill(1L, "rag-search", Skill.SCOPE_GLOBAL, null, Skill.STATUS_ACTIVE)));
        when(skillRepository.findByAgentId(2L)).thenReturn(List.of(
                skill(3L, "agent-special", Skill.SCOPE_AGENT, 2L, Skill.STATUS_ACTIVE)));

        List<Skill> result = loader.loadAgentSkills(2L);

        assertThat(result).extracting(Skill::getName)
                .containsExactly("rag-search", "agent-special");
    }

    @Test
    @DisplayName("绑定技能含停用时被过滤（管理列表接口与加载接口共用查询）")
    void shouldFilterInactiveBoundSkill() {
        when(skillRepository.findActiveGlobal()).thenReturn(List.of());
        when(skillRepository.findByAgentId(2L)).thenReturn(List.of(
                skill(3L, "active-skill", Skill.SCOPE_AGENT, 2L, Skill.STATUS_ACTIVE),
                skill(4L, "inactive-skill", Skill.SCOPE_AGENT, 2L, Skill.STATUS_INACTIVE)));

        List<Skill> result = loader.loadAgentSkills(2L);

        assertThat(result).extracting(Skill::getName).containsExactly("active-skill");
    }

    @Test
    @DisplayName("agentId 为 null 时仅加载全局技能（不查绑定）")
    void shouldOnlyLoadGlobalWhenAgentIdNull() {
        when(skillRepository.findActiveGlobal()).thenReturn(List.of(
                skill(1L, "rag-search", Skill.SCOPE_GLOBAL, null, Skill.STATUS_ACTIVE)));

        List<Skill> result = loader.loadAgentSkills(null);

        assertThat(result).extracting(Skill::getName).containsExactly("rag-search");
        verify(skillRepository).findActiveGlobal();
    }

    @Test
    @DisplayName("场景技能加载委托仓储并以 scene.key() 为查询键")
    void shouldDelegateSceneSkillsWithSceneKey() {
        when(skillRepository.findActiveBySceneKey("card")).thenReturn(List.of(
                sceneSkill("card-review-ready", "card", "规范正文")));

        List<Skill> result = loader.loadSceneSkills(SkillScene.CARD);

        assertThat(result).extracting(Skill::getName).containsExactly("card-review-ready");
        verify(skillRepository).findActiveBySceneKey("card");
    }

    @Test
    @DisplayName("场景无技能时 applySceneSkills 原样返回基础提示词（零行为变化）")
    void shouldReturnBasePromptWhenNoSceneSkills() {
        when(skillRepository.findActiveBySceneKey(SkillScene.CONCLUDE.key()))
                .thenReturn(List.of());

        assertThat(loader.applySceneSkills(SkillScene.CONCLUDE, "基础提示词"))
                .isEqualTo("基础提示词");
    }

    @Test
    @DisplayName("systemPrompt 为 null/空白的技能被跳过，不产生分节")
    void shouldSkipBlankPromptSkills() {
        when(skillRepository.findActiveBySceneKey(SkillScene.TOPIC_PROFILE.key()))
                .thenReturn(List.of(
                        sceneSkill("blank-skill", "topic-profile", "  "),
                        sceneSkill("null-skill", "topic-profile", null),
                        sceneSkill("profile-comparable", "topic-profile", "有效规范")));

        String result = loader.applySceneSkills(SkillScene.TOPIC_PROFILE, "基础提示词");

        assertThat(result).contains("## 附加规范（SKILL: profile-comparable）");
        assertThat(result).doesNotContain("blank-skill").doesNotContain("null-skill");
        assertThat(result).isEqualTo("基础提示词\n\n## 附加规范（SKILL: profile-comparable）\n有效规范");
    }

    @Test
    @DisplayName("多技能按仓储返回顺序拼接，各含分节头")
    void shouldConcatenateMultipleSkillsInOrder() {
        when(skillRepository.findActiveBySceneKey(SkillScene.CONCLUDE.key()))
                .thenReturn(List.of(
                        sceneSkill("conclude-article", "conclude", "文章体骨架"),
                        sceneSkill("conclude-strict", "conclude", "蒸馏纪律")));

        String result = loader.applySceneSkills(SkillScene.CONCLUDE, "基础提示词");

        assertThat(result).isEqualTo("基础提示词"
                + "\n\n## 附加规范（SKILL: conclude-article）\n文章体骨架"
                + "\n\n## 附加规范（SKILL: conclude-strict）\n蒸馏纪律");
    }

    @Test
    @DisplayName("basePrompt 为 null 时不抛异常，按空串处理")
    void shouldTreatNullBasePromptAsEmpty() {
        when(skillRepository.findActiveBySceneKey(SkillScene.CARD.key()))
                .thenReturn(List.of(sceneSkill("card-review-ready", "card", "规范正文")));

        String result = loader.applySceneSkills(SkillScene.CARD, null);

        assertThat(result).isEqualTo("\n\n## 附加规范（SKILL: card-review-ready）\n规范正文");
    }
}
