package com.dingring.domain.service;

import com.dingring.domain.agent.Agent;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 统一 LLM 调用端口。
 * <p>无工具 chat 用于意图分类、摘要、画像等确定性任务；带 ToolSet 的 chat 用于 Agent 发言与 ReAct 工作流。
 */
public interface LlmService {

    /** 无工具、无状态的单次对话补全。 */
    String chat(Agent agent, String systemPrompt, List<ChatTurn> messages);

    /** 带单次参数覆盖的无工具对话补全。 */
    default String chat(Agent agent, String systemPrompt, List<ChatTurn> messages, CallOptions options) {
        return chat(agent, systemPrompt, messages);
    }

    /** 带工具、Hook 和上下文的 Agentic 对话调用。 */
    default AgentResult chat(Agent agent, String systemPrompt, List<ChatTurn> messages,
                             ToolSet toolSet, Map<String, Object> context) {
        throw new UnsupportedOperationException("当前 LLM 实现不支持带工具 chat");
    }

    /** 无工具流式对话补全；不支持流式时允许整段回调。 */
    default String chatStream(Agent agent, String systemPrompt, List<ChatTurn> messages,
                              Consumer<String> onDelta) {
        return chat(agent, systemPrompt, messages);
    }

    /** 带工具 Agentic 流式调用；不支持流式时允许整段回调。 */
    default AgentResult chatStream(Agent agent, String systemPrompt, List<ChatTurn> messages,
                                   ToolSet toolSet, Map<String, Object> context,
                                   Consumer<String> onDelta) {
        throw new UnsupportedOperationException("当前 LLM 实现不支持带工具 chat");
    }

    /** 单条对话消息。 */
    record ChatTurn(String role, String content) {
        public static ChatTurn user(String content) {
            return new ChatTurn("USER", content);
        }

        public static ChatTurn assistant(String content) {
            return new ChatTurn("ASSISTANT", content);
        }
    }

    /** 单次无工具调用参数覆盖。 */
    record CallOptions(Double temperature, Integer maxTokens, Long readTimeoutSeconds,
                       Boolean jsonMode, Boolean logReasoning) {
        public CallOptions(Double temperature, Integer maxTokens, Long readTimeoutSeconds) {
            this(temperature, maxTokens, readTimeoutSeconds, false, false);
        }
    }

    /** 带工具调用的 Agent 输出。 */
    record AgentResult(String content, boolean hasToolCalls, List<String> toolCallSummary) {
        public static AgentResult of(String content) {
            return new AgentResult(content, false, List.of());
        }
    }

    /** 按场景注入的工具集。 */
    enum ToolSet {
        CHAT,
        DISCUSS,
        CONCLUDE,
        WORK
    }
}
