package com.dingring.common.response;

import com.dingring.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ApiResponse} 单元测试：覆盖 ok/fail 工厂方法的统一响应结构。
 * <p>设计要点：success 标志位、errorCode 透传、data 承载。
 */
@DisplayName("ApiResponse 统一响应")
class ApiResponseTest {

    @Nested
    @DisplayName("ok 工厂方法")
    class OkFactory {

        @Test
        @DisplayName("ok(data) 包装数据并标记成功")
        void okWithDataShouldReturnSuccessResponse() {
            // when
            ApiResponse<String> resp = ApiResponse.ok("hello");

            // then
            assertThat(resp.isSuccess()).isTrue();
            assertThat(resp.getErrorCode()).isNull();
            assertThat(resp.getMessage()).isEqualTo("操作成功");
            assertThat(resp.getData()).isEqualTo("hello");
        }

        @Test
        @DisplayName("ok() 无数据时 data 为 null")
        void okWithoutDataShouldHaveNullData() {
            ApiResponse<Void> resp = ApiResponse.ok();

            assertThat(resp.isSuccess()).isTrue();
            assertThat(resp.getData()).isNull();
        }
    }

    @Nested
    @DisplayName("fail 工厂方法")
    class FailFactory {

        @Test
        @DisplayName("fail(errorCode, message) 使用自定义 message")
        void failWithCustomMessage() {
            ApiResponse<Void> resp = ApiResponse.fail(ErrorCode.NOT_FOUND, "群不存在: 99");

            assertThat(resp.isSuccess()).isFalse();
            assertThat(resp.getErrorCode()).isEqualTo("NOT_FOUND");
            assertThat(resp.getMessage()).isEqualTo("群不存在: 99");
            assertThat(resp.getData()).isNull();
        }

        @Test
        @DisplayName("fail(errorCode) 回退到默认 message")
        void failWithDefaultMessage() {
            ApiResponse<Void> resp = ApiResponse.fail(ErrorCode.PARAM_INVALID);

            assertThat(resp.isSuccess()).isFalse();
            assertThat(resp.getErrorCode()).isEqualTo("PARAM_INVALID");
            assertThat(resp.getMessage()).isEqualTo(ErrorCode.PARAM_INVALID.getDefaultMessage());
        }
    }
}
