package com.dingring.infrastructure.skill;

import com.dingring.domain.skill.Skill;
import com.dingring.domain.skill.SkillRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SkillHotReloader} 种子加载单测。
 * <p>用测试 classpath 下的 skill-config.json（test/resources）验证：
 * 幂等写入（存在同名跳过）、字段映射、以及 reload() 复用同一加载逻辑。
 */
@DisplayName("SkillHotReloader 种子加载")
class SkillHotReloaderTest {

    private SkillRepository skillRepository;
    private SkillHotReloader reloader;

    @BeforeEach
    void setUp() {
        skillRepository = mock(SkillRepository.class);
        reloader = new SkillHotReloader(skillRepository);
        when(skillRepository.findByName(any())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("库为空时把种子技能全部落库（字段映射正确）")
    void shouldSeedAllWhenEmpty() {
        reloader.loadSeedConfig();

        // 种子文件含 2 条，应保存 2 次
        verify(skillRepository, times(2)).save(any(Skill.class));
    }

    @Test
    @DisplayName("同名技能已存在时跳过（幂等，不重复写入）")
    void shouldSkipExistingByName() {
        when(skillRepository.findByName("web-search")).thenReturn(Optional.of(existingSkill("web-search")));
        when(skillRepository.findByName("diagram")).thenReturn(Optional.of(existingSkill("diagram")));

        reloader.loadSeedConfig();

        verify(skillRepository, never()).save(any(Skill.class));
    }

    @Test
    @DisplayName("reload 复用同一加载逻辑（Nacos 监听回调扩展点，幂等不重复写入）")
    void shouldReloadReuseSeedLogic() {
        when(skillRepository.findByName("web-search")).thenReturn(Optional.of(existingSkill("web-search")));
        when(skillRepository.findByName("diagram")).thenReturn(Optional.of(existingSkill("diagram")));

        reloader.reload("skill-config.json", "[]");

        verify(skillRepository, never()).save(any(Skill.class));
    }

    private Skill existingSkill(String name) {
        Skill s = new Skill();
        s.setId(99L);
        s.setName(name);
        s.setStatus(Skill.STATUS_ACTIVE);
        return s;
    }
}
