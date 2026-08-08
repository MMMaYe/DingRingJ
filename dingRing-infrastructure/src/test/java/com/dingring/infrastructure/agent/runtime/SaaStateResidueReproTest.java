package com.dingring.infrastructure.agent.runtime;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SAA 状态残留回归测试（Phase G bug2）。
 * <p>背景：SAA 1.1.2.3 默认 CompileConfig 注册 MemorySaver checkpoint saver，
 * 默认 RunnableConfig.threadId 固定，导致下一次 invoke 时 getInitialState 从 saver
 * 恢复上一次执行的全部 state（含 intent 等键）——DISCUSS 消息被残留 INTENT=WORK 误路由。
 * <p>修复：graph.compile(CompileConfig.builder().releaseThread(true).build())，
 * 每次执行结束后释放该 thread 的 checkpoint。
 * <p>断言：第一次 invoke 走 WORK 分支；第二次 invoke 状态全新、重新分类为 CHAT 并路由到 chat 分支。
 */
class SaaStateResidueReproTest {

    private final AtomicReference<String> secondBranch = new AtomicReference<>("none");

    private CompiledGraph buildGraph() throws GraphStateException {
        KeyStrategyFactory factory = () -> {
            Map<String, KeyStrategy> strategies = new HashMap<>();
            strategies.put("intent", new ReplaceStrategy());
            strategies.put("topicId", new ReplaceStrategy());
            strategies.put("input", new ReplaceStrategy());
            return strategies;
        };
        StateGraph graph = new StateGraph("test", factory);

        graph.addNode("intent-classify", AsyncNodeAction.node_async(state -> {
            String preset = state.<String>value("intent").orElse(null);
            System.out.println("==== intent-classify state@"
                    + System.identityHashCode(state) + " preset=" + preset
                    + " dataKeys=" + state.data().keySet());
            if (preset != null && !preset.isBlank()) {
                return Map.of();
            }
            String input = state.value("input", "");
            return Map.of("intent", input.startsWith("task1") ? "WORK" : "CHAT");
        }));

        graph.addNode("work", AsyncNodeAction.node_async(state -> {
            System.out.println("==== work node state@"
                    + System.identityHashCode(state)
                    + " intent=" + state.value("intent", "null"));
            secondBranch.compareAndSet("none", "work");
            return Map.of("workResult", "done");
        }));

        graph.addNode("chat-end", AsyncNodeAction.node_async(state -> {
            secondBranch.compareAndSet("none", "chat");
            return Map.of();
        }));

        graph.addNode("sediment", AsyncNodeAction.node_async(state -> {
            Long topicId = state.<Long>value("topicId").orElse(null);
            Map<String, Object> r = new HashMap<>();
            r.put("topicId", topicId == null ? 0L : topicId);
            return r;
        }));

        graph.addConditionalEdges("intent-classify",
                AsyncEdgeAction.edge_async(state -> {
                    String intent = state.value("intent", "CHAT");
                    System.out.println("==== CONDITIONAL EDGE intent=" + intent
                            + " state@" + System.identityHashCode(state)
                            + " dataKeys=" + state.data().keySet());
                    return intent;
                }),
                Map.of("WORK", "work", "CHAT", "chat-end"));
        graph.addEdge("work", "sediment");
        graph.addEdge(StateGraph.START, "intent-classify");
        graph.addEdge("chat-end", StateGraph.END);
        graph.addEdge("sediment", StateGraph.END);
        return graph.compile(com.alibaba.cloud.ai.graph.CompileConfig.builder()
                .releaseThread(true)
                .build());
    }

    @Test
    @DisplayName("复现：异常后第二次 invoke 是否残留 intent 并误路由")
    void stateResidueAfterException() throws GraphStateException {
        CompiledGraph graph = buildGraph();
        secondBranch.set("none");

        // 第一次 invoke：WORK 分支 → sediment NPE（模拟真实 SedimentNode 的 Map.of NPE）
        try {
            graph.invoke(Map.of("input", "task1"));
        } catch (Exception expected) {
            System.out.println("==== FIRST INVOKE THREW: " + expected.getClass().getSimpleName());
        }
        assertEquals("work", secondBranch.get(), "第一次 invoke 应走 WORK 分支");
        secondBranch.set("none");

        // 第二次 invoke：模拟 DISCUSS 消息，intent-classify 应重新分类为 CHAT
        Optional<OverAllState> result = graph.invoke(Map.of("input", "task2"));
        System.out.println("==== SECOND BRANCH = " + secondBranch.get());
        result.ifPresent(s -> System.out.println("==== SECOND intent=" + s.value("intent", "null")));

        assertEquals("chat", secondBranch.get(),
                "第二次 invoke 应走 CHAT 分支；若为 work 则说明 state 残留 intent=WORK（bug2 复现）");
        result.ifPresent(s -> assertEquals("CHAT", s.<String>value("intent").orElse(null)));
    }
}
