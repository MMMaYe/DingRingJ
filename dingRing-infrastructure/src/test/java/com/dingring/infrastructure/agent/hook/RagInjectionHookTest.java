package com.dingring.infrastructure.agent.hook;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.dingring.domain.service.RagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link RagInjectionHook} 知识注入单测。
 * <p>策略：用真实 {@link OverAllState}（Map 构造），仅 mock {@link RagService}。
 * <p>验证：有知识时注入 SystemMessage、无知识/失败时返回空 Map（不注入）。
 */
@DisplayName("RagInjectionHook 知识注入")
class RagInjectionHookTest {

    private RagService ragService;
    private RagInjectionHook hook;

    @BeforeEach
    void setUp() {
        ragService = mock(RagService.class);
        hook = new RagInjectionHook(ragService);
    }

    @Test
    @DisplayName("检索到知识时注入 SystemMessage（含知识内容 + 前缀标识）")
    void shouldInjectKnowledgeWhenRetrieved() {
        when(ragService.retrieve("缓存雪崩怎么解决", 1L))
                .thenReturn("知识库检索结果：\n[1] 使用 Redis 集群 + 降级策略");
        OverAllState state = new OverAllState(Map.of("groupId", 1L, "ragQuery", "缓存雪崩怎么解决"));

        Map<String, Object> result = hook.beforeModel(state, null).join();

        assertThat(result).containsKey("messages");
        SystemMessage msg = (SystemMessage) result.get("messages");
        assertThat(msg.getText())
                .contains("知识库参考")                    // 前缀标识
                .contains("使用 Redis 集群 + 降级策略");  // 知识正文
    }

    @Test
    @DisplayName("检索为空时返回空 Map（不注入）")
    void shouldReturnEmptyWhenKnowledgeBlank() {
        when(ragService.retrieve("query", 1L)).thenReturn("");
        OverAllState state = new OverAllState(Map.of("groupId", 1L, "ragQuery", "query"));

        Map<String, Object> result = hook.beforeModel(state, null).join();

        assertThat(result).doesNotContainKey("messages");
    }

    @Test
    @DisplayName("groupId 缺失时返回空 Map")
    void shouldReturnEmptyWhenGroupIdNull() {
        OverAllState state = new OverAllState(Map.of("ragQuery", "query"));

        Map<String, Object> result = hook.beforeModel(state, null).join();

        assertThat(result).doesNotContainKey("messages");
    }

    @Test
    @DisplayName("ragQuery 缺失时不触发检索")
    void shouldReturnEmptyWhenQueryBlank() {
        OverAllState state = new OverAllState(Map.of("groupId", 1L));

        Map<String, Object> result = hook.beforeModel(state, null).join();

        assertThat(result).doesNotContainKey("messages");
    }
}
