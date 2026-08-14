package com.dingring.domain.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * {@link Agent} 聚合根单元测试：feature 字段的默认值与自定义值解析。
 * <p>设计要点：temperature/maxTokens 缺省时回退到 DEFAULT_*。
 */
@DisplayName("Agent 聚合根")
class AgentTest {

    @Nested
    @DisplayName("temperature 温度")
    class Temperature {

        @Test
        @DisplayName("feature 为 null 时使用默认 1.0")
        void nullFeatureShouldUseDefault() {
            Agent a = new Agent();
            a.setFeature(null);

            assertThat(a.temperature()).isEqualTo(1.0, within(0.0001));
        }

        @Test
        @DisplayName("feature 无 temperature 字段时使用默认 1.0")
        void noTemperatureKeyShouldUseDefault() {
            Agent a = new Agent();
            a.setFeature(Map.of("maxTokens", 2048));

            assertThat(a.temperature()).isEqualTo(1.0, within(0.0001));
        }

        @Test
        @DisplayName("feature.temperature 为数字时使用自定义值")
        void customTemperatureShouldBeUsed() {
            Agent a = new Agent();
            a.setFeature(Map.of("temperature", 0.2));

            assertThat(a.temperature()).isEqualTo(0.2, within(0.0001));
        }

        @Test
        @DisplayName("feature.temperature 为非数字时回退默认")
        void nonNumberTemperatureShouldFallback() {
            Agent a = new Agent();
            a.setFeature(Map.of("temperature", "high"));

            assertThat(a.temperature()).isEqualTo(1.0, within(0.0001));
        }
    }

    @Nested
    @DisplayName("maxTokens 最大 token 数")
    class MaxTokens {

        @Test
        @DisplayName("feature 为 null 时使用默认 100000")
        void nullFeatureShouldUseDefault() {
            Agent a = new Agent();
            a.setFeature(null);

            assertThat(a.maxTokens()).isEqualTo(100000);
        }

        @Test
        @DisplayName("无 feature 配置时使用调用方提供的 fallback")
        void customFallbackShouldBeUsed() {
            Agent a = new Agent();
            a.setFeature(null);

            assertThat(a.maxTokens(16384)).isEqualTo(16384);
        }

        @Test
        @DisplayName("feature.maxTokens 优先于调用方 fallback")
        void customMaxTokensShouldOverrideFallback() {
            Agent a = new Agent();
            a.setFeature(Map.of("maxTokens", 8192));

            assertThat(a.maxTokens(16384)).isEqualTo(8192);
        }


        @Test
        @DisplayName("feature.maxTokens 为浮点数时取整")
        void floatMaxTokensShouldBeTruncated() {
            Agent a = new Agent();
            a.setFeature(Map.of("maxTokens", 2048.9));

            assertThat(a.maxTokens()).isEqualTo(2048);
        }
    }
}
