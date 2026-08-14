package com.dingring.domain.workflow;

/**
 * 群聊讨论业务规则（强类型配置，替代散落的 @Value 注入）。
 * <p>pace 机制改为"收敛+发散"模型（方案 6.4 决策 2）：
 * <ul>
 *   <li>收敛模式(CONVERGE)：Agent 直接回答用户问题；讨论态下引擎在 pace 窗口后自主推进下一轮，直至 Agent 输出 [[ASK_USER]] 让位或达 maxAutoRounds</li>
 *   <li>发散模式(DIVERGE)：Agent 提出关联新视角，轻量 pace 防刷屏，maxDivergeRounds 后自动回拉收敛</li>
 *   <li>等待模式(WAIT)：Agent 通过 [[ASK_USER]] 让位给用户，阻塞等用户发言</li>
 * </ul>
 * 收束只由显式信号触发（CONCLUDE 提议/用户要求/熔断），不再用 convergePassCount（决策 4）。
 */
public record DiscussionRules(
        long divergePaceMs,           // 自主推进发言间隔（轻量防刷屏，如 2000ms；用户可在窗口内插话）
        int maxAutoRounds,            // 每轮用户消息后 CONVERGE 自主推进兜底上限（LLM [[ASK_USER]] 让位为主，此为防永不让位的兜底，如 5）
        int maxDivergeRounds,         // 发散最大轮次（自动回拉收敛，如 3）
        int maxRounds,                // 总熔断轮次（Agent 发言总数上限）
        int profileExtractThreshold,  // 画像提炼触发阈值
        int backfillLimit,             // 建题回填消息上限
        int contextWindow,            // 上下文窗口
        long concludeConfirmTimeoutMs  // 提议收束后等用户确认的超时（如 300000=5分钟，超时自动收束）
) {}
