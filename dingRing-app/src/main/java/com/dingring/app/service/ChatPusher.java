package com.dingring.app.service;

/**
 * WebSocket 推送端口（app 层出站接口，adapter 层用 WsSessionManager 实现）。
 * <p>消息统一为 {type, data} JSON 结构（见技术方案 8. WebSocket 消息）。
 */
public interface ChatPusher {

    /**
     * 向群内所有在线连接广播消息。
     *
     * @param groupId 群 ID
     * @param type    消息类型（WsConstants）
     * @param data    payload（序列化为 JSON）
     */
    void pushToGroup(Long groupId, String type, Object data);
}
