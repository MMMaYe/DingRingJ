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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 基于 Spring AI OpenAI 兼容接口的 LLM 适配（DeepSeek / Kimi / GLM 等均走此实现）。
 * <p>按 Agent 配置（baseUrl/apiKey/model）动态构建 ChatModel，每个 Agent 可指向不同厂商。
 * <p>必须设置读超时：默认 RestClient 无超时，网关偶发挂起会永久卡死对话引擎线程。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "dingring.llm.mock", havingValue = "false", matchIfMissing = true)
public class SpringAiLlmService implements LlmService {

    /** 连接超时秒数 */
    @Value("${dingring.llm.connect-timeout-seconds:10}")
    private long connectTimeoutSeconds;

    /** 读超时秒数（LLM 生成耗时较长，默认放宽到 120s） */
    @Value("${dingring.llm.read-timeout-seconds:120}")
    private long readTimeoutSeconds;

    @Override
    public String chat(Agent agent, String systemPrompt, List<ChatTurn> messages) {
        long startAt = System.currentTimeMillis();
        try {
            OpenAiChatModel chatModel = buildChatModel(agent);
            List<Message> aiMessages = toAiMessages(systemPrompt, messages);
            log.info("LLM 请求开始, agent={}, model={}, baseUrl={}, systemPrompt长度={}, 上下文轮数={}",
                    agent.getName(), agent.getModelName(), agent.getBaseUrl(),
                    systemPrompt == null ? 0 : systemPrompt.length(), messages.size());
            ChatResponse response = chatModel.call(new Prompt(aiMessages));
            String text = response.getResult().getOutput().getText();
            long cost = System.currentTimeMillis() - startAt;
            if (text == null || text.isBlank()) {
                log.warn("LLM 返回空内容, agent={}, model={}, 耗时={}ms, 原始响应={}",
                        agent.getName(), agent.getModelName(), cost, response.getResult());
            } else {
                // 完整输出不截断，保证可观测性（静默无回复问题排查依据）
                log.info("LLM 响应完成, agent={}, model={}, 耗时={}ms, 长度={}, 完整内容:\n{}",
                        agent.getName(), agent.getModelName(), cost, text.length(), text);
            }
            return text == null ? "" : text.trim();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("LLM 调用失败, agent={}, model={}, 耗时={}ms",
                    agent.getName(), agent.getModelName(), System.currentTimeMillis() - startAt, e);
            throw new BizException(ErrorCode.LLM_API_ERROR,
                    "LLM 调用失败: " + agent.getName() + " - " + e.getMessage());
        }
    }

    @Override
    public String chatStream(Agent agent, String systemPrompt, List<ChatTurn> messages, Consumer<String> onDelta) {
        long startAt = System.currentTimeMillis();
        try {
            OpenAiChatModel chatModel = buildChatModel(agent);
            List<Message> aiMessages = toAiMessages(systemPrompt, messages);
            log.info("LLM 流式请求开始, agent={}, model={}, baseUrl={}, systemPrompt长度={}, 上下文轮数={}",
                    agent.getName(), agent.getModelName(), agent.getBaseUrl(),
                    systemPrompt == null ? 0 : systemPrompt.length(), messages.size());
            StringBuilder full = new StringBuilder();
            // 块间超时复用读超时配置：网关挂起时 Flux 报错退出，不永久卡死引擎线程
            chatModel.stream(new Prompt(aiMessages))
                    .timeout(Duration.ofSeconds(readTimeoutSeconds))
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
                log.warn("LLM 流式返回空内容, agent={}, model={}, 耗时={}ms",
                        agent.getName(), agent.getModelName(), cost);
            } else {
                log.info("LLM 流式响应完成, agent={}, model={}, 耗时={}ms, 长度={}, 完整内容:\n{}",
                        agent.getName(), agent.getModelName(), cost, text.length(), text);
            }
            return text.trim();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("LLM 流式调用失败, agent={}, model={}, 耗时={}ms",
                    agent.getName(), agent.getModelName(), System.currentTimeMillis() - startAt, e);
            throw new BizException(ErrorCode.LLM_API_ERROR,
                    "LLM 流式调用失败: " + agent.getName() + " - " + e.getMessage());
        }
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

    private OpenAiChatModel buildChatModel(Agent agent) {
        UrlParts parts = resolveUrl(agent.getBaseUrl());
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(connectTimeoutSeconds));
        requestFactory.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));
        // 流式走 WebClient（无 reactor-netty，用 JDK HttpClient 连接器）；读超时由 Flux.timeout 块间控制
        HttpClient jdkHttpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .build();
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(parts.baseUrl())
                .completionsPath(parts.completionsPath())
                .apiKey(agent.getApiKey())
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .webClientBuilder(WebClient.builder().clientConnector(new JdkClientHttpConnector(jdkHttpClient)))
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
