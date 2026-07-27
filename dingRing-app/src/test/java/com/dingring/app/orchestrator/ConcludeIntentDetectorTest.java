package com.dingring.app.orchestrator;

import com.dingring.domain.agent.Agent;
import com.dingring.domain.service.LlmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ConcludeIntentDetector} 用户总结意图判定单元测试。
 * <p>覆盖：YES/NO 判定、大小写与噪声容错、null 返回、LLM 异常降级。
 */
@DisplayName("ConcludeIntentDetector 总结意图判定")
class ConcludeIntentDetectorTest {

    private LlmService llmService;
    private ConcludeIntentDetector detector;
    private Agent judge;

    @BeforeEach
    void setUp() {
        llmService = mock(LlmService.class);
        detector = new ConcludeIntentDetector(llmService);
        judge = new Agent();
        judge.setId(99L);
        judge.setName("苏教授");
    }

    @Test
    @DisplayName("LLM 返回 YES 时判定为总结意图")
    void yesVerdictShouldReturnTrue() {
        when(llmService.chat(any(), anyString(), any())).thenReturn("YES");

        assertThat(detector.isConcludeIntent(judge, "@苏教授 总结一下")).isTrue();
    }

    @Test
    @DisplayName("LLM 返回 NO 时判定为非总结意图")
    void noVerdictShouldReturnFalse() {
        when(llmService.chat(any(), anyString(), any())).thenReturn("NO");

        assertThat(detector.isConcludeIntent(judge, "@苏教授 你怎么看")).isFalse();
    }

    @Test
    @DisplayName("YES 带小写与前后空白时仍判定为总结意图")
    void lowercaseYesWithWhitespaceShouldReturnTrue() {
        when(llmService.chat(any(), anyString(), any())).thenReturn("  yes\n");

        assertThat(detector.isConcludeIntent(judge, "@苏教授 收个尾吧")).isTrue();
    }

    @Test
    @DisplayName("YES 混杂解释文本时仍判定为总结意图")
    void yesWithNoiseShouldReturnTrue() {
        when(llmService.chat(any(), anyString(), any())).thenReturn("判定结果：YES");

        assertThat(detector.isConcludeIntent(judge, "@苏教授 请给出结论")).isTrue();
    }

    @Test
    @DisplayName("以 NO 开头的回答即使包含 YES 字样也判定为非总结意图")
    void noPrefixShouldWinOverEmbeddedYes() {
        when(llmService.chat(any(), anyString(), any())).thenReturn("NO（用户不是在要求总结，YES 不成立）");

        assertThat(detector.isConcludeIntent(judge, "@苏教授 继续聊聊")).isFalse();
    }

    @Test
    @DisplayName("LLM 返回 null 时判定为非总结意图")
    void nullVerdictShouldReturnFalse() {
        when(llmService.chat(any(), anyString(), any())).thenReturn(null);

        assertThat(detector.isConcludeIntent(judge, "@苏教授 总结一下")).isFalse();
    }

    @Test
    @DisplayName("LLM 调用抛异常时降级返回 false 不抛出")
    void llmExceptionShouldDegradeToFalse() {
        when(llmService.chat(any(), anyString(), any())).thenThrow(new RuntimeException("LLM 不可用"));

        assertThat(detector.isConcludeIntent(judge, "@苏教授 总结一下")).isFalse();
    }
}
