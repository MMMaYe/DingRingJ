package com.dingring.infrastructure.agent.interceptor;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.dingring.common.util.JsonHelper;
import com.dingring.common.util.LogHelper;
import com.dingring.infrastructure.aop.Event;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

import java.util.List;

/**
 * 模型调用请求日志拦截器：打印每次 LLM 调用的完整请求入参，用于观察
 * 「Hook 注入后的最终消息结构 + 工具装配结果」。
 *
 * <p>为什么选 ModelInterceptor 而非 Hook/日志切面：
 * <ul>
 *   <li>拦截点在 AgentLlmNode 的 baseHandler 外层，流式/非流式两个分支共用同一条
 *       interceptor 链，一处挂载全路径生效；</li>
 *   <li>时机在 appendSystemPromptIfNeeded 之前——{@code getMessages()} 即 Merge Hook
 *       合并后的最终消息列表，{@code getSystemMessage()} 是字段级 systemPrompt
 *       （DingRing 讨论场景刻意为 null，Supervisor/Worker 有值）。两者拼接才是真正
 *       发往 Chat Completions 的完整 messages，因此日志中将两部分分别打印；</li>
 *   <li>ReAct 循环每轮模型调用各经过一次本拦截器，可顺带观察工具往返过程中的
 *       消息列表增长（AssistantMessage 工具调用 + ToolResponseMessage）。</li>
 * </ul>
 *
 * <p>为什么不用 {@code @Event} 注解：EventAspect 会用 fastjson 序列化方法入参出参，
 * ModelRequest 含 Message/ToolCallback 等复杂对象，序列化体积大且存在失败风险，
 * 故本类手工打印关键字段。
 *
 * <p>线程安全：单例 Bean，仅读取 request（不修改、不持有），与各 Hook 同一安全模式。
 * 开关：{@code dingring.llm.log-model-request}（默认 true，生产可关闭以省日志量）。
 */
@Component
public class ModelRequestLoggingInterceptor extends ModelInterceptor {

    /** 是否打印模型请求日志（全量消息内容，日志量大，生产环境可关闭） */
    @Value("${dingring.llm.log-model-request:true}")
    private boolean logEnabled;

    @Override
    public String getName() {
        return "model-request-logging";
    }

    @Override
    @Event(eventCode = "BEFORE_CALL_LOG", eventName = "LLM请求日志")
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        if (logEnabled) {
            LogHelper.printLog(ModelRequestLoggingInterceptor.class,
                    "ModelRequestLoggingInterceptor.interceptModel", "LLM_REQUEST",
                    "LLM请求日志", "request={}", JsonHelper.toJson(request));
//            logRequest(request);
        }
        ContextSize contextSize = contextSizeOf(request);
        ModelResponse response = handler.call(request);
        if (logEnabled) {
            logContextUsage(request, contextSize, response);
        }
        return response;
    }

    private void logContextUsage(ModelRequest request, ContextSize contextSize, ModelResponse response) {
        Usage usage = usageOf(response);
        LogHelper.printLog(ModelRequestLoggingInterceptor.class, "interceptModel", "LLM_CONTEXT_USAGE",
                "LLM上下文统计",
                "model={} messageCount={} toolCount={} systemChars={} messageChars={} toolChars={} "
                        + "contextChars={} contextBytes={} contextTokens={} promptTokens={} completionTokens={} totalTokens={} tokenSource={}",
                modelOf(request), contextSize.messageCount(), contextSize.toolCount(),
                contextSize.systemChars(), contextSize.messageChars(), contextSize.toolChars(),
                contextSize.contextChars(), contextSize.contextBytes(),
                tokenOf(usage == null ? null : usage.getPromptTokens()),
                tokenOf(usage == null ? null : usage.getPromptTokens()),
                tokenOf(usage == null ? null : usage.getCompletionTokens()),
                tokenOf(usage == null ? null : usage.getTotalTokens()),
                usage == null ? "unavailable" : "provider");
    }

    private Usage usageOf(ModelResponse response) {
        if (response == null) {
            return null;
        }
        ChatResponse chatResponse = response.getChatResponse();
        if (chatResponse == null) {
            return null;
        }
        ChatResponseMetadata metadata = chatResponse.getMetadata();
        return metadata == null ? null : metadata.getUsage();
    }

    private String tokenOf(Integer tokens) {
        return tokens == null ? "-" : String.valueOf(tokens);
    }

    private ContextSize contextSizeOf(ModelRequest request) {
        String systemText = request.getSystemMessage() == null ? "" : request.getSystemMessage().getText();
        long messageChars = 0;
        long messageBytes = 0;
        List<Message> messages = request.getMessages();
        int messageCount = messages == null ? 0 : messages.size();
        if (messages != null) {
            for (Message message : messages) {
                String text = message == null || message.getText() == null ? "" : message.getText();
                messageChars += text.length();
                messageBytes += utf8Length(text);
            }
        }

        long toolChars = 0;
        long toolBytes = 0;
        int toolCount = request.getTools() == null ? 0 : request.getTools().size();
        if (request.getTools() != null) {
            for (String tool : request.getTools()) {
                toolChars += lengthOf(tool);
                toolBytes += utf8Length(tool);
            }
        }
        List<ToolCallback> callbacks = request.getDynamicToolCallbacks();
        if (callbacks != null) {
            toolCount += callbacks.size();
            for (ToolCallback callback : callbacks) {
                ToolDefinition definition = callback == null ? null : callback.getToolDefinition();
                if (definition == null) {
                    continue;
                }
                toolChars += lengthOf(definition.name()) + lengthOf(definition.description())
                        + lengthOf(definition.inputSchema());
                toolBytes += utf8Length(definition.name()) + utf8Length(definition.description())
                        + utf8Length(definition.inputSchema());
            }
        }

        long systemChars = systemText.length();
        long systemBytes = utf8Length(systemText);
        return new ContextSize(systemChars, messageChars, toolChars,
                systemChars + messageChars + toolChars,
                systemBytes + messageBytes + toolBytes, messageCount, toolCount);
    }

    private int lengthOf(String value) {
        return value == null ? 0 : value.length();
    }

    private long utf8Length(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private record ContextSize(long systemChars, long messageChars, long toolChars,
                               long contextChars, long contextBytes,
                               int messageCount, int toolCount) {
    }

    /**
     * 打印请求概览 + 消息明细两条日志。
     * <p>拆两条的原因：概览行短、便于 grep 检索定位；明细行含全量消息文本，通常很长，
     * 混在一行会淹没概览信息。
     */
    private void logRequest(ModelRequest request) {
        List<Message> messages = request.getMessages();
        int messageCount = messages == null ? 0 : messages.size();

        // 概览：模型参数 + 结构统计 + 工具装配结果
        LogHelper.printLog(ModelRequestLoggingInterceptor.class, "interceptModel", "LLM_REQUEST",
                "LLM请求概览", "model={} temperature={} maxTokens={} systemMessage长度={} 消息数={} tools={} 动态工具={}",
                modelOf(request), temperatureOf(request), maxTokensOf(request),
                request.getSystemMessage() == null ? "无" : request.getSystemMessage().getText().length(),
                messageCount,
                request.getTools(),
                toolNames(request.getDynamicToolCallbacks()));

        // 明细：systemMessage 全文 + 最终消息列表（Merge Hook 合并后的结构，可直接核对
        // 「单 SystemMessage 置顶」约束是否被破坏）
        LogHelper.printLog(ModelRequestLoggingInterceptor.class, "interceptModel", "LLM_REQUEST_DETAIL",
                "LLM请求明细", "systemMessage:\n{}\nmessages:\n{}",
                request.getSystemMessage() == null ? "(无)" : request.getSystemMessage().getText(),
                LogHelper.formatTurns(messages));
    }

    private String modelOf(ModelRequest request) {
        return optionsOf(request) == null ? "未知" : String.valueOf(optionsOf(request).getModel());
    }

    private Object temperatureOf(ModelRequest request) {
        return optionsOf(request) == null ? "未知" : optionsOf(request).getTemperature();
    }

    private Object maxTokensOf(ModelRequest request) {
        return optionsOf(request) == null ? "未知" : optionsOf(request).getMaxTokens();
    }

    private ToolCallingChatOptions optionsOf(ModelRequest request) {
        return request.getOptions();
    }

    private List<String> toolNames(List<ToolCallback> callbacks) {
        if (callbacks == null || callbacks.isEmpty()) {
            return List.of();
        }
        return callbacks.stream()
                .map(callback -> callback.getToolDefinition().name())
                .toList();
    }
}
