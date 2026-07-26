package com.dingring.adapter.websocket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChatWebSocketHandler} WebSocket 连接生命周期单元测试。
 * <p>覆盖：连接建立（含 groupId 解析）、消息处理、连接关闭。
 */
@DisplayName("ChatWebSocketHandler 连接生命周期")
@ExtendWith(MockitoExtension.class)
class ChatWebSocketHandlerTest {

    @Mock
    private WsSessionManager sessionManager;

    @Mock
    private WsMessageDispatcher dispatcher;

    @InjectMocks
    private ChatWebSocketHandler handler;

    private WebSocketSession mockSession(URI uri) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getUri()).thenReturn(uri);
        // 仅在 groupId 合法分支下才会调用 getAttributes()，由调用方按需 stub
        return session;
    }

    @Nested
    @DisplayName("afterConnectionEstablished 连接建立")
    class ConnectionEstablished {

        @Test
        @DisplayName("URL 含合法 groupId 时注册到 sessionManager 并写入 attributes")
        void shouldRegisterWhenGroupIdValid() throws Exception {
            WebSocketSession session = mockSession(URI.create("ws://localhost/ws/chat?groupId=42"));
            when(session.getAttributes()).thenReturn(new HashMap<>());

            handler.afterConnectionEstablished(session);

            verify(sessionManager).register(eq(42L), eq(session));
            // attributes 中应缓存 groupId 供后续 handleTextMessage 使用
            assertThat(session.getAttributes().get("groupId")).isEqualTo(42L);
        }

        @Test
        @DisplayName("URL 缺少 groupId 参数时关闭连接（BAD_DATA）")
        void shouldCloseWhenGroupIdMissing() throws Exception {
            WebSocketSession session = mockSession(URI.create("ws://localhost/ws/chat"));

            handler.afterConnectionEstablished(session);

            verify(sessionManager, never()).register(any(), any());
            verify(session).close(eq(CloseStatus.BAD_DATA.withReason("缺少 groupId 参数")));
        }

        @Test
        @DisplayName("groupId 非数字时关闭连接")
        void shouldCloseWhenGroupIdNotNumber() throws Exception {
            WebSocketSession session = mockSession(URI.create("ws://localhost/ws/chat?groupId=abc"));

            handler.afterConnectionEstablished(session);

            verify(sessionManager, never()).register(any(), any());
            verify(session).close(eq(CloseStatus.BAD_DATA.withReason("缺少 groupId 参数")));
        }

        @Test
        @DisplayName("URI 为 null 时关闭连接")
        void shouldCloseWhenUriIsNull() throws Exception {
            WebSocketSession session = mock(WebSocketSession.class);
            when(session.getUri()).thenReturn(null);

            handler.afterConnectionEstablished(session);

            verify(sessionManager, never()).register(any(), any());
            verify(session).close(eq(CloseStatus.BAD_DATA.withReason("缺少 groupId 参数")));
        }
    }

    @Nested
    @DisplayName("handleTextMessage 文本消息处理")
    class TextMessageHandling {

        @Test
        @DisplayName("已注册连接的消息转发给 dispatcher")
        void shouldDispatchMessageWhenRegistered() throws Exception {
            WebSocketSession session = mockSession(URI.create("ws://localhost/ws/chat?groupId=42"));
            when(session.getAttributes()).thenReturn(new HashMap<>());
            handler.afterConnectionEstablished(session);

            handler.handleTextMessage(session, new TextMessage("{\"type\":\"SEND_MESSAGE\"}"));

            verify(dispatcher).dispatch(eq(42L), eq(session), eq("{\"type\":\"SEND_MESSAGE\"}"));
        }

        @Test
        @DisplayName("attributes 无 groupId 时不转发（防御性）")
        void shouldNotDispatchWhenNoGroupIdInAttributes() throws Exception {
            WebSocketSession session = mock(WebSocketSession.class);
            // 不通过 afterConnectionEstablished 流程，直接构造无 groupId 的 attributes
            Map<String, Object> attrs = new HashMap<>();
            when(session.getAttributes()).thenReturn(attrs);

            handler.handleTextMessage(session, new TextMessage("{}"));

            verify(dispatcher, never()).dispatch(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("afterConnectionClosed 连接关闭")
    class ConnectionClosed {

        @Test
        @DisplayName("attributes 含 groupId 时调用 sessionManager.unregister")
        void shouldUnregisterWhenGroupIdPresent() {
            WebSocketSession session = mock(WebSocketSession.class);
            Map<String, Object> attrs = new HashMap<>();
            attrs.put("groupId", 42L);
            when(session.getAttributes()).thenReturn(attrs);

            handler.afterConnectionClosed(session, CloseStatus.NORMAL);

            verify(sessionManager).unregister(eq(42L), eq(session));
        }

        @Test
        @DisplayName("attributes 无 groupId 时跳过 unregister（防御性，不抛异常）")
        void shouldSkipUnregisterWhenNoGroupId() {
            WebSocketSession session = mock(WebSocketSession.class);
            when(session.getAttributes()).thenReturn(new HashMap<>());

            handler.afterConnectionClosed(session, CloseStatus.NORMAL);

            verify(sessionManager, never()).unregister(any(), any());
        }
    }
}
