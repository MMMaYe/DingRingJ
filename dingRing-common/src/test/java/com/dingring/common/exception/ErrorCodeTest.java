package com.dingring.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ErrorCode} 单元测试：校验错误码与 HTTP 状态码、默认提示的对应关系。
 * <p>不变式：错误码字符串名与 HTTP 状态语义一致（4xx 客户端 / 5xx 服务端）。
 */
@DisplayName("ErrorCode 错误码枚举")
class ErrorCodeTest {

    @Test
    @DisplayName("PARAM_INVALID 对应 400")
    void paramInvalidShouldBe400() {
        assertThat(ErrorCode.PARAM_INVALID.getHttpStatus()).isEqualTo(400);
        assertThat(ErrorCode.PARAM_INVALID.getDefaultMessage()).isEqualTo("参数校验失败");
    }

    @Test
    @DisplayName("UNAUTHORIZED 对应 401")
    void unauthorizedShouldBe401() {
        assertThat(ErrorCode.UNAUTHORIZED.getHttpStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("FORBIDDEN 对应 403")
    void forbiddenShouldBe403() {
        assertThat(ErrorCode.FORBIDDEN.getHttpStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("NOT_FOUND 对应 404")
    void notFoundShouldBe404() {
        assertThat(ErrorCode.NOT_FOUND.getHttpStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("TOPIC_ALREADY_IN_PROGRESS / TOPIC_NOT_IN_PROGRESS 对应 409")
    void topicStateConflictShouldBe409() {
        assertThat(ErrorCode.TOPIC_ALREADY_IN_PROGRESS.getHttpStatus()).isEqualTo(409);
        assertThat(ErrorCode.TOPIC_NOT_IN_PROGRESS.getHttpStatus()).isEqualTo(409);
    }

    @Test
    @DisplayName("服务端错误统一 5xx")
    void serverErrorsShouldBe5xx() {
        assertThat(ErrorCode.INTERNAL_ERROR.getHttpStatus()).isEqualTo(500);
        assertThat(ErrorCode.TOPIC_CONCLUSION_FAILED.getHttpStatus()).isEqualTo(500);
        assertThat(ErrorCode.LLM_API_ERROR.getHttpStatus()).isEqualTo(502);
        assertThat(ErrorCode.ALL_AGENTS_FAILED.getHttpStatus()).isEqualTo(503);
    }

    @Test
    @DisplayName("所有错误码均有非空默认提示")
    void allCodesShouldHaveNonBlankDefaultMessage() {
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(code.getDefaultMessage())
                    .as("错误码 %s 必须有默认提示", code.name())
                    .isNotBlank();
        }
    }
}
