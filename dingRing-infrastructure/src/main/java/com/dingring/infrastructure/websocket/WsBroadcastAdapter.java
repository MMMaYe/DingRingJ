package com.dingring.infrastructure.websocket;

import com.dingring.domain.service.GroupBroadcastService;
import com.dingring.infrastructure.aop.Event;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Set;

/**
 * 群聊消息广播适配器：实现 domain 层 {@link GroupBroadcastService} 出站端口。
 * <p>拆分自原 {@code WsSessionManager} 的群广播职责。
 * <p>纯出站适配器：从 {@link WsSessionRegistry} 拿群内在线连接，序列化为 {@code {type, data}} 帧后逐个发送。
 * 与 {@link WsSessionRegistryImpl} 同在 infrastructure 层，复用其 {@code encode}/{@code sendSafely} 包级方法。
 * <p>与 {@code ReactAgentLlmService}（实现 {@code LlmService} 出站端口）对称：都是 domain 端口的 infrastructure 实现。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WsBroadcastAdapter implements GroupBroadcastService {

    private final WsSessionRegistry sessionRegistry;

    @Override
    @Event(eventCode = "SEND_MESSAGE_TO_GROUP", eventName = "向群发送消息")
    public void broadcast(Long groupId, String type, Object data) {
        Set<WebSocketSession> sessions = sessionRegistry.lookup(groupId);
        if (sessions.isEmpty()) {
            return;
        }
        // 复用 registry 的序列化逻辑，避免重复实现
        TextMessage message = ((WsSessionRegistryImpl) sessionRegistry).encode(type, data);
        if (message == null) {
            return;
        }
        for (WebSocketSession session : sessions) {
            ((WsSessionRegistryImpl) sessionRegistry).sendSafely(session, message);
        }
    }
}
