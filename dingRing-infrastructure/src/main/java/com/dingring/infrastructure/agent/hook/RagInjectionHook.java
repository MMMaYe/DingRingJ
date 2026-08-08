package com.dingring.infrastructure.agent.hook;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import com.dingring.common.util.LogHelper;
import com.dingring.domain.service.RagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * RAG 知识注入 Hook（Phase E）。
 * <p>Agent 发言前自动检索知识库，将相关知识注入 system prompt。
 * <p>与 MemoryInjectionHook/ProfileInjectionHook 一致，通过 beforeModel 返回 SystemMessage。
 * <p>容错：检索失败时返回空 Map（不注入），不影响 Agent 发言。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "dingring.rag.enabled", havingValue = "true", matchIfMissing = true)
public class RagInjectionHook extends ModelHook {

    private final RagService ragService;

    @Override
    public CompletableFuture<Map<String, Object>> beforeModel(OverAllState state, RunnableConfig config) {
        Long groupId = state.<Long>value("groupId").orElse(null);
        // 从 state 读取检索 query（由节点构建时写入）
        String query = state.value("ragQuery", "");

        if (groupId == null || query == null || query.isBlank()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        try {
            String knowledge = ragService.retrieve(query, groupId);
            if (knowledge == null || knowledge.isBlank()) {
                return CompletableFuture.completedFuture(Map.of());
            }
            LogHelper.printLog(RagInjectionHook.class, "beforeModel", "RAG_HOOK",
                    "知识注入", "groupId={} queryLen={} knowledgeLen={}",
                            groupId, query.length(), knowledge.length());
            return CompletableFuture.completedFuture(
                    Map.of("messages", new SystemMessage("知识库参考：\n" + knowledge))
            );
        } catch (Exception e) {
            LogHelper.printWarnLog(RagInjectionHook.class, "beforeModel", "RAG_HOOK",
                    "知识注入失败跳过", "groupId={} 错误: {}", groupId, e.getMessage());
            return CompletableFuture.completedFuture(Map.of());
        }
    }

    @Override
    public String getName() {
        return "rag-injection-hook";
    }
}
