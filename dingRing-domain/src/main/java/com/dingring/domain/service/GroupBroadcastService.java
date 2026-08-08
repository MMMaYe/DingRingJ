package com.dingring.domain.service;

/**
 * 群聊消息广播端口（domain 层出站接口）。
 * <p>统一所有 WS 推送逻辑，替代原 app 层 {@code ChatPusher} 散点调用。
 * <p>设计目的（Phase B）：
 * <ul>
 *   <li>端口下沉到 domain 层，app 层（DiscussionEngine/ChatOrchestrator）与
 *       infrastructure 层（Phase C 的 StateGraph 节点）均可依赖，不违反分层</li>
 *   <li>adapter 层 {@code WsSessionManager} 实现此接口，完成端口-适配器闭环</li>
 *   <li>消息统一为 {@code {type, data}} JSON 结构（见 WsConstants）</li>
 * </ul>
 */
public interface GroupBroadcastService {

    /**
     * 向群内所有在线连接广播消息。
     *
     * @param groupId 群 ID
     * @param type    消息类型（WsConstants）
     * @param data    payload（序列化为 JSON）
     */
    void broadcast(Long groupId, String type, Object data);
}
