package com.dingring.infrastructure.llm;

import com.dingring.common.exception.BizException;
import com.dingring.common.exception.ErrorCode;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.service.LlmService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 Spring AI OpenAI 兼容接口的 LLM 适配（DeepSeek / Kimi / GLM 等均走此实现）。
 * <p>按 Agent 配置（baseUrl/apiKey/model）动态构建 ChatModel，每个 Agent 可指向不同厂商。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "dingring.llm.mock", havingValue = "false", matchIfMissing = true)
public class SpringAiLlmService implements LlmService {

    @Override
    public String chat(Agent agent, String systemPrompt, List<ChatTurn> messages) {
        try {
            OpenAiChatModel chatModel = buildChatModel(agent);
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
            ChatResponse response = chatModel.call(new Prompt(aiMessages));
            String text = response.getResult().getOutput().getText();
            return text == null ? "" : text.trim();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("LLM 调用失败, agent={}, model={}", agent.getName(), agent.getModelName(), e);
            throw new BizException(ErrorCode.LLM_API_ERROR,
                    "LLM 调用失败: " + agent.getName() + " - " + e.getMessage());
        }
    }

    private OpenAiChatModel buildChatModel(Agent agent) {
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(agent.getBaseUrl())
                .apiKey(agent.getApiKey())
                .build();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(agent.getModelName())
                .temperature(agent.temperature())
                .maxTokens(agent.maxTokens())
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .build();
    }
}
