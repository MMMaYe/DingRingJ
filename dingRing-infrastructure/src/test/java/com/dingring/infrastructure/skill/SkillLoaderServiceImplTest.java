package com.dingring.infrastructure.skill;

import com.dingring.domain.skill.Skill;
import com.dingring.domain.skill.SkillRepository;
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
}
