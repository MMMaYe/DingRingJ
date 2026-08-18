package com.dingring.common.constant;

/**
 * 群聊协作标记（原 ContextBuilder 静态成员迁入）。
 * <p>Agent 发言中输出的协作协议标记，由编排层（ChatNode/DiscussNode/ConclusionService/StreamMarkerGuard）
 * 识别处理，入库/广播前必须剥离。
 */
public final class CollaborationMarkers {

    private CollaborationMarkers() {}

    /** Agent 自主收束标记：Agent 认为讨论可总结时在回复末尾输出，编排器检测到后触发结束流程 */
    public static final String CONCLUDE_MARKER = "[[CONCLUDE]]";

    /** Agent 跳过本轮标记：无新观点时只输出该标记，不入库不广播；连续 PASS 触发收敛收束 */
    public static final String PASS_MARKER = "[[PASS]]";

    /** Agent 让位给用户标记：主要观点已覆盖/需要用户参与时输出，引擎暂停讨论等用户发言（WAIT） */
    public static final String ASK_USER_MARKER = "[[ASK_USER]]";

    /** 剥离收束标记（消息入库/结论落库前调用） */
    public static String stripConcludeMarker(String content) {
        return content == null ? null : content.replace(CONCLUDE_MARKER, "").trim();
    }

    /** 剥离全部协作标记（CONCLUDE + PASS + ASK_USER） */
    public static String stripMarkers(String content) {
        return content == null ? null
                : content.replace(CONCLUDE_MARKER, "").replace(PASS_MARKER, "")
                        .replace(ASK_USER_MARKER, "").trim();
    }
}
