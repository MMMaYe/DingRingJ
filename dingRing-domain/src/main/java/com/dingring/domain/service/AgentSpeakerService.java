package com.dingring.domain.service;

import com.dingring.domain.agent.Agent;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Agent 发言服务端口（Phase D：ReactAgent + 工具调用 + Hook 干预）。
 * <p>infrastructure 层用 SAA ReactAgent 实现，替代 Phase C 直接调用 {@link LlmService#chat}。
 * <p>设计要点：
 * <ul>
 *   <li>systemPrompt 只含 Agent 人设 + 协作协议（基础部分），群记忆/用户画像/群成员名单由 Hook 动态注入</li>
 *   <li>context 携带 groupId/topicId/userId 等，传入 ReactAgent 初始 state 供 Hook 读取</li>
 *   <li>tools 按场景注入：闲聊=画像查询，讨论=知识检索，收束=历史主题，工作=通用工具集</li>
 *   <li>ReAct 循环上限由 recursionLimit 控制（讨论=3，工作=15）</li>
 * </ul>
 */
public interface AgentSpeakerService {

    /**
     * Agent 发言（带工具调用能力，非流式）。
     *
     * @param agent        领域 Agent 实体（LLM 配置）
     * @param systemPrompt 基础系统提示词（Agent 人设 + 协作协议）
     * @param messages     对话历史
     * @param toolSet      工具集（按场景注入）
     * @param context      上下文参数（groupId/topicId/userId 等，传入 ReactAgent 初始 state 供 Hook 读取）
     * @return Agent 发言结果
     */
    AgentResult call(Agent agent, String systemPrompt, List<LlmService.ChatTurn> messages,
                     ToolSet toolSet, Map<String, Object> context);

    /**
     * 流式发言：逐块回调 {@code onDelta}，返回拼接后的完整内容（落库语义与 {@link #call} 一致）。
     *
     * @param onDelta 逐块内容回调（原始 chunk，未做标记过滤）
     */
    AgentResult callStream(Agent agent, String systemPrompt, List<LlmService.ChatTurn> messages,
                           ToolSet toolSet, Map<String, Object> context, Consumer<String> onDelta);

    /**
     * Agent 发言结果。
     *
     * @param content        生成内容（可能为空字符串 = Agent 选择不发言）
     * @param hasToolCalls   是否触发了工具调用
     * @param toolCallSummary 工具调用摘要（工具名列表，供日志/事件用）
     */
    record AgentResult(String content, boolean hasToolCalls, List<String> toolCallSummary) {

        /** 便捷构造：无工具调用的简单结果 */
        public static AgentResult of(String content) {
            return new AgentResult(content, false, List.of());
        }
    }

    /**
     * 工具集枚举（按场景注入不同工具组合）。
     * <p>映射关系：
     * <ul>
     *   <li>CHAT：UserProfileQuery（闲聊时查询用户画像）</li>
     *   <li>DISCUSS：KnowledgeSearch + UserProfileQuery（讨论时检索知识库）</li>
     *   <li>CONCLUDE：TopicHistory（收束时查询历史主题结论）</li>
     *   <li>WORK：KnowledgeSearch + TopicHistory + UserProfileQuery（通用工具集）</li>
     * </ul>
     */
    enum ToolSet {
        CHAT,       // 闲聊：画像查询
        DISCUSS,    // 讨论：知识检索 + 画像查询
        CONCLUDE,   // 收束：历史主题查询
        WORK        // 工作：通用工具集
    }
}
