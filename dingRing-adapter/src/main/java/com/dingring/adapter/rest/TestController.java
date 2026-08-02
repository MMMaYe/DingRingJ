package com.dingring.adapter.rest;

import com.dingring.app.dto.test.LlmDebugChatRequest;
import com.dingring.app.dto.test.LlmDebugChatResponse;
import com.dingring.common.response.ApiResponse;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.service.LlmService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * LLM 调试专用 REST API。
 *
 * <p>目的：通过 Postman（任何 HTTP 客户端）直接构造请求，产生真实的 LLM 调用，
 * 用于排查：API Key 是否有效、baseUrl 解析是否正确、temperature/maxTokens 覆盖是否生效、
 * EventAspect 日志是否完整、流式 delta 是否按预期输出等。
 *
 * <p>安全：仅当配置 {@code dingring.debug.llm.enabled=true} 时该控制器才注册。
 * 生产配置必须置为 false（见 application-prod.yml）。
 */
@RestController
@RequestMapping("/api/test/llm")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "dingring.debug.llm.enabled", havingValue = "true")
public class TestController {

    private final LlmService llmService;

    /**
     * 对应 {@link LlmService#chat(Agent, String, java.util.List, LlmService.CallOptions)}。
     * 4 参重载（带 CallOptions）是你实际想要测试的方法——请求里 override 字段非空就用它。
     */
    @PostMapping("/chat")
    public ApiResponse<LlmDebugChatResponse> chat(@Valid @RequestBody LlmDebugChatRequest req) {
        Agent agent = toAgent(req);
        String systemPrompt = req.getSystemPrompt();
        List<LlmService.ChatTurn> turns = req.getMessages().stream()
                .map(LlmDebugChatRequest.Turn::toChatTurn)
                .collect(Collectors.toList());
        boolean jsonMode = Boolean.TRUE.equals(req.getJsonMode());
        boolean logReasoning = Boolean.TRUE.equals(req.getLogReasoning());
        LlmService.CallOptions options = new LlmService.CallOptions(
                req.getOverride() == null ? null : req.getOverride().getTemperature(),
                req.getOverride() == null ? null : req.getOverride().getMaxTokens(),
                req.getOverride() == null ? null : req.getOverride().getReadTimeoutSeconds(),
                jsonMode, logReasoning);

        long start = System.currentTimeMillis();
        String content = llmService.chat(agent, systemPrompt, turns, options);
        long cost = System.currentTimeMillis() - start;

        return ApiResponse.ok(LlmDebugChatResponse.builder()
                .content(content)
                .durationMs(cost)
                .length(content == null ? 0 : content.length())
                .build());
    }

    /**
     * 对应 {@link LlmService#chatStream(Agent, String, java.util.List, java.util.function.Consumer)}。
     * 为了兼容 Postman（不用 SSE 才能直接看完整结果），这里同步等流式结束再一次性返回，
     * 同时把每个 delta 块单独收集在 deltas 字段里，方便你对照打印/前端流式渲染是否有漏块。
     */
    @PostMapping("/chat-stream")
    public ApiResponse<LlmDebugChatResponse> chatStream(@Valid @RequestBody LlmDebugChatRequest req) {
        Agent agent = toAgent(req);
        String systemPrompt = req.getSystemPrompt();
        List<LlmService.ChatTurn> turns = req.getMessages().stream()
                .map(LlmDebugChatRequest.Turn::toChatTurn)
                .collect(Collectors.toList());

        List<String> deltas = new ArrayList<>();
        long start = System.currentTimeMillis();
        String content = llmService.chatStream(agent, systemPrompt, turns, deltas::add);
        long cost = System.currentTimeMillis() - start;

        return ApiResponse.ok(LlmDebugChatResponse.builder()
                .content(content)
                .durationMs(cost)
                .length(content == null ? 0 : content.length())
                .deltaCount(deltas.size())
                .deltas(deltas)
                .build());
    }

    /**
     * 把请求体里的 baseUrl/apiKey/modelName/temperature/maxTokens 装配成临时 Agent 对象。
     * 不落库，仅用于调用 LlmService（该接口按 Agent 字段读配置，不查 DB）。
     */
    private Agent toAgent(LlmDebugChatRequest req) {
        Agent a = new Agent();
        a.setId(-1L);
        a.setName(req.getAgentName() == null ? "debug" : req.getAgentName());
        a.setBaseUrl(req.getBaseUrl());
        a.setApiKey(req.getApiKey());
        a.setModelName(req.getModelName());
        // temperature()/maxTokens() 两个方法读的是 feature 里的对应 key，所以要放进 feature Map
        java.util.Map<String, Object> feature = new java.util.HashMap<>();
        if (req.getTemperature() != null) {
            feature.put("temperature", req.getTemperature());
        }
        if (req.getMaxTokens() != null) {
            feature.put("maxTokens", req.getMaxTokens());
        }
        if (!feature.isEmpty()) {
            a.setFeature(feature);
        }
        return a;
    }
}
