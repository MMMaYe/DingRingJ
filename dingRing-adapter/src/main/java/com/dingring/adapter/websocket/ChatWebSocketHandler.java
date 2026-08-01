package com.dingring.adapter.websocket;

import com.dingring.common.util.JsonHelper;
import com.dingring.common.util.LogHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 群聊 WebSocket 处理器。连接地址：ws://host/ws/chat?groupId={id}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final String ATTR_GROUP_ID = "groupId";

    private final WsSessionManager sessionManager;
    private final WsMessageDispatcher dispatcher;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Long groupId = parseGroupId(session);
        if (groupId == null) {
            LogHelper.printWarnLog(ChatWebSocketHandler.class, "ChatWebSocketHandler.afterConnectionEstablished", "AFTER_CONNECTION_ESTABLISHED", "连接缺少groupId参数关闭", "uri={}", session.getUri());
            session.close(CloseStatus.BAD_DATA.withReason("缺少 groupId 参数"));
            return;
        }
        session.getAttributes().put(ATTR_GROUP_ID, groupId);
        sessionManager.register(groupId, session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Long groupId = (Long) session.getAttributes().get(ATTR_GROUP_ID);
        LogHelper.printLog(ChatWebSocketHandler.class,
                "ChatWebSocketHandler.handleTextMessage",
                "HANDLE_TEXT_MESSAGE",
                "处理消息", "message={}", JsonHelper.toJson(message));
        if (groupId != null) {
            dispatcher.dispatch(groupId, session, message.getPayload());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long groupId = (Long) session.getAttributes().get(ATTR_GROUP_ID);
        if (groupId != null) {
            sessionManager.unregister(groupId, session);
        }
    }

    private Long parseGroupId(WebSocketSession session) {
        if (session.getUri() == null) {
            return null;
        }
        String value = UriComponentsBuilder.fromUri(session.getUri())
                .build().getQueryParams().getFirst(ATTR_GROUP_ID);
        try {
            return value == null ? null : Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
