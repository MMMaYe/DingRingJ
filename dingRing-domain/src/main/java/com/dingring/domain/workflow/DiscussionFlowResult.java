package com.dingring.domain.workflow;

import java.util.Map;

/**
 * 群聊流程执行结果。
 * <p>每次 advance() 返回一次流程推进的结果，DiscussionEngine 根据其中的 discussMode
 * 决定后续行为（阻塞等待用户 / 继续推进 / 结束循环）。
 */
public record DiscussionFlowResult(
        boolean concluded,           // 是否已收束（true = 循环应退出）
        String discussMode,          // 讨论模式：CONVERGE/DIVERGE/WAIT/CONCLUDE_PROPOSED/CONCLUDE/null
        Map<String, Object> state     // 最终状态快照（topicId/topicTitle/restartHint 等）
) {

    /** 便利方法：从 state 取值，不存在返回默认 */
    @SuppressWarnings("unchecked")
    public <T> T stateValue(String key, T defaultValue) {
        Object val = state == null ? null : state.get(key);
        return val != null ? (T) val : defaultValue;
    }
}
