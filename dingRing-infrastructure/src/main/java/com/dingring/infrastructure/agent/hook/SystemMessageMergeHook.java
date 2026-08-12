package com.dingring.infrastructure.agent.hook;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import com.alibaba.cloud.ai.graph.state.ReplaceAllWith;
import com.dingring.common.util.LogHelper;
import com.dingring.infrastructure.aop.Event;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * SystemMessage 合并 Hook（修复多 SystemMessage 导致模型空响应/告警问题）。
 * <p>背景：ReactAgent 字段级 systemPrompt + 各注入 Hook（记忆/画像/名单/知识）各自追加一条
 * SystemMessage，且 Hook 注入的消息经 AppendStrategy 追加到 messages 列表末尾（位于 UserMessage
 * 之后），导致模型收到 3~5 条 SystemMessage、且 system 消息不在首位的非法结构。SAA 会告警
 * （Detected N SystemMessages），部分模型（如 stepfun）行为异常甚至返回空内容。
 * <p>方案：本 Hook 必须注册在各注入 Hook 之后（ReactAgent 按注册顺序执行 Hook），读取 state.messages，
 * 将字段级基础提示词（{@link #BASE_SYSTEM_PROMPT_KEY}，由 AgentSpeakerServiceImpl 写入 state）与所有
 * SystemMessage 文本按序拼接为单条 SystemMessage 置顶，并通过 {@link ReplaceAllWith} 整体替换 messages
 * （覆盖 AppendStrategy 的追加语义）。ReAct 多轮循环中每次模型调用前均执行，天然幂等。
 */
@Component
public class SystemMessageMergeHook extends ModelHook {

    /** state 中基础系统提示词 key（由 AgentSpeakerServiceImpl 写入，供本 Hook 合并） */
    public static final String BASE_SYSTEM_PROMPT_KEY = "baseSystemPrompt";

    /** 消息列表 key（与 ReactAgent 默认 messages key 一致） */
    private static final String MESSAGES_KEY = "messages";

    @Override
    public String getName() {
        return "system-message-merge";
    }

    @Override
    @Event(eventCode = "BEFORE_CALL", eventName = "HOOK的合并内容")
    public CompletableFuture<Map<String, Object>> beforeModel(OverAllState state, RunnableConfig config) {
        List<Message> messages = state.value(MESSAGES_KEY, List.of());
        if (messages == null || messages.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }

        List<Message> merged = mergeMessage(state, messages);

        if (merged == null) {
            return CompletableFuture.completedFuture(Map.of());
        }

        // ReplaceAllWith 覆盖 messages 的 AppendStrategy：整体替换而非追加
        return CompletableFuture.completedFuture(Map.of(MESSAGES_KEY, ReplaceAllWith.of(merged)));
    }

    @Event(eventCode = "BEFORE_MODEL", eventName = "合并SystemMessage")
    private List<Message> mergeMessage(OverAllState state, List<Message> messages) {
        // 基础系统提示词（Agent 人设 + 协作协议），由 AgentSpeakerServiceImpl 写入 state
        String basePrompt = state.value(BASE_SYSTEM_PROMPT_KEY, "");

        List<Message> merged = new ArrayList<>(messages.size());
        StringBuilder systemText = new StringBuilder();
        if (basePrompt != null && !basePrompt.isBlank()) {
            systemText.append(basePrompt);
        }
        int systemCount = 0;
        for (Message message : messages) {
            if (message instanceof SystemMessage systemMessage) {
                String text = systemMessage.getText();
                if (text != null && !text.isBlank()) {
                    if (systemText.length() > 0) {
                        systemText.append("\n\n");
                    }
                    systemText.append(text);
                }
                systemCount++;
            } else {
                merged.add(message);
            }
        }

        // 既无基础提示词也无 Hook 注入内容，无需合并
        if (systemText.length() == 0) {
            return null;
        }

        merged.add(0, new SystemMessage(systemText.toString()));
        LogHelper.printLog(SystemMessageMergeHook.class, "SystemMessageMergeHook.beforeModel",
                "HOOK_MERGE_SYSTEM", "合并 SystemMessage",
                "合并条数={} system长度={} merged={}", systemCount, systemText.length(), merged);
        return merged;
    }
}
