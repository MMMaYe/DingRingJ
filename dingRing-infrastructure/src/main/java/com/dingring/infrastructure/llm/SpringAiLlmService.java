package com.dingring.infrastructure.llm;

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
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 基于 Spring AI OpenAI 兼容接口的 LLM 适配（DeepSeek / Kimi / GLM 等均走此实现）。
 * <p><b>已废弃（Phase D 统一 LLM 调用入口）：</b>由 {@link ReactAgentLlmService} 替代，
 * 所有 LLM 调用统一经由 SAA ReactAgent，避免同时维护两套 LLM 调用代码。
 * 本类保留仅供回退参考，默认不注册为 Bean，需显式配置 {@code dingring.llm.legacy-spring-ai=true} 才启用
 * （该实现仍保留真流式 {@link #chatStream}，ReactAgent 路径在 SAA 升级前暂回退非流式）。
 * <p>Phase A 改造：模型构建逻辑提取到 {@link SaaLlmFactory}，本类只管调用与日志。
 * <p>按 Agent 配置（baseUrl/apiKey/model）动态构建 ChatModel，每个 Agent 可指向不同厂商。
 * <p>必须设置读超时：默认 RestClient 无超时，网关偶发挂起会永久卡死对话引擎线程。
 */
@Deprecated(since = "Phase D", forRemoval = false)
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "dingring.llm.legacy-spring-ai", havingValue = "true")
public class SpringAiLlmService implements LlmService {

    /** 模型构建工厂（Phase A 提取，Phase D ReactAgentFactory 复用） */
    private final SaaLlmFactory llmFactory;

    @Override
    public String chat(Agent agent, String systemPrompt, List<ChatTurn> messages) {
        return chat(agent, systemPrompt, messages, null);
    }

    @Event(eventCode = "CHAT_TO_LLM", eventName = "非流式调用LLM")
    @Override
    public String chat(Agent agent, String systemPrompt, List<ChatTurn> messages, CallOptions options) {
        long startAt = System.currentTimeMillis();
        try {
            OpenAiChatModel chatModel = llmFactory.buildChatModel(agent, options);
            List<Message> aiMessages = toAiMessages(systemPrompt, messages);
            LogHelper.printLog(SpringAiLlmService.class, "SpringAiLlmService.chat", "CHAT_PROMPT", "Prompt",
                    "agent={} model={}\nsystemPrompt:\n{}\nturns({}轮):\n{}",
                    agent.getName(), agent.getModelName(), systemPrompt,
                    aiMessages.size(), LogHelper.formatTurns(aiMessages));
            ChatResponse response = chatModel.call(new Prompt(aiMessages));
            String text = response.getResult().getOutput().getText();
            long cost = System.currentTimeMillis() - startAt;
            // 推理过程日志：Spring AI 1.0.0 GA 的 AssistantMessage 未原生暴露 reasoningContent，
            // 尝试从 metadata 中提取（部分厂商 SDK 可能塞入）；未来版本支持后可直接 getReasoningContent()
            if (options != null && Boolean.TRUE.equals(options.logReasoning())) {
                String reasoning = extractReasoning(response);
                if (reasoning != null && !reasoning.isBlank()) {
                    LogHelper.printLog(SpringAiLlmService.class, "SpringAiLlmService.chat",
                            "CHAT_REASONING", "推理过程",
                            "agent={} model={}\n推理内容:\n{}",
                            agent.getName(), agent.getModelName(), reasoning);
                }
            }
            if (text == null || text.isBlank()) {
                LogHelper.printWarnLog(SpringAiLlmService.class, "SpringAiLlmService.chat", "CHAT_EMPTY_RESPONSE", "空响应",
                        "agent={} model={} 耗时={}ms", agent.getName(), agent.getModelName(), cost);
            } else {
                LogHelper.printLog(SpringAiLlmService.class, "SpringAiLlmService.chat", "CHAT_RESPONSE", "响应",
                        "agent={} model={} 耗时={}ms 长度={} 完整内容:\n{}",
                        agent.getName(), agent.getModelName(), cost, text.length(), text);
            }
            return text == null ? "" : text.trim();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            LogHelper.printWarnLog(SpringAiLlmService.class, "SpringAiLlmService.chat", "CHAT_ERROR", "异常",
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
        long startAt = System.currentTimeMillis();
        try {
            OpenAiChatModel chatModel = llmFactory.buildChatModel(agent, null);
            List<Message> aiMessages = toAiMessages(systemPrompt, messages);
            LogHelper.printLog(SpringAiLlmService.class, "SpringAiLlmService.chatStream", "STREAM_PROMPT", "Prompt",
                    "agent={} model={}\nsystemPrompt:\n{}\nturns({}轮):\n{}",
                    agent.getName(), agent.getModelName(), systemPrompt,
                    aiMessages.size(), LogHelper.formatTurns(aiMessages));
            StringBuilder full = new StringBuilder();
            // 块间超时复用读超时配置：网关挂起时 Flux 报错退出，不永久卡死引擎线程
            chatModel.stream(new Prompt(aiMessages))
                    .timeout(Duration.ofSeconds(llmFactory.getReadTimeoutSeconds()))
                    .toIterable()
                    .forEach(response -> {
                        String delta = response.getResult() == null || response.getResult().getOutput() == null
                                ? null : response.getResult().getOutput().getText();
                        if (delta != null && !delta.isEmpty()) {
                            full.append(delta);
                            onDelta.accept(delta);
                        }
                    });
            String text = full.toString();
            long cost = System.currentTimeMillis() - startAt;
            if (text.isBlank()) {
                LogHelper.printWarnLog(SpringAiLlmService.class, "SpringAiLlmService.chatStream", "STREAM_EMPTY_RESPONSE", "空响应",
                        "agent={} model={} 耗时={}ms (流式)", agent.getName(), agent.getModelName(), cost);
            } else {
                LogHelper.printLog(SpringAiLlmService.class, "SpringAiLlmService.chatStream", "STREAM_RESPONSE", "响应",
                        "agent={} model={} 耗时={}ms 长度={} (流式) 完整内容:\n{}",
                        agent.getName(), agent.getModelName(), cost, text.length(), text);
            }
            return text.trim();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            LogHelper.printWarnLog(SpringAiLlmService.class, "SpringAiLlmService.chatStream", "STREAM_ERROR", "异常",
                    "agent={} model={} 耗时={}ms (流式) error={}",
                    e, agent.getName(), agent.getModelName(),
                    System.currentTimeMillis() - startAt, e.getMessage());
            throw new BizException(ErrorCode.LLM_API_ERROR,
                    "LLM 流式调用失败: " + agent.getName() + " - " + e.getMessage());
        }
    }

    /**
     * 尝试从 ChatResponse 中提取推理过程内容（reasoning_content）。
     * <p>Spring AI 1.0.0 GA 的 AssistantMessage 未原生暴露此字段，
     * 先从 metadata 里找常见 key（reasoning_content / reasoning），
     * 未来版本支持后可直接调用 getReasoningContent()。
     */
    private String extractReasoning(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return null;
        }
        java.util.Map<String, Object> metadata = response.getResult().getOutput().getMetadata();
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        // 常见 key：reasoning_content（火山方舟/智谱）、reasoning（OpenAI o1 系列）
        for (String key : new String[]{"reasoning_content", "reasoning"}) {
            Object val = metadata.get(key);
            if (val instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }

    private List<Message> toAiMessages(String systemPrompt, List<ChatTurn> messages) {
        List<Message> aiMessages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            aiMessages.add(new SystemMessage(systemPrompt));
        }
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
