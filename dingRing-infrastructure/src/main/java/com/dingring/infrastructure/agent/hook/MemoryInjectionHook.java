package com.dingring.infrastructure.agent.hook;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import com.dingring.common.util.LogHelper;
import com.dingring.domain.service.MemoryService;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 群记忆注入 Hook（Phase D）。
 * <p>在 LLM 调用前注入群历史记忆（历史 Topic 结论），替代 Phase C 由 ContextBuilder 静态拼接。
 * <p>注入方式（SAA Hook 机制）：beforeModel 返回 {@code Map.of("messages", new SystemMessage(memory))}，
 * SAA 图引擎按 AppendStrategy 将该 SystemMessage 追加到 state 的 messages 列表。
 * <p>线程安全：Hook 为单例 Bean，但通过 OverAllState（per-call）读取 groupId，无共享可变状态。
 */
@Component
public class MemoryInjectionHook extends ModelHook {

    private final MemoryService memoryService;

    public MemoryInjectionHook(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Override
    public String getName() {
        return "memory-injection";
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeModel(OverAllState state, RunnableConfig config) {
        Long groupId = state.<Long>value("groupId").orElse(null);
        if (groupId == null) {
            return CompletableFuture.completedFuture(Map.of());
        }

        String memory = memoryService.retrieveMemory(groupId);
        if (memory == null || memory.isBlank()) {
            return CompletableFuture.completedFuture(Map.of());
        }

        LogHelper.printLog(MemoryInjectionHook.class, "MemoryInjectionHook.beforeModel",
                "HOOK_MEMORY", "注入群历史记忆", "groupId={} 长度={}", groupId, memory.length());

        // 方案 A：向 messages state key 追加 SystemMessage（AppendStrategy）
        // ReactAgent 内部会将此 SystemMessage 与字段级 systemPrompt 共存传给 ChatModel
        return CompletableFuture.completedFuture(
                Map.of("messages", new SystemMessage("群历史记忆（过往讨论结论，供参考）：\n" + memory))
        );
    }
}
