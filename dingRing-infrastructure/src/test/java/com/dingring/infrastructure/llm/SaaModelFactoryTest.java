package com.dingring.infrastructure.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SaaModelFactory#resolveUrl(String)} baseUrl 解析单元测试。
 * <p>背景：联调发现 Spring AI 默认在 baseUrl 后拼 /v1/chat/completions，
 * 带路径前缀的网关（StepFun step_plan、腾讯云 CloudBase）会 404。
 * <p>Phase A 改造：resolveUrl 从 SpringAiLlmService 迁移到 SaaModelFactory，
 * 测试同步迁移，确保 baseUrl 解析逻辑行为不变。
 */
@DisplayName("SaaModelFactory baseUrl 解析")
class SaaModelFactoryTest {

    @Test
    @DisplayName("无路径 baseUrl（DeepSeek 风格）保持 OpenAI 默认 /v1/chat/completions")
    void shouldKeepDefaultPathForHostOnlyUrl() {
        var parts = SaaModelFactory.resolveUrl("https://api.deepseek.com");
        assertThat(parts.baseUrl()).isEqualTo("https://api.deepseek.com");
        assertThat(parts.completionsPath()).isEqualTo("/v1/chat/completions");
    }

    @Test
    @DisplayName("StepFun step_plan/v1 前缀：拼 /chat/completions 不重复版本号")
    void shouldAppendChatCompletionsForStepFun() {
        var parts = SaaModelFactory.resolveUrl("https://api.stepfun.com/step_plan/v1");
        assertThat(parts.baseUrl()).isEqualTo("https://api.stepfun.com");
        assertThat(parts.completionsPath()).isEqualTo("/step_plan/v1/chat/completions");
    }

    @Test
    @DisplayName("腾讯云 CloudBase 网关 /v1/ai/cloudbase 前缀：拼 /chat/completions")
    void shouldAppendChatCompletionsForCloudBase() {
        var parts = SaaModelFactory.resolveUrl(
                "https://come-d7grhnf176744e01b.api.tcloudbasegateway.com/v1/ai/cloudbase");
        assertThat(parts.baseUrl()).isEqualTo("https://come-d7grhnf176744e01b.api.tcloudbasegateway.com");
        assertThat(parts.completionsPath()).isEqualTo("/v1/ai/cloudbase/chat/completions");
    }

    @Test
    @DisplayName("baseUrl 已含 /chat/completions 结尾：直接使用不重复拼接")
    void shouldUseFullPathAsIs() {
        var parts = SaaModelFactory.resolveUrl("https://api.stepfun.com/step_plan/v1/chat/completions");
        assertThat(parts.baseUrl()).isEqualTo("https://api.stepfun.com");
        assertThat(parts.completionsPath()).isEqualTo("/step_plan/v1/chat/completions");
    }

    @Test
    @DisplayName("尾部斜杠被规整")
    void shouldTrimTrailingSlash() {
        var parts = SaaModelFactory.resolveUrl("https://api.deepseek.com/v1/");
        assertThat(parts.baseUrl()).isEqualTo("https://api.deepseek.com");
        assertThat(parts.completionsPath()).isEqualTo("/v1/chat/completions");
    }
}
