package com.dingring.infrastructure.workflow;

import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.dingring.common.constant.WsConstants;
import com.dingring.domain.service.DiscussionFlowService;
import com.dingring.domain.service.GroupBroadcastService;
import com.dingring.domain.workflow.DiscussionFlowResult;
import com.dingring.domain.workflow.DiscussionRules;
import com.dingring.domain.workflow.StateKeys;
import com.dingring.infrastructure.aop.Event;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DiscussionFlowService 的 SAA StateGraph 实现。
 * <p>核心职责：把群聊流程编译为 StateGraph 并执行。
 * <p>设计决策（基于 SAA 1.1.2.3 实际 API 验证）：
 * <ul>
 *   <li>不用 FlowNode/FlowEdge 字符串条件中间层——SAA EdgeAction 是代码接口，
 *       直接用 Java lambda 判断条件比解析字符串更安全可靠</li>
 *   <li>@PostConstruct 时编译一次 StateGraph，advance() 只调用 invoke()——避免重复编译开销</li>
 *   <li>条件边用 AsyncEdgeAction.edge_async(state -> "返回值")，
 *       Map 映射返回值到目标节点名</li>
 * </ul>
 * <p>流程图（方案 6.2.1，Phase C 单轮推进设计）：
 * <pre>
 * START → preprocess → intent-classify → (条件边: intent)
 *   CHAT → chat → (条件边: needProfileExtract?) → profile-extract → END / END
 *   DISCUSS → ensure-topic → (条件边: ensureSuccess?)
 *     true → discuss → (条件边: discussMode)
 *       CONVERGE/DIVERGE → END (DiscussionEngine 主循环决定后续推进)
 *       WAIT → END (阻塞等用户)
 *       CONCLUDE_PROPOSED → END (等用户确认)
 *       CONCLUDE → conclude → sediment → END
 *     false → chat (回退闲聊应答)
 *   WORK → work → sediment → END
 *   CONCLUDE → conclude → sediment → END
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SaaWorkflow implements DiscussionFlowService {

    private final NodeHandlerRegistry nodeRegistry;
    private final GroupBroadcastService groupBroadcastService;
    private CompiledGraph compiledGraph;

    /** 图节点 ID → 前端展示名（FLOW_EVENT 广播用） */
    private static final Map<String, String> NODE_NAMES = Map.of(
            "preprocess", "预处理",
            "intent-classify", "意图分类",
            "chat", "闲聊应答",
            "ensure-topic", "建题判定",
            "discuss", "讨论推进",
            "work", "任务执行",
            "conclude", "总结陈词",
            "sediment", "沉淀入库",
            "profile-extract", "画像提炼"
    );

    /** FLOW_EVENT 推送的可观测白名单：只暴露少量关键 state 字段，避免把完整 state（含消息历史）发给前端 */
    private static final List<String> OBSERVABLE_STATE_KEYS = List.of(
            StateKeys.INTENT, StateKeys.CONFIDENCE, StateKeys.TOPIC_TITLE, StateKeys.TOPIC_ID,
            StateKeys.ENSURE_SUCCESS, StateKeys.DISCUSS_MODE, StateKeys.TRIGGERED_BY,
            StateKeys.CONCLUDED, StateKeys.DIVERGE_ROUNDS, StateKeys.SPEAKER_AGENT_ID,
            StateKeys.RESTART_HINT, StateKeys.USER_HISTORY_HINT
    );

    @PostConstruct
    public void init() throws GraphStateException {
        // 1. KeyStrategyFactory：定义 OverAllState 的 key 策略
        KeyStrategyFactory keyStrategyFactory = () -> {
            Map<String, KeyStrategy> strategies = new HashMap<>();
            // 输入（替换策略）
            strategies.put(StateKeys.GROUP_ID, new ReplaceStrategy());
            strategies.put(StateKeys.INPUT, new ReplaceStrategy());
            strategies.put(StateKeys.MENTIONED_AGENT_IDS, new ReplaceStrategy());
            strategies.put(StateKeys.REPLIED_TO_AGENT_ID, new ReplaceStrategy());
            // 话题（替换策略）
            strategies.put(StateKeys.TOPIC_ID, new ReplaceStrategy());
            strategies.put(StateKeys.TOPIC_TITLE, new ReplaceStrategy());
            strategies.put(StateKeys.RESTART_HINT, new ReplaceStrategy());
            strategies.put(StateKeys.USER_HISTORY_HINT, new ReplaceStrategy());
            // 意图（替换策略）
            strategies.put(StateKeys.INTENT, new ReplaceStrategy());
            strategies.put(StateKeys.CONFIDENCE, new ReplaceStrategy());
            // 讨论模式（替换策略）
            strategies.put(StateKeys.DISCUSS_MODE, new ReplaceStrategy());
            strategies.put(StateKeys.DIVERGE_ROUNDS, new ReplaceStrategy());
            // 收束（替换策略）
            strategies.put(StateKeys.CONCLUDED, new ReplaceStrategy());
            strategies.put(StateKeys.CONCLUSION, new ReplaceStrategy());
            strategies.put(StateKeys.CONCLUDER_AGENT_ID, new ReplaceStrategy());
            strategies.put(StateKeys.TRIGGERED_BY, new ReplaceStrategy());
            // 讨论态运行时状态（替换策略——由 DiscussionEngine 维护，每次 advance 通过 inputs 传入）
            strategies.put(StateKeys.PASSED_AGENT_IDS, new ReplaceStrategy());
            strategies.put(StateKeys.MENTION_HANDLED, new ReplaceStrategy());
            strategies.put(StateKeys.LOW_DISCUSS_STREAK, new ReplaceStrategy());
            strategies.put(StateKeys.SPEAKER_AGENT_ID, new ReplaceStrategy());
            strategies.put(StateKeys.ENSURE_SUCCESS, new ReplaceStrategy());
            // 闲聊态运行时状态（替换策略）
            strategies.put(StateKeys.CHAT_BUFFER, new ReplaceStrategy());
            strategies.put(StateKeys.NEED_PROFILE_EXTRACT, new ReplaceStrategy());
            // 消息历史（追加策略——累积所有节点产出的消息 ID）
            strategies.put(StateKeys.MESSAGES, new AppendStrategy(false));
            return strategies;
        };

        // 2. 构建 StateGraph
        StateGraph graph = new StateGraph("group-chat", keyStrategyFactory);

        // 3. 添加节点（通过 NodeHandlerRegistry 按 Bean 名查找 NodeAction）
        graph.addNode("preprocess", AsyncNodeAction.node_async(
                nodeRegistry.getHandler("preprocessHandler")));
        graph.addNode("intent-classify", AsyncNodeAction.node_async(
                nodeRegistry.getHandler("intentClassifyHandler")));
        graph.addNode("chat", AsyncNodeAction.node_async(
                nodeRegistry.getHandler("chatHandler")));
        graph.addNode("ensure-topic", AsyncNodeAction.node_async(
                nodeRegistry.getHandler("ensureTopicHandler")));
        graph.addNode("discuss", AsyncNodeAction.node_async(
                nodeRegistry.getHandler("discussHandler")));
        graph.addNode("conclude", AsyncNodeAction.node_async(
                nodeRegistry.getHandler("concludeHandler")));
        graph.addNode("sediment", AsyncNodeAction.node_async(
                nodeRegistry.getHandler("sedimentHandler")));
        graph.addNode("profile-extract", AsyncNodeAction.node_async(
                nodeRegistry.getHandler("profileExtractHandler")));
        graph.addNode("work", AsyncNodeAction.node_async(
                nodeRegistry.getHandler("workHandler")));

        // 4. 添加边
        // 入口
        graph.addEdge(StateGraph.START, "preprocess");
        graph.addEdge("preprocess", "intent-classify");

        // intent-classify 条件边：按意图分流
        graph.addConditionalEdges("intent-classify",
                AsyncEdgeAction.edge_async(state -> state.value(StateKeys.INTENT, "CHAT")),
                Map.of(
                        "CHAT", "chat",
                        "DISCUSS", "ensure-topic",
                        "WORK", "work",
                        "CONCLUDE", "conclude"
                ));

        // chat 条件边：缓冲达阈值时触发画像提炼
        graph.addConditionalEdges("chat",
                AsyncEdgeAction.edge_async(state -> {
                    boolean need = state.value(StateKeys.NEED_PROFILE_EXTRACT, false);
                    return need ? "EXTRACT" : "END";
                }),
                Map.of(
                        "EXTRACT", "profile-extract",
                        "END", StateGraph.END
                ));
        graph.addEdge("profile-extract", StateGraph.END);

        // ensure-topic 条件边：建题成功 → 进入讨论；未达门槛 → 回退到闲聊应答
        // 注意：建题成功（ensureSuccess=true）应进 discuss；未建题（false）回退 chat。
        // 曾写反（true→CHAT），导致低置信度未建题的消息被错误路由进 discuss（topicId 为空）而崩溃。
        graph.addConditionalEdges("ensure-topic",
                AsyncEdgeAction.edge_async(state -> state.value(StateKeys.ENSURE_SUCCESS, false)
                        ? "DISCUSS" : "CHAT"),
                Map.of(
                        "DISCUSS", "discuss",
                        "CHAT", "chat"
                ));

        // discuss 条件边：按讨论模式分流（Phase C 每次只推进一轮，由 DiscussionEngine 主循环控制后续推进）
        graph.addConditionalEdges("discuss",
                AsyncEdgeAction.edge_async(state -> state.value(StateKeys.DISCUSS_MODE, "")),
                Map.of(
                        // CONVERGE/DIVERGE：本轮已发言完成，DiscussionEngine 根据返回结果决定是否继续推进
                        StateKeys.MODE_CONVERGE, StateGraph.END,
                        StateKeys.MODE_DIVERGE, StateGraph.END,
                        StateKeys.MODE_WAIT, StateGraph.END,                  // 等待：阻塞等用户回答
                        StateKeys.MODE_CONCLUDE_PROPOSED, StateGraph.END,    // 提议收束：等用户确认
                        StateKeys.MODE_CONCLUDE, "conclude"                  // 收束：生成结论
                ));

        // 收束路径
        graph.addEdge("conclude", "sediment");
        graph.addEdge("sediment", StateGraph.END);

        // 工作路径
        graph.addEdge("work", "sediment");

        // 5. 编译
        // 空 SaverConfig 禁用 checkpoint：本应用每次 advance() 都是独立无状态执行，
        // 不需要跨调用恢复状态。默认 CompileConfig 注册 MemorySaver 且 RunnableConfig.threadId 固定，
        // 一旦某次节点抛异常（图走 error 分支），handleCompletion 不会释放该 thread 的 checkpoint，
        // 残留 state（如 intent=CONCLUDE）会被下一次 advance 的 getInitialState 合并恢复，
        // 导致普通消息被错误路由进 ConcludeNode 并反复崩溃——形成自愈不了的崩溃循环（Phase G bug2 的复发形态）。
        // 禁用后 getInitialState 恒为纯 inputs，预设意图（runConcludeFlow/advanceAuto）仍通过 inputs 透传。
        compiledGraph = graph.compile(CompileConfig.builder()
                .saverConfig(SaverConfig.builder().build())
                .build());
        log.info("SaaWorkflow StateGraph 编译完成，节点: preprocess/intent-classify/chat/ensure-topic/discuss/conclude/sediment/profile-extract/work");
    }

    @Override
    @Event(eventCode = "SaaWorkflow.advance", eventName = "推进群聊至下一节点")
       public DiscussionFlowResult advance(DiscussionRules rules, Map<String, Object> inputs) {
        // 合并 DiscussionRules 参数到 inputs，让各 NodeAction 从 OverAllState 读取配置
        Map<String, Object> allInputs = new HashMap<>(inputs);
        allInputs.put("divergePaceMs", rules.divergePaceMs());
        allInputs.put("maxDivergeRounds", rules.maxDivergeRounds());
        allInputs.put("maxRounds", rules.maxRounds());
        allInputs.put("profileExtractThreshold", rules.profileExtractThreshold());
        allInputs.put("backfillLimit", rules.backfillLimit());
        allInputs.put("contextWindow", rules.contextWindow());
        allInputs.put("concludeConfirmTimeoutMs", rules.concludeConfirmTimeoutMs());

        log.info("SaaWorkflow.advance 开始执行, inputs={}", allInputs.keySet());
        // groupId 恒在 inputs 中，用于 FLOW_EVENT 定向广播
        Long groupId = inputs.get(StateKeys.GROUP_ID) instanceof Number n ? n.longValue() : null;
        long[] lastNodeTs = { System.currentTimeMillis() };
        try {
            // 用 stream() 节点事件流替代 invoke()：每个节点执行完产生一个 NodeOutput，
            // 借此把"图走到哪一步"实时广播到前端（FLOW_EVENT），其余语义与 invoke 完全一致
            List<NodeOutput> outputs = compiledGraph.stream(allInputs, RunnableConfig.builder().build())
                    .doOnNext(nodeOutput -> broadcastFlowEvent(groupId, nodeOutput, lastNodeTs))
                    .collectList()
                    .block();
            if (outputs == null || outputs.isEmpty()) {
                log.warn("SaaWorkflow.advance 返回空状态");
                return new DiscussionFlowResult(false, null, Map.of());
            }
            // 最终状态取 END 节点（流中最后一个）的 state，与 invoke 返回值等价
            OverAllState state = outputs.get(outputs.size() - 1).state();
            boolean concluded = state.value(StateKeys.CONCLUDED, false);
            String discussMode = state.value(StateKeys.DISCUSS_MODE, "");
            Map<String, Object> stateData = state.data();
            log.info("SaaWorkflow.advance 完成, concluded={}, discussMode={}, stateKeys={}",
                    concluded, discussMode, stateData.keySet());
            return new DiscussionFlowResult(concluded, discussMode, stateData);
        } catch (Exception e) {
            broadcastFlowError(groupId, e);
            log.error("SaaWorkflow.advance 执行异常", e);
            throw new RuntimeException("群聊流程执行失败: " + e.getMessage(), e);
        }
    }

    /** 把单个节点执行事件广播为 FLOW_EVENT（前端据此渲染流程步骤条） */
    private void broadcastFlowEvent(Long groupId, NodeOutput nodeOutput, long[] lastNodeTs) {
        if (groupId == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long elapsedMs = now - lastNodeTs[0];
        lastNodeTs[0] = now;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("node", nodeOutput.node());
        data.put("nodeName", NODE_NAMES.getOrDefault(nodeOutput.node(), nodeOutput.node()));
        data.put("status", nodeOutput.isEND() ? "END" : "SUCCESS");
        data.put("elapsedMs", elapsedMs);
        data.put("state", extractObservableState(nodeOutput.state()));
        groupBroadcastService.broadcast(groupId, WsConstants.FLOW_EVENT, data);
    }

    /** 图执行异常时广播一条 FLOW_EVENT(ERROR)，让前端流程条展示失败而非卡在最后节点 */
    private void broadcastFlowError(Long groupId, Exception e) {
        if (groupId == null) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("node", "error");
        data.put("nodeName", "流程异常");
        data.put("status", "ERROR");
        data.put("elapsedMs", 0L);
        data.put("message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        data.put("state", Map.of());
        groupBroadcastService.broadcast(groupId, WsConstants.FLOW_EVENT, data);
    }

    /** 只提取白名单字段，避免把完整 state（含消息历史等）推给前端 */
    private Map<String, Object> extractObservableState(OverAllState state) {
        Map<String, Object> visible = new LinkedHashMap<>();
        for (String key : OBSERVABLE_STATE_KEYS) {
            state.value(key).ifPresent(v -> visible.put(key, v));
        }
        return visible;
    }
}
