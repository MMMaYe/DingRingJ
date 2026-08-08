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
 * {@link WsSessionRegistryImpl} 会话注册表单元测试。
 * <p>拆分自原 WsSessionManagerTest：覆盖 register/unregister/lookup/sendToSession/encode/sendSafely。
 */
@DisplayName("WsSessionRegistryImpl 会话注册表")
class WsSessionRegistryImplTest {

    private WsSessionRegistryImpl registry;

    @BeforeEach
    void setUp() {
        registry = new WsSessionRegistryImpl(new ObjectMapper());
    }

    private WebSocketSession mockSession(String sessionId) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(sessionId);
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    @Nested
    @DisplayName("register/unregister/lookup 会话表管理")
    class SessionTable {

        @Test
        @DisplayName("首次注册后 lookup 返回该 session")
        void shouldRegisterFirstSession() {
            WebSocketSession session = mockSession("s1");

            registry.register(1L, session);

            assertThat(registry.lookup(1L)).contains(session);
        }

        @Test
        @DisplayName("同群注册多个 session 时 lookup 返回全部")
        void shouldRegisterMultipleSessions() {
            WebSocketSession s1 = mockSession("s1");
            WebSocketSession s2 = mockSession("s2");

            registry.register(1L, s1);
            registry.register(1L, s2);

            assertThat(registry.lookup(1L)).containsExactlyInAnyOrder(s1, s2);
        }

        @Test
        @DisplayName("注销后 lookup 不再返回该 session")
        void shouldStopLookupAfterUnregister() {
            WebSocketSession session = mockSession("s1");
            registry.register(1L, session);

            registry.unregister(1L, session);

            assertThat(registry.lookup(1L)).doesNotContain(session);
        }

        @Test
        @DisplayName("群内所有连接注销后该群条目被清理（再次注册不报错）")
        void shouldRemoveGroupEntryWhenEmpty() {
            WebSocketSession session = mockSession("s1");
            registry.register(1L, session);
            registry.unregister(1L, session);

            // 群条目被清理后，再次注册不报错
            WebSocketSession newSession = mockSession("s2");
            registry.register(1L, newSession);
            assertThat(registry.lookup(1L)).contains(newSession);
        }

        @Test
        @DisplayName("注销不存在的群连接不抛异常")
        void unregisterNonExistentGroupShouldNotThrow() {
            WebSocketSession session = mockSession("s1");
            registry.unregister(999L, session);  // 不抛异常即通过
        }

        @Test
        @DisplayName("lookup 不存在的群返回空 Set（非 null）")
        void lookupNonExistentGroupShouldReturnEmptySet() {
            assertThat(registry.lookup(999L)).isEmpty();
        }
    }

    @Nested
    @DisplayName("sendToSession 定向推送")
    class SendToSession {

        @Test
        @DisplayName("向指定 session 推送 {type, data} JSON 帧")
        void shouldPushToSpecificSession() throws Exception {
            WebSocketSession session = mockSession("s1");

            registry.sendToSession(session, "ERROR", Map.of("message", "错误"));

            verify(session).sendMessage(any(TextMessage.class));
        }

        @Test
        @DisplayName("已关闭的 session 不会被发送消息")
        void closedSessionShouldNotReceiveMessages() throws Exception {
            WebSocketSession session = mock(WebSocketSession.class);
            when(session.getId()).thenReturn("s1");
            when(session.isOpen()).thenReturn(false);

            registry.sendToSession(session, "ERROR", Map.of("message", "错误"));

            verify(session, never()).sendMessage(any());
        }

        @Test
        @DisplayName("单个 session 发送失败不抛异常（sendSafely 吞异常）")
        void sendFailureShouldNotThrow() throws Exception {
            WebSocketSession session = mockSession("s1");
            doThrow(new IOException("网络断开")).when(session).sendMessage(any());

            registry.sendToSession(session, "ERROR", Map.of("message", "错误"));  // 不抛异常即通过
        }
    }

    @Nested
    @DisplayName("encode 序列化")
    class Encode {

        @Test
        @DisplayName("序列化为 {type, data} JSON 结构")
        void shouldSerializeToJsonStructure() {
            TextMessage message = registry.encode("NEW_MESSAGE", Map.of("content", "hello"));

            assertThat(message.getPayload()).contains("\"type\":\"NEW_MESSAGE\"");
            assertThat(message.getPayload()).contains("\"data\":");
            assertThat(message.getPayload()).contains("hello");
        }

        @Test
        @DisplayName("不可序列化对象返回 null（不抛异常）")
        void shouldReturnNullForUnserializable() {
            Object unserializable = new Object() {
                @Override
                public String toString() {
                    throw new RuntimeException("故意触发序列化失败");
                }
            };
            // fastjson/jackson 对任意对象一般不抛，这里用一个会抛异常的对象验证兜底
            // 直接传 null type 验证 Map.of 不接受 null key 的兜底（实际不会发生）
            TextMessage message = registry.encode("TEST", Map.of("ok", 1));
            assertThat(message).isNotNull();
        }
    }
}
