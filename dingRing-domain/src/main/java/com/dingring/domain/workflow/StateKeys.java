package com.dingring.domain.workflow;

/**
 * OverAllState 的 key 常量，集中管理避免拼写不一致。
 * <p>所有 NodeAction 读写 state 时必须引用此类，不硬编码字符串。
 * <p>KeyStrategy（在 SaaWorkflow 中定义）：
 * <ul>
 *   <li>ReplaceStrategy：单值替换（最新值覆盖旧值）</li>
 *   <li>AppendStrategy：列表追加（历史累积）</li>
 * </ul>
 */
public final class StateKeys {

    private StateKeys() {}

    // === 输入 ===
    public static final String GROUP_ID = "groupId";                    // 群 ID
    public static final String INPUT = "input";                         // 用户原始消息
    public static final String MENTIONED_AGENT_IDS = "mentionedAgentIds"; // @提及的 Agent ID 列表
    public static final String REPLIED_TO_AGENT_ID = "repliedToAgentId"; // 引用回复的 Agent ID

    // === 话题 ===
    public static final String TOPIC_ID = "topicId";                    // 当前话题 ID
    public static final String TOPIC_TITLE = "topicTitle";             // 当前话题标题
    public static final String RESTART_HINT = "restartHint";           // 话题重启提示（给前端展示）
    public static final String USER_HISTORY_HINT = "userHistoryHint";  // 用户历史表现提示（给 Agent，EnsureTopicNode 产出）

    // === 意图 ===
    public static final String INTENT = "intent";                      // CHAT/DISCUSS/CONCLUDE
    public static final String CONFIDENCE = "confidence";               // HIGH/LOW（仅 DISCUSS 有意义，追溯式建题依据）

    // === 讨论模式（方案 6.4 决策 2） ===
    public static final String DISCUSS_MODE = "discussMode";           // CONVERGE/DIVERGE/WAIT/CONCLUDE_PROPOSED/CONCLUDE
    public static final String DIVERGE_ROUNDS = "divergeRounds";       // 发散轮次计数（达 maxDivergeRounds 回拉）

    // === 收束 ===
    public static final String CONCLUDED = "concluded";                // 是否已收束
    public static final String CONCLUSION = "conclusion";              // 结论文本
    public static final String CONCLUDER_AGENT_ID = "concluderAgentId"; // 总结 Agent ID

    // === 触发信息 ===
    public static final String TRIGGERED_BY = "triggeredBy";            // USER/AGENT/MAX_ROUNDS/MODERATOR/CONVERGED/FAILED

    // === 讨论态运行时状态（由 DiscussionEngine 维护，每次 advance 通过 inputs 传入） ===
    public static final String PASSED_AGENT_IDS = "passedAgentIds";     // 本轮已 PASS 的 Agent ID 列表（有人发言即清空）
    public static final String MENTION_HANDLED = "mentionHandled";     // @提及的一次性发言权是否已消费（true 后不再豁免 PASS）
    public static final String LOW_DISCUSS_STREAK = "lowDiscussStreak";  // 连续低置信度 DISCUSS 计数（追溯式建题）
    public static final String SPEAKER_AGENT_ID = "speakerAgentId";     // 本轮实际发言 Agent ID（用于事件回放/调试）
    public static final String ENSURE_SUCCESS = "ensureSuccess";       // 建题是否成功（false 时条件边回退到 chat 节点）

    // === 闲聊态运行时状态 ===
    public static final String CHAT_BUFFER = "chatBuffer";             // 闲聊缓冲计数（达阈值触发画像提炼）
    public static final String NEED_PROFILE_EXTRACT = "needProfileExtract"; // 是否需触发画像提炼（条件边分流到 profile-extract）

    // === 消息历史（追加策略，累积各节点产出的消息 ID） ===
    public static final String MESSAGES = "messages";                  // List<Long>，按时间序累积

    // === 讨论模式枚举值 ===
    public static final String MODE_CONVERGE = "CONVERGE";             // 收敛：Agent 直接回答，无延迟
    public static final String MODE_DIVERGE = "DIVERGE";               // 发散：Agent 提新视角，轻量 pace
    public static final String MODE_WAIT = "WAIT";                     // 等待：Agent 追问用户，阻塞
    public static final String MODE_CONCLUDE_PROPOSED = "CONCLUDE_PROPOSED"; // 提议收束：等用户确认
    public static final String MODE_CONCLUDE = "CONCLUDE";             // 收束：用户确认/要求结束/熔断
}
