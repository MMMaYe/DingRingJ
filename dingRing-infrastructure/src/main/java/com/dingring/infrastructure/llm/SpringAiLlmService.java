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

import java.net.URI;
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
        UrlParts parts = resolveUrl(agent.getBaseUrl());
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(parts.baseUrl())
                .completionsPath(parts.completionsPath())
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

    /**
     * 解析 Agent 配置的 baseUrl，兼容带自定义路径前缀的网关。
     * <p>Spring AI 默认在 baseUrl 后拼 {@code /v1/chat/completions}，导致如
     * {@code https://api.stepfun.com/step_plan/v1}、{@code https://xxx.tcloudbasegateway.com/v1/ai/cloudbase}
     * 这类带路径的端点被拼成双重版本号而 404。
     * <ul>
     *   <li>baseUrl 不含路径（如 https://api.deepseek.com）：保持 Spring AI 默认 {@code /v1/chat/completions}</li>
     *   <li>baseUrl 含路径：以该路径为版本化前缀，拼 {@code /chat/completions}</li>
     *   <li>baseUrl 已以 {@code /chat/completions} 结尾：直接作为完整调用路径</li>
     * </ul>
     */
    static UrlParts resolveUrl(String rawBaseUrl) {
        String raw = rawBaseUrl == null ? "" : rawBaseUrl.trim();
        while (raw.endsWith("/")) {
            raw = raw.substring(0, raw.length() - 1);
        }
        URI uri = URI.create(raw);
        String origin = uri.getScheme() + "://" + uri.getRawAuthority();
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        if (path.isEmpty()) {
            // 无路径：沿用 OpenAI 默认约定
            return new UrlParts(origin, "/v1/chat/completions");
        }
        String completionsPath = path.endsWith("/chat/completions") ? path : path + "/chat/completions";
        return new UrlParts(origin, completionsPath);
    }

    /** baseUrl（仅 scheme+host）与 completions 路径的拆分结果 */
    record UrlParts(String baseUrl, String completionsPath) {}
}
