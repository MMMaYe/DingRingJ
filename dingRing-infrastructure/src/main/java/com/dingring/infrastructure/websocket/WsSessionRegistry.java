package com.dingring.infrastructure.websocket;

import org.springframework.web.socket.WebSocketSession;

import java.util.Set;

/**
 * WebSocket 会话注册表端口（infrastructure 层技术契约）。
 * <p>拆分自原 {@code WsSessionManager}：纯 session 表的维护与查询契约，
 * 不含序列化与广播逻辑（由同包的 {@link WsBroadcastAdapter} 负责）。
 * <p>设计目的（Phase B 分层归位）：
 * <ul>
 *   <li>adapter 层的 {@code ChatWebSocketHandler} 调 {@link #register} / {@link #unregister}
 *       维护连接生命周期（入站协作）</li>
 *   <li>adapter 层的 {@code WsMessageDispatcher} 调 {@link #sendToSession} 做定向错误推送（入站协作）</li>
 *   <li>infrastructure 层的 {@link WsBroadcastAdapter} 调 {@link #lookup} 拿 sessions 再广播（出站协作）</li>
 * </ul>
 * <p>本端口引用 Spring WS 的 {@link WebSocketSession}，属 WS 技术细节，
 * 不放 domain 层（避免 domain 污染）。adapter 依赖 infrastructure 获取此能力。
 */
public interface WsSessionRegistry {

    /** 连接建立后注册到群 */
    void register(Long groupId, WebSocketSession session);

    /** 连接关闭后注销 */
    void unregister(Long groupId, WebSocketSession session);

    /**
     * 查询群内所有在线连接（供出站广播使用）。
     *
     * @return 该群的在线连接集合，群不存在或空时返回空 Set（不返回 null）
     */
    Set<WebSocketSession> lookup(Long groupId);

    /**
     * 向单个连接推送消息（定向错误提示等，不经过群广播路径）。
     * <p>序列化由实现层负责，调用方只传原始 type + data。
     */
    void sendToSession(WebSocketSession session, String type, Object data);
}
