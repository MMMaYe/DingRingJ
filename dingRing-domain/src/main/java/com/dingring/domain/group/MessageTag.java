package com.dingring.domain.group;

/**
 * 消息标签（方案 6.3.6 消息上下文优化，简化版三类）。
 * <ul>
 *   <li>KEY：用户消息，原始内容保留</li>
 *   <li>NOISE：明显噪音（纯标点/emoji/极短内容或无实质观点的 Agent 发言），上下文构建时排除</li>
 *   <li>VIEWPOINT：有实质观点的 Agent 发言，经 LLM 生成 30 字以内观点摘要</li>
 * </ul>
 * <p>注：原方案中的 MARKER_* 标签因消息入库前已剥离 [[DIVERGE]]/[[ASK_USER]]/[[CONCLUDE]] 标记而无法识别，
 * 含标记的消息自然归为 VIEWPOINT 候选（经 LLM 判定）。详见 three-frameworks-fusion-plan-vs-current-status.md 差异 1。
 */
public enum MessageTag {
    KEY,
    NOISE,
    VIEWPOINT
}
