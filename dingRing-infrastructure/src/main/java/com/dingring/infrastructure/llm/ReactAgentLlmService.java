package com.dingring.infrastructure.llm;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.dingring.common.exception.BizException;
import com.dingring.common.exception.ErrorCode;
import com.dingring.common.util.LogHelper;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.service.LlmService;
import com.dingring.infrastructure.aop.Event;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 基于 SAA ReactAgent 的 {@link LlmService} 实现（Phase D 统一 LLM 调用入口）。
 * <p>替代 Phase C 的裸 {@code OpenAiChatModel} 直调（{@code SpringAiLlmService} 已废弃保留，
 * 默认不激活）：所有 LLM 调用统一经由 ReactAgent——本类构建「无工具 ReactAgent」服务意图分类/摘要/重排等
 * 确定性任务，发言场景由 {@link com.dingring.infrastructure.agent.runtime.AgentSpeakerServiceImpl}
 * 构建「带工具 ReactAgent」，两条路径共享同一 Agent 运行时，无第二套 LLM 调用代码。
 * <p>参数覆盖（temperature/maxTokens/readTimeout/jsonMode）已由 {@link SaaModelFactory#buildChatModel}
 * 装配进 model 的 defaultOptions；ReactAgent 未指定 chatOptions 时复用 model 默认 options，
 * 因此无需在 Agent 层重复处理。
 * <p>流式：SAA 1.1.2.3 的 ReactAgent 无公共流式入口，chatStream 统一回退非流式
 * （与 {@code AgentSpeakerServiceImpl.callStream} 语义一致，整段回调 onDelta）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "dingring.llm.mock", havingValue = "false", matchIfMissing = true)
public class ReactAgentLlmService implements LlmService {

    /** 模型构建工厂（参数覆盖 + 超时在此装配进 ChatModel defaultOptions） */
    private final SaaModelFactory modelFactory;

    @Override
    public String chat(Agent agent, String systemPrompt, List<ChatTurn> messages) {
        return chat(agent, systemPrompt, messages, null);
    }

    @Event(eventCode = "CHAT_TO_LLM", eventName = "非流式调用LLM")
    @Override
    public String chat(Agent agent, String systemPrompt, List<ChatTurn> messages, CallOptions options) {
        long startAt = System.currentTimeMillis();
        try {
            // 统一入口：每次调用构建无工具 ReactAgent（无状态，参数覆盖由 SaaModelFactory 装配进 model）
            ReactAgent reactAgent = buildAgent(agent, systemPrompt, options);
            List<Message> aiMessages = toAiMessages(messages);
            LogHelper.printLog(ReactAgentLlmService.class, "ReactAgentLlmService.chat", "CHAT_PROMPT", "Prompt",
                    "agent={} model={}\nsystemPrompt:\n{}\nturns({}轮):\n{}",
                    agent.getName(), agent.getModelName(), systemPrompt,
                    aiMessages.size(), LogHelper.formatTurns(aiMessages));
            Map<String, Object> inputs = Map.of("messages", aiMessages);
            LogHelper.printLog(ReactAgentLlmService.class, "ReactAgentLlmService.chat", "CHAT_INPUTS", "输入",
                    "reactAgent.call时的message={}", inputs);
            AssistantMessage response = reactAgent.call(inputs);
            String text = response.getText();
            long cost = System.currentTimeMillis() - startAt;
            // 推理过程日志：ReactAgent 的 AssistantMessage 将 reasoning_content 放入 metadata，
            // 从 metadata 中提取（部分厂商 SDK 可能塞入；未来版本支持后可直接 getReasoningContent()）
            if (options != null && Boolean.TRUE.equals(options.logReasoning())) {
                String reasoning = extractReasoning(response);
                if (reasoning != null && !reasoning.isBlank()) {
                    LogHelper.printLog(ReactAgentLlmService.class, "ReactAgentLlmService.chat",
                            "CHAT_REASONING", "推理过程",
                            "agent={} model={}\n推理内容:\n{}",
                            agent.getName(), agent.getModelName(), reasoning);
                }
            }
            if (text == null || text.isBlank()) {
                LogHelper.printWarnLog(ReactAgentLlmService.class, "ReactAgentLlmService.chat", "CHAT_EMPTY_RESPONSE", "空响应",
                        "agent={} model={} 耗时={}ms", agent.getName(), agent.getModelName(), cost);
            } else {
                LogHelper.printLog(ReactAgentLlmService.class, "ReactAgentLlmService.chat", "CHAT_RESPONSE", "响应",
                        "agent={} model={} 耗时={}ms 长度={} 完整内容:\n{}",
                        agent.getName(), agent.getModelName(), cost, text.length(), text);
            }
            return text == null ? "" : text.trim();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            LogHelper.printWarnLog(ReactAgentLlmService.class, "ReactAgentLlmService.chat", "CHAT_ERROR", "异常",
                    "agent={} model={} 耗时={}ms error={}",
                    e, agent.getName(), agent.getModelName(),
                    System.currentTimeMillis() - startAt, e.getMessage());
            throw new BizException(ErrorCode.LLM_API_ERROR,
                    "LLM 调用失败: " + agent.getName() + " - " + e.getMessage());
        }
    }

    @Override
    @Event(eventCode = "CHAT_TO_LLM", eventName = "流式调用LLM")
    public String chatStream(Agent agent, String systemPrompt, List<ChatTurn> messages, Consumer<String> onDelta) {
        // 统一入口：SAA 1.1.2.3 ReactAgent 无公共流式 API，回退非流式（与 AgentSpeakerServiceImpl.callStream 一致）
        String text = chat(agent, systemPrompt, messages, null);
        if (onDelta != null && !text.isEmpty()) {
            onDelta.accept(text);
        }
        return text;
    }

    /**
     * 构建无工具 ReactAgent。
     * <p>systemPrompt 由 builder 注入（模型调用时置顶为 SystemMessage，与 messages 分离），
     * 避免多 SystemMessage 干扰模型；参数覆盖（temperature/maxTokens/readTimeout/jsonMode）
     * 已在 {@link SaaModelFactory#buildChatModel} 装配进 model 默认 options，ReactAgent 复用之。
     */
    private ReactAgent buildAgent(Agent agent, String systemPrompt, CallOptions options) {
        ChatModel chatModel = modelFactory.buildChatModel(agent, options);
        var builder = ReactAgent.builder()
                .name(agent.getName())
                .model(chatModel);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            builder.systemPrompt(systemPrompt);
        }
        return builder.build();
    }

    /**
     * 从 AssistantMessage 的 metadata 中提取推理过程内容（reasoning_content）。
     * <p>常见 key：reasoning_content（火山方舟/智谱）、reasoning（OpenAI o1 系列）。
     */
    private String extractReasoning(AssistantMessage message) {
        if (message == null || message.getMetadata() == null || message.getMetadata().isEmpty()) {
            return null;
        }
        for (String key : new String[]{"reasoning_content", "reasoning"}) {
            Object val = message.getMetadata().get(key);
            if (val instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }

    /**
     * ChatTurn → Spring AI Message 转换。
     * <p>不含 SystemMessage：systemPrompt 由 ReactAgent builder 注入。
     */
    private List<Message> toAiMessages(List<ChatTurn> messages) {
        List<Message> aiMessages = new ArrayList<>();
        for (ChatTurn turn : messages) {
            if ("ASSISTANT".equalsIgnoreCase(turn.role())) {
                aiMessages.add(new AssistantMessage(turn.content()));
            } else {
                aiMessages.add(new UserMessage(turn.content()));
            }
        }
        return aiMessages;
    }
}
