package com.dingring.common.constant;

/**
 * WebSocket 消息类型常量（见技术方案 8. WebSocket 消息 JSON 结构）。
 */
public final class WsConstants {

    private WsConstants() {
    }

    /* ==================== Client -> Server ==================== */
    public static final String SEND_MESSAGE = "SEND_MESSAGE";
    public static final String REPLY_MESSAGE = "REPLY_MESSAGE";
    public static final String CREATE_TOPIC = "CREATE_TOPIC";
    public static final String CONCLUDE_TOPIC = "CONCLUDE_TOPIC";

    /* ==================== Server -> Client ==================== */
    public static final String NEW_MESSAGE = "NEW_MESSAGE";
    public static final String AGENT_TYPING = "AGENT_TYPING";
    /** 流式发言逐块推送 {streamId, agentId, agentName, delta} */
    public static final String MESSAGE_DELTA = "MESSAGE_DELTA";
    /** 流式发言完成 {streamId, message}，message 为落库后的正式消息体 */
    public static final String MESSAGE_COMPLETE = "MESSAGE_COMPLETE";
    /** 流式发言中途废弃 {streamId}，前端丢弃半成品气泡 */
    public static final String MESSAGE_ABORT = "MESSAGE_ABORT";
    public static final String TOPIC_STATUS_CHANGED = "TOPIC_STATUS_CHANGED";
    public static final String TOPIC_CREATED = "TOPIC_CREATED";
    public static final String TOPIC_CLOSED = "TOPIC_CLOSED";
    public static final String CARD_GENERATED = "CARD_GENERATED";
    public static final String ERROR = "ERROR";
}
