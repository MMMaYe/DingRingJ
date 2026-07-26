package com.dingring.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link BizException} 与 {@link ParamException} 单元测试。
 * <p>设计要点：异常携带 ErrorCode，message 可自定义或回退默认。
 */
@DisplayName("BizException 业务异常")
class BizExceptionTest {

    @Nested
    @DisplayName("BizException 构造")
    class BizExceptionConstructors {

        @Test
        @DisplayName("仅传 errorCode 时使用默认 message")
        void withErrorCodeOnlyShouldUseDefaultMessage() {
            BizException e = new BizException(ErrorCode.NOT_FOUND);

            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
            assertThat(e.getMessage()).isEqualTo(ErrorCode.NOT_FOUND.getDefaultMessage());
        }

        @Test
        @DisplayName("传自定义 message 时覆盖默认 message")
        void withCustomMessageShouldOverrideDefault() {
            BizException e = new BizException(ErrorCode.NOT_FOUND, "群不存在: 99");

            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
            assertThat(e.getMessage()).isEqualTo("群不存在: 99");
        }

        @Test
        @DisplayName("携带 cause 链")
        void withCauseShouldRetainCause() {
            Exception cause = new RuntimeException("db down");
            BizException e = new BizException(ErrorCode.INTERNAL_ERROR, "系统异常", cause);

            assertThat(e.getCause()).isSameAs(cause);
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR);
        }

        @Test
        @DisplayName("是 RuntimeException 子类（不被检查异常约束）")
        void shouldBeRuntimeException() {
            assertThat(RuntimeException.class.isAssignableFrom(BizException.class)).isTrue();
        }
    }

    @Nested
    @DisplayName("ParamException 参数异常")
    class ParamExceptionTests {

        @Test
        @DisplayName("ParamException 始终携带 PARAM_INVALID 错误码")
        void paramExceptionShouldCarryParamInvalidCode() {
            ParamException e = new ParamException("专家 Agent 不能同时是普通成员");

            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
            assertThat(e.getMessage()).isEqualTo("专家 Agent 不能同时是普通成员");
        }

        @Test
        @DisplayName("抛出与捕获行为")
        void shouldThrowAndCatch() {
            assertThatThrownBy(() -> {
                throw new ParamException("缺少参数");
            })
                    .isInstanceOf(BizException.class)
                    .isInstanceOf(ParamException.class)
                    .hasMessage("缺少参数");
        }
    }
}
