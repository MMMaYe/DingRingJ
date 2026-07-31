package com.dingring.adapter.websocket;

import com.dingring.app.service.ChatPusher;
import com.dingring.common.util.LogHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * WebSocket 会话管理器：维护 groupId -> 在线连接集合，实现 app 层 ChatPusher 出站端口。
 * <p>消息统一序列化为 {"type": ..., "data": ...}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WsSessionManager implements ChatPusher {

    private final ObjectMapper objectMapper;

    /** groupId -> 该群的在线连接 */
    private final Map<Long, Set<WebSocketSession>> groupSessions = new ConcurrentHashMap<>();

    /** 连接建立后注册到群 */
    public void register(Long groupId, WebSocketSession session) {
        groupSessions.computeIfAbsent(groupId, k -> new CopyOnWriteArraySet<>()).add(session);
        LogHelper.printLog(log, "WsSessionManager.register", "连接注册",
                "groupId=%d sessionId=%s 在线数=%d", groupId, session.getId(), groupSessions.get(groupId).size());
    }

    /** 连接关闭后注销 */
    public void unregister(Long groupId, WebSocketSession session) {
        Set<WebSocketSession> sessions = groupSessions.get(groupId);
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) {
                groupSessions.remove(groupId, sessions);
            }
        }
        LogHelper.printLog(log, "WsSessionManager.unregister", "连接注销", "groupId=%d sessionId=%s", groupId, session.getId());
    }

    @Override
    public void pushToGroup(Long groupId, String type, Object data) {
        Set<WebSocketSession> sessions = groupSessions.get(groupId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        TextMessage message = encode(type, data);
        if (message == null) {
            return;
        }
        for (WebSocketSession session : sessions) {
            sendSafely(session, message);
        }
    }

    /** 向单个连接推送（错误提示等定向消息） */
    public void pushToSession(WebSocketSession session, String type, Object data) {
        TextMessage message = encode(type, data);
        if (message != null) {
            sendSafely(session, message);
        }
    }

    private TextMessage encode(String type, Object data) {
        try {
            return new TextMessage(objectMapper.writeValueAsString(Map.of("type", type, "data", data)));
        } catch (Exception e) {
            LogHelper.printWarnLog(log, "WsSessionManager.encode", "消息序列化失败", "type=" + type, e);
            return null;
        }
    }

    private void sendSafely(WebSocketSession session, TextMessage message) {
        try {
            if (session.isOpen()) {
                // 同一 session 上的并发写需要串行化
                synchronized (session) {
                    session.sendMessage(message);
                }
            }
        } catch (Exception e) {
            LogHelper.printWarnLog(log, "WsSessionManager.sendSafely", "消息发送失败", "sessionId=" + session.getId(), e);
        }
    }
}
