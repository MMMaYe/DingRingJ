package com.dingring.infrastructure.skill;

import com.dingring.domain.skill.Skill;
import com.dingring.infrastructure.agent.tool.WebTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link SkillToolkitFactory} 工具解析单测。
 * <p>验证：init() 按 @Tool 方法名注册工具；resolveTools 按 Skill 声明的名称精确匹配，
 * 未知工具名 WARN 跳过不阻断装配。
 */
@DisplayName("SkillToolkitFactory 工具集解析")
class SkillToolkitFactoryTest {

    private SkillToolkitFactory factory;

    @BeforeEach
    void setUp() {
        factory = new SkillToolkitFactory(new WebTools(""));
        factory.init();
    }

    private Skill skillWithTools(String... toolNames) {
        Skill s = new Skill();
        s.setName("test-skill");
        s.setToolNames(String.join(",", toolNames));
        return s;
    }

    @Test
    @DisplayName("init 后注册表包含两个 @Tool 方法名")
    void shouldRegisterAllTools() {
        Skill s = skillWithTools("webSearch", "webFetch");

        ToolCallback[] callbacks = factory.resolveTools(s);

        assertThat(callbacks).extracting(cb -> cb.getToolDefinition().name())
                .containsExactlyInAnyOrder("webSearch", "webFetch");
    }

    @Test
    @DisplayName("按声明顺序返回工具（LinkedHashMap 保持注册顺序）")
    void shouldResolveInDeclaredOrder() {
        Skill s = skillWithTools("webFetch", "webSearch");

        ToolCallback[] callbacks = factory.resolveTools(s);

        assertThat(callbacks).extracting(cb -> cb.getToolDefinition().name())
                .containsExactly("webFetch", "webSearch");
    }

    @Test
    @DisplayName("未知工具名跳过，已注册工具正常解析")
    void shouldSkipUnknownTool() {
        Skill s = skillWithTools("webSearch", "no-such-tool");

        ToolCallback[] callbacks = factory.resolveTools(s);

        assertThat(callbacks).extracting(cb -> cb.getToolDefinition().name())
                .containsExactly("webSearch");
    }

    @Test
    @DisplayName("空工具声明返回空数组")
    void shouldReturnEmptyForNoTools() {
        Skill s = skillWithTools();

        ToolCallback[] callbacks = factory.resolveTools(s);

        assertThat(callbacks).isEmpty();
    }
}
