package com.dingring.infrastructure.websocket;

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
 * WebSocket 会话注册表实现：维护 groupId -> 在线连接集合。
 * <p>拆分自原 {@code WsSessionManager} 的会话表管理 + 序列化 + 定向推送职责。
 * <p>纯技术基础设施：{@link ConcurrentHashMap} 会话表、{@link ObjectMapper} 序列化、
 * {@code synchronized(session)} 并发写串行化，无任何协议翻译。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WsSessionRegistryImpl implements WsSessionRegistry {

    private final ObjectMapper objectMapper;

    /** groupId -> 该群的在线连接 */
    private final Map<Long, Set<WebSocketSession>> groupSessions = new ConcurrentHashMap<>();

    @Override
    public void register(Long groupId, WebSocketSession session) {
        groupSessions.computeIfAbsent(groupId, k -> new CopyOnWriteArraySet<>()).add(session);
        LogHelper.printLog(WsSessionRegistryImpl.class, "WsSessionRegistryImpl.register", "REGISTER", "连接注册",
                "groupId={} sessionId={} 在线数={}", groupId, session.getId(), groupSessions.get(groupId).size());
    }

    @Override
    public void unregister(Long groupId, WebSocketSession session) {
        Set<WebSocketSession> sessions = groupSessions.get(groupId);
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) {
                groupSessions.remove(groupId, sessions);
            }
        }
        LogHelper.printLog(WsSessionRegistryImpl.class, "WsSessionRegistryImpl.unregister", "UNREGISTER", "连接注销",
                "groupId={} sessionId={}", groupId, session.getId());
    }

    @Override
    public Set<WebSocketSession> lookup(Long groupId) {
        Set<WebSocketSession> sessions = groupSessions.get(groupId);
        return sessions == null ? Set.of() : sessions;
    }

    @Override
    public void sendToSession(WebSocketSession session, String type, Object data) {
        TextMessage message = encode(type, data);
        if (message != null) {
            sendSafely(session, message);
        }
    }

    /**
     * 序列化为 {@code {"type":..,"data":..}} JSON 文本帧。
     * <p>包级可见：供同包的 {@link WsBroadcastAdapter} 复用，避免广播路径重复序列化逻辑。
     */
    TextMessage encode(String type, Object data) {
        try {
            return new TextMessage(objectMapper.writeValueAsString(Map.of("type", type, "data", data)));
        } catch (Exception e) {
            LogHelper.printWarnLog(WsSessionRegistryImpl.class, "WsSessionRegistryImpl.encode", "ENCODE", "消息序列化失败",
                    "type={}", type, e);
            return null;
        }
    }

    /**
     * 单连接发送，{@code synchronized(session)} 串行化并发写避免 {@code TEXT_PARTIAL_WRITING}。
     * <p>包级可见：供 {@link WsBroadcastAdapter} 复用。
     */
    void sendSafely(WebSocketSession session, TextMessage message) {
        try {
            if (session.isOpen()) {
                synchronized (session) {
                    session.sendMessage(message);
                }
            }
        } catch (Exception e) {
            LogHelper.printWarnLog(WsSessionRegistryImpl.class, "WsSessionRegistryImpl.sendSafely", "SEND_SAFELY",
                    "消息发送失败", "sessionId={}", session.getId(), e);
        }
    }
}
