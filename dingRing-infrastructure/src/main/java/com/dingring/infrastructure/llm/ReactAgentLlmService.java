package com.dingring.infrastructure.llm;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.dingring.common.exception.BizException;
import com.dingring.common.exception.ErrorCode;
import com.dingring.common.util.JsonHelper;
import com.dingring.common.util.LogHelper;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.service.LlmService;
import com.dingring.infrastructure.agent.hook.SystemMessageMergeHook;
import com.dingring.infrastructure.agent.interceptor.ModelRequestLoggingInterceptor;
import com.dingring.infrastructure.aop.Event;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 基于 SAA ReactAgent 的 {@link LlmService} 实现（Phase D 统一 LLM 调用入口）。
 * <p>替代 Phase C 的裸 {@code OpenAiChatModel} 直调（{@code SpringAiLlmService} 已废弃保留，
 * 默认不激活）：所有 LLM 调用统一经由 ReactAgent——本类构建无工具 ReactAgent 服务意图分类/摘要/重排等
 * 确定性任务，也构建带工具 ReactAgent 服务 Agent 发言，两条路径共享同一 Agent 运行时，无第二套 LLM 调用代码。
 * <p>参数覆盖（temperature/maxTokens/readTimeout/jsonMode）已由 {@link SaaLlmFactory#buildChatModel}
 * 装配进 model 的 defaultOptions；ReactAgent 未指定 chatOptions 时复用 model 默认 options，
 * 因此无需在 Agent 层重复处理。
 * <p>流式：SAA 1.1.2.3 的 ReactAgent 无公共流式入口，chatStream 统一回退非流式
 * （与无工具 chatStream 语义一致，整段回调 onDelta）。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "dingring.llm.mock", havingValue = "false", matchIfMissing = true)
public class ReactAgentLlmService implements LlmService {

    /** 统一 LLM runtime 构建工厂（模型 + 工具 Agent） */
    private final SaaLlmFactory llmFactory;
    /** 模型调用请求日志拦截器（观察用，与带工具路径共用同一实例） */
    private final ModelRequestLoggingInterceptor modelRequestLoggingInterceptor;

    public ReactAgentLlmService(@Lazy SaaLlmFactory llmFactory,
                                ModelRequestLoggingInterceptor modelRequestLoggingInterceptor) {
        this.llmFactory = llmFactory;
        this.modelRequestLoggingInterceptor = modelRequestLoggingInterceptor;
    }

    @Override
    public String chat(Agent agent, String systemPrompt, List<ChatTurn> messages) {
        return chat(agent, systemPrompt, messages, null);
    }

    @Event(eventCode = "CHAT_TO_LLM", eventName = "非流式调用LLM")
    @Override
    public String chat(Agent agent, String systemPrompt, List<ChatTurn> messages, CallOptions options) {
        long startAt = System.currentTimeMillis();
        try {
            // 统一入口：每次调用构建无工具 ReactAgent（无状态，参数覆盖由 SaaLlmFactory 装配进 model）
            ReactAgent reactAgent = buildAgent(agent, systemPrompt, options);
            List<Message> aiMessages = toAiMessages(messages);
            LogHelper.printLog(ReactAgentLlmService.class, "ReactAgentLlmService.chat", "CHAT_PROMPT", "Prompt",
                    "agent={} model={} ☆ systemPrompt:\n{}\nturns({}轮):\n{}",
                    agent.getName(), agent.getModelName(), systemPrompt,
                    aiMessages.size(), LogHelper.formatTurns(aiMessages));
            Map<String, Object> inputs = Map.of("messages", aiMessages);
            LogHelper.printLog(ReactAgentLlmService.class, "ReactAgentLlmService.chat", "CHAT_INPUTS", "输入",
                    "☆ reactAgent.call时的message={}", inputs);
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
                        "agent={} model={} ☆ 耗时={}ms ☆ 长度={} ☆ 完整内容:\n{}",
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
                    "LLM 调用失败: " + agent.getName() + " - " + e.getMessage(), e);
        }
    }

    @Override
//    @Event(eventCode = "AGENT_SPEAK", eventName = "Agent同事发言")
    public AgentResult chat(Agent agent, String systemPrompt, List<ChatTurn> messages,
                            ToolSet toolSet, Map<String, Object> context) {
        try {
            ReactAgent reactAgent = toolSet == ToolSet.WORK
                    ? llmFactory.buildWorkAgent(agent)
                    : llmFactory.buildDiscussAgent(agent, toolSet);
            Map<String, Object> inputs = buildAgentInputs(systemPrompt, messages, context);

            LogHelper.printLog(ReactAgentLlmService.class, "ReactAgentLlmService.chatAgent", "AGENT_SPEAK_INPUTS",
                    "Agent开始发言",
                    "agent={} toolSet={} 给LLM的消息={} 原始context={}",
                    agent.getName(), toolSet, JsonHelper.mapToJsonStr(inputs), JsonHelper.mapToJsonStr(context));

            AssistantMessage response = reactAgent.call(inputs);

            String content = response.getText() == null ? "" : response.getText();

            //打印content
            LogHelper.printLog(ReactAgentLlmService.class,
                    "ReactAgentLlmService.chatAgent",
                    "AGENT_SPEAK_CLOSED", "Agent发言完成", "content={}", content);

            boolean hasToolCalls = response.getToolCalls() != null && !response.getToolCalls().isEmpty();

            return new AgentResult(content, hasToolCalls, List.of());
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            LogHelper.printWarnLog(ReactAgentLlmService.class, "ReactAgentLlmService.chatAgent", "AGENT_SPEAK",
                    "Agent发言失败", "agent={} 错误: {}", agent.getName(), e.getMessage());
            throw new BizException(ErrorCode.LLM_API_ERROR,
                    "LLM 调用失败: " + agent.getName() + " - " + e.getMessage(), e);
        }
    }

    @Override
    @Event(eventCode = "AGENT_SPEAK", eventName = "流式Agent发言")
    public AgentResult chatStream(Agent agent, String systemPrompt, List<ChatTurn> messages,
                                  ToolSet toolSet, Map<String, Object> context, Consumer<String> onDelta) {
        AgentResult result = chat(agent, systemPrompt, messages, toolSet, context);
        if (onDelta != null && result.content() != null && !result.content().isEmpty()) {
            onDelta.accept(result.content());
        }
        return result;
    }

    @Event(eventCode = "AGENT_SPEAK_INPUTS", eventName = "构建Agent发言输入")
    private Map<String, Object> buildAgentInputs(String systemPrompt, List<ChatTurn> messages,
                                                  Map<String, Object> context) {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("messages", toAiMessages(messages));
        inputs.put(SystemMessageMergeHook.BASE_SYSTEM_PROMPT_KEY,
                systemPrompt == null ? "" : systemPrompt);
        if (context != null) {
            inputs.putAll(context);
        }
        return inputs;
    }

    @Override
    @Event(eventCode = "CHAT_TO_LLM", eventName = "流式调用LLM")
    public String chatStream(Agent agent, String systemPrompt, List<ChatTurn> messages, Consumer<String> onDelta) {
        // SAA ReactAgent 无公共流式 API，回退非流式；整段内容只回调一次。
        String text = chat(agent, systemPrompt, messages, null);
        if (onDelta != null && !text.isEmpty()) {
            onDelta.accept(text);
        }
        return text;
    }

    /** 带工具 Agent 的消息转换与无工具路径共用同一实现。 */

    /**
     * 构建无工具 ReactAgent。
     * <p>systemPrompt 由 builder 注入（模型调用时置顶为 SystemMessage，与 messages 分离），
     * 避免多 SystemMessage 干扰模型；参数覆盖（temperature/maxTokens/readTimeout/jsonMode）
     * 已在 {@link SaaLlmFactory#buildChatModel} 装配进 model 默认 options，ReactAgent 复用之。
     */
    private ReactAgent buildAgent(Agent agent, String systemPrompt, CallOptions options) {
        ChatModel chatModel = llmFactory.buildChatModel(agent, options);
        var builder = ReactAgent.builder()
                .name(agent.getName())
                .model(chatModel)
                // 模型调用请求日志拦截器：观察意图分类/摘要等无工具调用的请求结构
                .interceptors(modelRequestLoggingInterceptor);
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
