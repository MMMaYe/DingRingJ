package com.dingring.infrastructure.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link WsBroadcastAdapter} 群广播适配器单元测试。
 * <p>拆分自原 WsSessionManagerTest 的 broadcast 相关用例。
 * <p>覆盖：群无在线连接静默返回、JSON 结构、单 session 失败不影响其他、已关闭 session 不发送。
 */
@DisplayName("WsBroadcastAdapter 群广播适配器")
class WsBroadcastAdapterTest {

    private WsSessionRegistryImpl registry;
    private WsBroadcastAdapter adapter;

    @BeforeEach
    void setUp() {
        registry = new WsSessionRegistryImpl(new ObjectMapper());
        adapter = new WsBroadcastAdapter(registry);
    }

    private WebSocketSession mockSession(String sessionId) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(sessionId);
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    @Nested
    @DisplayName("broadcast 群广播")
    class Broadcast {

        @Test
        @DisplayName("群无在线连接时静默返回（不抛异常）")
        void noSessionsShouldReturnSilently() {
            adapter.broadcast(999L, "TEST", Map.of("msg", "hi"));  // 不抛异常即通过
        }

        @Test
        @DisplayName("群内 session 全部收到广播")
        void shouldBroadcastToAllSessions() throws Exception {
            WebSocketSession s1 = mockSession("s1");
            WebSocketSession s2 = mockSession("s2");
            registry.register(1L, s1);
            registry.register(1L, s2);

            adapter.broadcast(1L, "TEST", Map.of("msg", "hi"));

            verify(s1, atLeastOnce()).sendMessage(any(TextMessage.class));
            verify(s2, atLeastOnce()).sendMessage(any(TextMessage.class));
        }

        @Test
        @DisplayName("消息被序列化为 {type, data} JSON 结构")
        void shouldSerializeToJsonStructure() throws Exception {
            WebSocketSession session = mockSession("s1");
            registry.register(1L, session);

            adapter.broadcast(1L, "NEW_MESSAGE", Map.of("content", "hello"));

            org.mockito.ArgumentCaptor<TextMessage> captor =
                    org.mockito.ArgumentCaptor.forClass(TextMessage.class);
            verify(session, atLeastOnce()).sendMessage(captor.capture());
            String payload = captor.getValue().getPayload();
            assertThat(payload).contains("\"type\":\"NEW_MESSAGE\"");
            assertThat(payload).contains("\"data\":");
            assertThat(payload).contains("hello");
        }

        @Test
        @DisplayName("单个 session 发送失败不影响其他 session")
        void oneSessionFailShouldNotAffectOthers() throws Exception {
            WebSocketSession s1 = mockSession("s1");
            WebSocketSession s2 = mockSession("s2");
            doThrow(new IOException("s1 网络断开")).when(s1).sendMessage(any());

            registry.register(1L, s1);
            registry.register(1L, s2);

            // s1 发送失败，s2 仍应收到
            adapter.broadcast(1L, "TEST", Map.of("msg", "hi"));
            verify(s2, atLeastOnce()).sendMessage(any(TextMessage.class));
        }

        @Test
        @DisplayName("已关闭的 session 不会被发送消息")
        void closedSessionShouldNotReceiveMessages() throws Exception {
            WebSocketSession session = mock(WebSocketSession.class);
            when(session.getId()).thenReturn("s1");
            when(session.isOpen()).thenReturn(false);

            registry.register(1L, session);
            adapter.broadcast(1L, "TEST", Map.of("msg", "hi"));

            verify(session, never()).sendMessage(any());
        }
    }
}
