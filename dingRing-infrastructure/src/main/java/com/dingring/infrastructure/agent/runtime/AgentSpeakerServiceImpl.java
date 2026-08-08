package com.dingring.infrastructure.agent.runtime;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.dingring.common.util.LogHelper;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.service.AgentSpeakerService;
import com.dingring.domain.service.LlmService.ChatTurn;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Agent 发言服务 SAA ReactAgent 实现（Phase D）。
 * <p>替代 Phase C 的 {@link com.dingring.domain.service.LlmService#chat} 直接调用，
 * Agent 现在拥有工具调用 + Hook 干预能力。
 * <p>消息转换：{@link ChatTurn} → Spring AI {@link Message}（USER→UserMessage，ASSISTANT→AssistantMessage）。
 * <p>context 传入 ReactAgent 初始 state，Hook 从 state 读取 groupId/topicId/userId 等参数。
 * <p>流式输出（callStream）：Phase D 暂回退非流式（ReactAgent.streamMessages 的 Flux 订阅在后续完善），
 * 落库语义与 call 一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentSpeakerServiceImpl implements AgentSpeakerService {

    private final SaaReactAgentFactory agentFactory;

    @Override
    public AgentResult call(Agent agent, String systemPrompt, List<ChatTurn> messages,
                            ToolSet toolSet, Map<String, Object> context) {
        ReactAgent reactAgent = agentFactory.buildDiscussAgent(agent, systemPrompt, toolSet);

        // 构建 ReactAgent 初始 state：messages + context（groupId/topicId/userId 供 Hook 读取）
        Map<String, Object> inputs = buildInputs(messages, context);

        LogHelper.printLog(AgentSpeakerServiceImpl.class, "AgentSpeakerServiceImpl.call", "AGENT_SPEAK",
                "Agent发言开始", "agent={} toolSet={} 消息数={} context={}",
                agent.getName(), toolSet, messages.size(), context.keySet());

        try {
            AssistantMessage response = reactAgent.call(inputs);
            String content = response.getText() != null ? response.getText() : "";
            boolean hasToolCalls = response.getToolCalls() != null && !response.getToolCalls().isEmpty();

            LogHelper.printLog(AgentSpeakerServiceImpl.class, "AgentSpeakerServiceImpl.call", "AGENT_SPEAK",
                    "Agent发言完成", "agent={} 内容长度={} hasToolCalls={}",
                    agent.getName(), content.length(), hasToolCalls);

            return new AgentResult(content, hasToolCalls, List.of());
        } catch (Exception e) {
            LogHelper.printWarnLog(AgentSpeakerServiceImpl.class, "AgentSpeakerServiceImpl.call", "AGENT_SPEAK",
                    "Agent发言失败", "agent={} 错误: {}", agent.getName(), e.getMessage());
            throw new RuntimeException("Agent 发言失败: " + agent.getName(), e);
        }
    }

    @Override
    public AgentResult callStream(Agent agent, String systemPrompt, List<ChatTurn> messages,
                                   ToolSet toolSet, Map<String, Object> context, Consumer<String> onDelta) {
        // Phase D：流式输出暂回退非流式（ReactAgent.streamMessages 的 Flux 订阅在后续完善）
        // 落库语义与 call 一致，onDelta 整段回调一次
        AgentResult result = call(agent, systemPrompt, messages, toolSet, context);
        if (onDelta != null && !result.content().isEmpty()) {
            onDelta.accept(result.content());
        }
        return result;
    }

    /**
     * 构建 ReactAgent 初始 state。
     * <p>messages 转为 Spring AI Message 列表，context 参数（groupId/topicId/userId/speakerAgentId）
     * 一并放入 state 供 Hook 读取。
     */
    private Map<String, Object> buildInputs(List<ChatTurn> messages, Map<String, Object> context) {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("messages", toSpringMessages(messages));
        if (context != null) {
            inputs.putAll(context);
        }
        return inputs;
    }

    /**
     * ChatTurn → Spring AI Message 转换。
     * <p>USER → UserMessage，ASSISTANT → AssistantMessage。
     */
    private List<Message> toSpringMessages(List<ChatTurn> turns) {
        List<Message> messages = new ArrayList<>(turns.size());
        for (ChatTurn turn : turns) {
            if ("ASSISTANT".equals(turn.role())) {
                messages.add(new AssistantMessage(turn.content()));
            } else {
                messages.add(new UserMessage(turn.content()));
            }
        }
        return messages;
    }
}
