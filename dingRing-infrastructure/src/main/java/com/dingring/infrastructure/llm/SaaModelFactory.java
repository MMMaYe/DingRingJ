package com.dingring.infrastructure.llm;

import com.dingring.domain.agent.Agent;
import com.dingring.domain.service.LlmService.CallOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 按 Agent 领域实体配置构建 Spring AI OpenAiChatModel。
 * <p>设计目的(Phase A):
 * <ul>
 *   <li>从 SpringAiLlmService 提取模型构建逻辑，chat()/chatStream() 只管调用</li>
 *   <li>Phase D 统一入口 ReactAgentLlmService / ReactAgentFactory 均复用本工厂构建 Model，避免重复 resolveUrl 逻辑</li>
 * </ul>
 * <p>兼容 DeepSeek / StepFun / 智谱等 OpenAI 兼容厂商，resolveUrl 处理双重版本号 404 坑。
 */
@Slf4j
@Component
public class SaaModelFactory {

    /** 连接超时秒数（默认 RestClient 无超时，网关偶发挂起会永久卡死对话引擎线程） */
    @Value("${dingring.llm.connect-timeout-seconds:10}")
    private long connectTimeoutSeconds;

    /** 读超时秒数（LLM 生成耗时较长，默认放宽到 120s） */
    @Value("${dingring.llm.read-timeout-seconds:120}")
    private long readTimeoutSeconds;

    /** 业务发言未单独配置 maxTokens 时的默认输出预算，普通发言需支持较长的 SVG/文档结果。 */
    @Value("${dingring.llm.default-max-tokens:16384}")
    private int defaultMaxTokens;

    /**
     * 构建 OpenAiChatModel，参数优先级:CallOptions > Agent 配置 > 全局默认。
     * <p>单次覆盖参数用于意图分类等确定性任务（低温/小 maxTokens/短超时），
     * 避免复用 Agent 会话参数（temperature 0.7、读超时 120s）导致判定抖动或长时间卡住调用方。
     *
     * @param agent   Agent 领域实体（baseUrl / apiKey / modelName / temperature / maxTokens）
     * @param options 单次调用参数覆盖（null = 完全沿用 Agent 配置）
     * @return 配置好的 OpenAiChatModel（每次调用新建，无状态）
     */
    public OpenAiChatModel buildChatModel(Agent agent, CallOptions options) {
        double temperature = options != null && options.temperature() != null
                ? options.temperature() : agent.temperature();
        int maxTokens = options != null && options.maxTokens() != null
                ? options.maxTokens() : agent.maxTokens(defaultMaxTokens);
        long readTimeout = options != null && options.readTimeoutSeconds() != null
                && options.readTimeoutSeconds() > 0
                ? options.readTimeoutSeconds() : readTimeoutSeconds;

        log.debug("构建 LLM 模型 agent={} model={} maxTokens={} source={}",
                agent.getName(), agent.getModelName(), maxTokens,
                options != null && options.maxTokens() != null ? "callOptions"
                        : agent.getFeature() != null && agent.getFeature().get("maxTokens") instanceof Number
                                ? "agentFeature" : "default");

        UrlParts parts = resolveUrl(agent.getBaseUrl());

        // 非流式：RestClient + SimpleClientHttpRequestFactory（设置连接/读超时）
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(connectTimeoutSeconds));
        requestFactory.setReadTimeout(Duration.ofSeconds(readTimeout));

        // 流式：WebClient + JDK HttpClient 连接器（无 reactor-netty 依赖）
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

        OpenAiChatOptions.Builder chatOptionsBuilder = OpenAiChatOptions.builder()
                .model(agent.getModelName())
                .temperature(temperature)
                .maxTokens(maxTokens);

        // JSON 模式：API 层面强制输出合法 JSON（意图分类/主持人决策/知识卡片提取等场景）
        // Spring AI 1.1.2 仍用 ResponseFormat.builder()（1.0.0 GA 已移除单参构造）
        if (options != null && Boolean.TRUE.equals(options.jsonMode())) {
            chatOptionsBuilder.responseFormat(ResponseFormat.builder()
                    .type(ResponseFormat.Type.JSON_OBJECT)
                    .build());
        }

        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(chatOptionsBuilder.build())
                .build();
    }

    /**
     * 读超时秒数（供 SpringAiLlmService.chatStream 的 Flux.timeout 块间超时复用）。
     * <p>块间超时：网关挂起时 Flux 报错退出，不永久卡死引擎线程。
     */
    public long getReadTimeoutSeconds() {
        return readTimeoutSeconds;
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
