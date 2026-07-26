package com.dingring.common.response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PageResult} 单元测试：分页结果载体。
 */
@DisplayName("PageResult 分页结果")
class PageResultTest {

    @Test
    @DisplayName("构造时保留 items/total/page/pageSize")
    void shouldRetainAllFields() {
        // given
        List<String> items = List.of("a", "b");

        // when
        PageResult<String> result = new PageResult<>(items, 100L, 2, 20);

        // then
        assertThat(result.getItems()).containsExactly("a", "b");
        assertThat(result.getTotal()).isEqualTo(100L);
        assertThat(result.getPage()).isEqualTo(2);
        assertThat(result.getPageSize()).isEqualTo(20);
    }

    @Test
    @DisplayName("无参构造 + setter 同样可用（便于框架反序列化）")
    void noArgsConstructorAndSetters() {
        PageResult<String> result = new PageResult<>();
        result.setItems(List.of("x"));
        result.setTotal(1L);
        result.setPage(1);
        result.setPageSize(10);

        assertThat(result.getItems()).containsExactly("x");
        assertThat(result.getTotal()).isEqualTo(1L);
    }
}
