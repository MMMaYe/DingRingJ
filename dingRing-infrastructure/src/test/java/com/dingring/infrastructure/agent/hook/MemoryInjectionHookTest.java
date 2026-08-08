package com.dingring.infrastructure.agent.hook;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.dingring.domain.service.MemoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link MemoryInjectionHook} 群记忆注入单测。
 * <p>承接自 Phase C ContextBuilder 的群记忆拼接逻辑（Phase D 迁移到 Hook）。
 * <p>策略：用真实 {@link OverAllState}（Map 构造），仅 mock {@link MemoryService}。
 */
@DisplayName("MemoryInjectionHook 群记忆注入")
class MemoryInjectionHookTest {

    private MemoryService memoryService;
    private MemoryInjectionHook hook;

    @BeforeEach
    void setUp() {
        memoryService = mock(MemoryService.class);
        hook = new MemoryInjectionHook(memoryService);
    }

    @Test
    @DisplayName("有群记忆时注入 SystemMessage（含记忆内容 + 前缀标识）")
    void shouldInjectMemoryWhenPresent() {
        when(memoryService.retrieveMemory(1L)).thenReturn("历史结论：Redis 用 ZSET");
        OverAllState state = new OverAllState(Map.of("groupId", 1L));

        Map<String, Object> result = hook.beforeModel(state, null).join();

        assertThat(result).containsKey("messages");
        SystemMessage msg = (SystemMessage) result.get("messages");
        assertThat(msg.getText())
                .contains("历史结论：Redis 用 ZSET")   // 记忆正文
                .contains("群历史记忆");              // 前缀标识
    }

    @Test
    @DisplayName("群记忆为空时返回空 Map（不注入）")
    void shouldReturnEmptyWhenMemoryBlank() {
        when(memoryService.retrieveMemory(1L)).thenReturn("");
        OverAllState state = new OverAllState(Map.of("groupId", 1L));

        Map<String, Object> result = hook.beforeModel(state, null).join();

        assertThat(result).doesNotContainKey("messages");
    }

    @Test
    @DisplayName("groupId 为 null 时返回空 Map")
    void shouldReturnEmptyWhenGroupIdNull() {
        OverAllState state = new OverAllState(Map.of());

        Map<String, Object> result = hook.beforeModel(state, null).join();

        assertThat(result).doesNotContainKey("messages");
    }
}
