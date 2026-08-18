package com.dingring.infrastructure.llm;

import com.dingring.domain.agent.Agent;
import com.dingring.domain.service.LlmService.CallOptions;
import com.dingring.infrastructure.agent.hook.GroupRosterHook;
import com.dingring.infrastructure.agent.hook.InjectKbHook;
import com.dingring.infrastructure.agent.hook.GroupContextMemoryHook;
import com.dingring.infrastructure.agent.hook.ProfileInjectionHook;
import com.dingring.infrastructure.agent.hook.SystemMessageMergeHook;
import com.dingring.infrastructure.agent.tool.KnowledgeSearchTool;
import com.dingring.infrastructure.agent.tool.TopicHistoryTool;
import com.dingring.infrastructure.agent.tool.UserProfileQueryTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link SaaLlmFactory} 单元测试。
 * <p>同时覆盖 baseUrl 解析和 LLM 输出预算的优先级，防止普通 Agent 发言被默认预算截断。
 */
@DisplayName("SaaLlmFactory")
class SaaLlmFactoryTest {

    @Test
    @DisplayName("无路径 baseUrl（DeepSeek 风格）保持 OpenAI 默认 /v1/chat/completions")
    void shouldKeepDefaultPathForHostOnlyUrl() {
        var parts = SaaLlmFactory.resolveUrl("https://api.deepseek.com");
        assertThat(parts.baseUrl()).isEqualTo("https://api.deepseek.com");
        assertThat(parts.completionsPath()).isEqualTo("/v1/chat/completions");
    }

    @Test
    @DisplayName("StepFun step_plan/v1 前缀：拼 /chat/completions 不重复版本号")
    void shouldAppendChatCompletionsForStepFun() {
        var parts = SaaLlmFactory.resolveUrl("https://api.stepfun.com/step_plan/v1");
        assertThat(parts.baseUrl()).isEqualTo("https://api.stepfun.com");
        assertThat(parts.completionsPath()).isEqualTo("/step_plan/v1/chat/completions");
    }

    @Test
    @DisplayName("腾讯云 CloudBase 网关 /v1/ai/cloudbase 前缀：拼 /chat/completions")
    void shouldAppendChatCompletionsForCloudBase() {
        var parts = SaaLlmFactory.resolveUrl(
                "https://come-d7grhnf176744e01b.api.tcloudbasegateway.com/v1/ai/cloudbase");
        assertThat(parts.baseUrl()).isEqualTo("https://come-d7grhnf176744e01b.api.tcloudbasegateway.com");
        assertThat(parts.completionsPath()).isEqualTo("/v1/ai/cloudbase/chat/completions");
    }

    @Test
    @DisplayName("baseUrl 已含 /chat/completions 结尾：直接使用不重复拼接")
    void shouldUseFullPathAsIs() {
        var parts = SaaLlmFactory.resolveUrl("https://api.stepfun.com/step_plan/v1/chat/completions");
        assertThat(parts.baseUrl()).isEqualTo("https://api.stepfun.com");
        assertThat(parts.completionsPath()).isEqualTo("/step_plan/v1/chat/completions");
    }

    @Test
    @DisplayName("尾部斜杠被规整")
    void shouldTrimTrailingSlash() {
        var parts = SaaLlmFactory.resolveUrl("https://api.deepseek.com/v1/");
        assertThat(parts.baseUrl()).isEqualTo("https://api.deepseek.com");
        assertThat(parts.completionsPath()).isEqualTo("/v1/chat/completions");
    }

    @Test
    @DisplayName("无 Agent maxTokens 时使用配置默认预算")
    void shouldUseConfiguredDefaultMaxTokens() {
        SaaLlmFactory factory = factoryWithDefaultMaxTokens(16384);
        Agent agent = agentWithFeature(null);

        OpenAiChatOptions options = defaultOptions(factory.buildChatModel(agent, null));

        assertThat(options.getMaxTokens()).isEqualTo(16384);
    }

    @Test
    @DisplayName("Agent feature.maxTokens 优先于配置默认预算")
    void shouldPreferAgentMaxTokens() {
        SaaLlmFactory factory = factoryWithDefaultMaxTokens(16384);
        Agent agent = agentWithFeature(Map.of("maxTokens", 8192));

        OpenAiChatOptions options = defaultOptions(factory.buildChatModel(agent, null));

        assertThat(options.getMaxTokens()).isEqualTo(8192);
    }

    @Test
    @DisplayName("CallOptions.maxTokens 优先于 Agent 与配置默认预算")
    void shouldPreferCallOptionsMaxTokens() {
        SaaLlmFactory factory = factoryWithDefaultMaxTokens(16384);
        Agent agent = agentWithFeature(Map.of("maxTokens", 8192));
        CallOptions callOptions = new CallOptions(0.0, 1024, null);

        OpenAiChatOptions options = defaultOptions(factory.buildChatModel(agent, callOptions));

        assertThat(options.getMaxTokens()).isEqualTo(1024);
    }

    private static Agent agentWithFeature(Map<String, Object> feature) {
        Agent agent = new Agent();
        agent.setName("test-agent");
        agent.setModelName("test-model");
        agent.setBaseUrl("https://api.example.com");
        agent.setApiKey("test-key");
        agent.setFeature(feature);
        return agent;
    }

    private static SaaLlmFactory factoryWithDefaultMaxTokens(int maxTokens) {
        SaaLlmFactory factory = new SaaLlmFactory(
                mock(GroupContextMemoryHook.class),
                mock(ProfileInjectionHook.class),
                mock(GroupRosterHook.class),
                mock(InjectKbHook.class),
                mock(SystemMessageMergeHook.class),
                mock(UserProfileQueryTool.class),
                mock(TopicHistoryTool.class),
                mock(KnowledgeSearchTool.class));
        ReflectionTestUtils.setField(factory, "connectTimeoutSeconds", 10L);
        ReflectionTestUtils.setField(factory, "readTimeoutSeconds", 120L);
        ReflectionTestUtils.setField(factory, "defaultMaxTokens", maxTokens);
        return factory;
    }

    private static OpenAiChatOptions defaultOptions(OpenAiChatModel model) {
        return (OpenAiChatOptions) model.getDefaultOptions();
    }
}
