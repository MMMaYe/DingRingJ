package com.dingring.adapter.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.net.URI;
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
 * {@link WsSessionManager} WebSocket 会话管理单元测试。
 */
@DisplayName("WsSessionManager WebSocket 会话管理")
class WsSessionManagerTest {

    private WsSessionManager sessionManager;

    @BeforeEach
    void setUp() {
        sessionManager = new WsSessionManager(new ObjectMapper());
    }

    private WebSocketSession mockSession(String sessionId) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(sessionId);
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    @Nested
    @DisplayName("register 注册连接")
    class Register {

        @Test
        @DisplayName("首次注册群连接后该群有 1 个在线 session")
        void shouldRegisterFirstSession() throws Exception {
            WebSocketSession session = mockSession("s1");

            sessionManager.register(1L, session);

            // 通过 pushToGroup 验证 session 被注册
            sessionManager.pushToGroup(1L, "TEST", Map.of("msg", "hi"));
            verify(session, atLeastOnce()).sendMessage(any(TextMessage.class));
        }

        @Test
        @DisplayName("同一群注册多个 session 时全部能收到广播")
        void shouldRegisterMultipleSessions() throws Exception {
            WebSocketSession s1 = mockSession("s1");
            WebSocketSession s2 = mockSession("s2");

            sessionManager.register(1L, s1);
            sessionManager.register(1L, s2);

            sessionManager.pushToGroup(1L, "TEST", Map.of("msg", "hi"));
            verify(s1, atLeastOnce()).sendMessage(any(TextMessage.class));
            verify(s2, atLeastOnce()).sendMessage(any(TextMessage.class));
        }
    }

    @Nested
    @DisplayName("unregister 注销连接")
    class Unregister {

        @Test
        @DisplayName("注销后不再收到该群广播")
        void shouldStopReceivingAfterUnregister() throws Exception {
            WebSocketSession session = mockSession("s1");
            sessionManager.register(1L, session);

            sessionManager.unregister(1L, session);
            sessionManager.pushToGroup(1L, "TEST", Map.of("msg", "hi"));

            verify(session, never()).sendMessage(any());
        }

        @Test
        @DisplayName("群内所有连接注销后该群条目被清理")
        void shouldRemoveGroupEntryWhenEmpty() throws Exception {
            WebSocketSession session = mockSession("s1");
            sessionManager.register(1L, session);
            sessionManager.unregister(1L, session);

            // 群条目被清理后，再次注册不报错
            WebSocketSession newSession = mockSession("s2");
            sessionManager.register(1L, newSession);
            sessionManager.pushToGroup(1L, "TEST", Map.of());
            verify(newSession, atLeastOnce()).sendMessage(any(TextMessage.class));
        }

        @Test
        @DisplayName("注销不存在的群连接不抛异常")
        void unregisterNonExistentGroupShouldNotThrow() {
            WebSocketSession session = mockSession("s1");

            // 不应抛异常
            sessionManager.unregister(999L, session);
        }
    }

    @Nested
    @DisplayName("pushToGroup 群广播")
    class PushToGroup {

        @Test
        @DisplayName("群无在线连接时静默返回（不抛异常）")
        void noSessionsShouldReturnSilently() {
            // 不注册任何 session，直接推送
            sessionManager.pushToGroup(999L, "TEST", Map.of("msg", "hi"));
            // 不抛异常即通过
        }

        @Test
        @DisplayName("消息被序列化为 {type, data} JSON 结构")
        void shouldSerializeToJsonStructure() throws Exception {
            WebSocketSession session = mockSession("s1");
            sessionManager.register(1L, session);

            sessionManager.pushToGroup(1L, "NEW_MESSAGE", Map.of("content", "hello"));

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

            sessionManager.register(1L, s1);
            sessionManager.register(1L, s2);

            // s1 发送失败，s2 仍应收到
            sessionManager.pushToGroup(1L, "TEST", Map.of("msg", "hi"));
            verify(s2, atLeastOnce()).sendMessage(any(TextMessage.class));
        }

        @Test
        @DisplayName("已关闭的 session 不会被发送消息")
        void closedSessionShouldNotReceiveMessages() throws Exception {
            WebSocketSession session = mock(WebSocketSession.class);
            when(session.getId()).thenReturn("s1");
            when(session.isOpen()).thenReturn(false);  // session 已关闭

            sessionManager.register(1L, session);
            sessionManager.pushToGroup(1L, "TEST", Map.of("msg", "hi"));

            verify(session, never()).sendMessage(any());
        }
    }

    @Nested
    @DisplayName("pushToSession 定向推送")
    class PushToSession {

        @Test
        @DisplayName("向指定 session 推送消息")
        void shouldPushToSpecificSession() throws Exception {
            WebSocketSession session = mockSession("s1");

            sessionManager.pushToSession(session, "ERROR", Map.of("message", "错误"));

            verify(session).sendMessage(any(TextMessage.class));
        }
    }
}
