package com.dingring.domain.service;

import com.dingring.domain.agent.Agent;
import com.dingring.domain.service.LlmService.ChatTurn;

import java.util.List;

/**
 * 群上下文记忆服务（依赖倒置，infrastructure 层实现）。
 * <p>承接原 ContextBuilder + SimpleMemoryService 的全部上下文组装逻辑，按意图三方法分流，
 * 由 {@code GroupContextMemoryHook} 在每次 Agent 运行前调用一次（见 docs/group-context-memory-hook-design.md）。
 * <p>规则要点：
 * <ul>
 *   <li>闲聊（CHAT）：最近 N 条闲聊消息记忆段（排除当前输入），无历史轮次</li>
 *   <li>讨论（DISCUSS）：当前主题观点摘要段（viewpoint 为 null 直接丢弃，不回退原文）+ 近期原文窗口轮次</li>
 *   <li>收束（CONCLUDE）：观点清单（KEY 用原文，VIEWPOINT 无摘要时回退原文）+ 全量原文窗口轮次</li>
 * </ul>
 */
public interface GroupContextMemoryService {

    /**
     * 构建闲聊上下文：Agent 人设 + chat-base 模板 + 最近 N 条闲聊记忆段。
     *
     * @param speaker 发言 Agent
     * @param groupId 群 ID
     * @return 完整系统提示词；turns 恒为空（当前输入由节点作为兜底 USER 轮传入）
     */
    AgentPromptContext buildChatContext(Agent speaker, Long groupId);

    /**
     * 构建讨论上下文（方案 6.3.6）：人设 + 协作协议 + 观点摘要段（viewpoint 非空）+ 进度引导
     * + 用户历史表现提示 + 近期原文窗口轮次。
     *
     * @param speaker         发言 Agent
     * @param groupId         群 ID
     * @param topicId         当前主题 ID
     * @param userHistoryHint 话题重启的用户历史表现提示（无则传 null/空）
     */
    AgentPromptContext buildDiscussContext(Agent speaker, Long groupId, Long topicId,
                                           String userHistoryHint);

    /**
     * 构建收束上下文：收束人设 + conclude 模板 + 观点清单（KEY 原文 / VIEWPOINT 摘要，无摘要回退原文，
     * 保证话题最终产出的完整性）+ 全量原文窗口轮次。
     *
     * @param concluder   收束 Agent
     * @param topicId     主题 ID
     * @param topicTitle  主题标题（conclude 模板参数）
     */
    AgentPromptContext buildConclusionContext(Agent concluder, Long topicId, String topicTitle);

    /** 组装结果：完整系统提示词 + 对话轮次（轮次可为空，如闲聊场景） */
    record AgentPromptContext(String systemPrompt, List<ChatTurn> turns) {}
}
