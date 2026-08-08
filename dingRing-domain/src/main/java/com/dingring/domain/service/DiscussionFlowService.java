package com.dingring.domain.service;

import com.dingring.domain.workflow.DiscussionFlowResult;
import com.dingring.domain.workflow.DiscussionRules;

import java.util.Map;

/**
 * 群聊流程服务端口。
 * <p>app 层的 DiscussionEngine 调用，infrastructure 层用 SAA StateGraph 实现。
 * <p>每次调用 advance() 推进流程到下一个停顿点（WAIT/CONCLUDE_PROPOSED/END），
 * DiscussionEngine 根据返回的 discussMode 决定阻塞等待还是继续推进。
 */
public interface DiscussionFlowService {

    /**
     * 推进群聊流程。
     *
     * @param rules  讨论业务规则（配置参数）
     * @param inputs 输入状态（groupId/userMessage/mentionedAgentIds/repliedToAgentId 等）
     * @return 流程执行结果（含 discussMode 和 state 快照）
     */
    DiscussionFlowResult advance(DiscussionRules rules, Map<String, Object> inputs);
}
