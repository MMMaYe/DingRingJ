package com.dingring.app.orchestrator;

import com.dingring.domain.group.MessageRepository;
import com.dingring.domain.group.SenderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link Terminator} 终止判定单元测试。
 * <p>核心规则：达到最大轮次（Agent 发言数 >= maxRounds）触发自动收束。
 */
@DisplayName("Terminator 终止判定")
class TerminatorTest {

    private MessageRepository messageRepository;
    private Terminator terminator;

    @BeforeEach
    void setUp() throws Exception {
        messageRepository = mock(MessageRepository.class);
        terminator = new Terminator(messageRepository);
        setField(terminator, "maxRounds", 20);
        setField(terminator, "autoReplies", 2);
    }

    private void setField(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Nested
    @DisplayName("reachedMaxRounds")
    class ReachedMaxRounds {

        @Test
        @DisplayName("topicId 为 null 时永远返回 false（闲聊不收束）")
        void nullTopicShouldReturnFalse() {
            assertThat(terminator.reachedMaxRounds(null)).isFalse();
        }

        @Test
        @DisplayName("Agent 发言数 < maxRounds 时返回 false")
        void belowMaxShouldReturnFalse() {
            when(messageRepository.countByTopicIdAndSenderType(1L, SenderType.AGENT)).thenReturn(19L);

            assertThat(terminator.reachedMaxRounds(1L)).isFalse();
        }

        @Test
        @DisplayName("Agent 发言数 == maxRounds 时返回 true（边界）")
        void atMaxShouldReturnTrue() {
            when(messageRepository.countByTopicIdAndSenderType(1L, SenderType.AGENT)).thenReturn(20L);

            assertThat(terminator.reachedMaxRounds(1L)).isTrue();
        }

        @Test
        @DisplayName("Agent 发言数 > maxRounds 时返回 true")
        void overMaxShouldReturnTrue() {
            when(messageRepository.countByTopicIdAndSenderType(1L, SenderType.AGENT)).thenReturn(25L);

            assertThat(terminator.reachedMaxRounds(1L)).isTrue();
        }
    }

    @Nested
    @DisplayName("currentRound 当前轮次")
    class CurrentRound {

        @Test
        @DisplayName("topicId 为 null 时返回 0")
        void nullTopicShouldReturnZero() {
            assertThat(terminator.currentRound(null)).isZero();
        }

        @Test
        @DisplayName("返回 Agent 发言条数")
        void shouldReturnAgentMessageCount() {
            when(messageRepository.countByTopicIdAndSenderType(1L, SenderType.AGENT)).thenReturn(8L);

            assertThat(terminator.currentRound(1L)).isEqualTo(8L);
        }
    }

    @Test
    @DisplayName("getMaxRounds/getAutoReplies 反射注入的值可读取")
    void gettersShouldReflectInjectedValues() {
        assertThat(terminator.getMaxRounds()).isEqualTo(20);
        assertThat(terminator.getAutoReplies()).isEqualTo(2);
    }
}
