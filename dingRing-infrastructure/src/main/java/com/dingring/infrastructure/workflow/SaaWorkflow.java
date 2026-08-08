package com.dingring.infrastructure.workflow;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.dingring.domain.service.DiscussionFlowService;
import com.dingring.domain.workflow.DiscussionFlowResult;
import com.dingring.domain.workflow.DiscussionRules;
import com.dingring.domain.workflow.StateKeys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

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
    private CompiledGraph compiledGraph;

    @PostConstruct
    public void init() throws GraphStateException {
        // 1. KeyStrategyFactory：定义 OverAllState 的 key 策略
        KeyStrategyFactory keyStrategyFactory = () -> {
            Map<String, com.alibaba.cloud.ai.graph.KeyStrategy> strategies = new HashMap<>();
            // 输入（替换策略）
            strategies.put(StateKeys.GROUP_ID, new ReplaceStrategy());
            strategies.put(StateKeys.INPUT, new ReplaceStrategy());
            strategies.put(StateKeys.MENTIONED_AGENT_IDS, new ReplaceStrategy());
            strategies.put(StateKeys.REPLIED_TO_AGENT_ID, new ReplaceStrategy());
            // 话题（替换策略）
            strategies.put(StateKeys.TOPIC_ID, new ReplaceStrategy());
            strategies.put(StateKeys.TOPIC_TITLE, new ReplaceStrategy());
            strategies.put(StateKeys.RESTART_HINT, new ReplaceStrategy());
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
        graph.addConditionalEdges("ensure-topic",
                AsyncEdgeAction.edge_async(state -> state.value(StateKeys.ENSURE_SUCCESS, false)
                        ? "CHAT" : "DISCUSS"),
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
        compiledGraph = graph.compile();
        log.info("SaaWorkflow StateGraph 编译完成，节点: preprocess/intent-classify/chat/ensure-topic/discuss/conclude/sediment/profile-extract/work");
    }

    @Override
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
        try {
            Optional<com.alibaba.cloud.ai.graph.OverAllState> result = compiledGraph.invoke(allInputs);
            if (result.isEmpty()) {
                log.warn("SaaWorkflow.advance 返回空状态");
                return new DiscussionFlowResult(false, null, Map.of());
            }
            com.alibaba.cloud.ai.graph.OverAllState state = result.get();
            boolean concluded = state.value(StateKeys.CONCLUDED, false);
            String discussMode = state.value(StateKeys.DISCUSS_MODE, "");
            Map<String, Object> stateData = state.data();
            log.info("SaaWorkflow.advance 完成, concluded={}, discussMode={}, stateKeys={}",
                    concluded, discussMode, stateData.keySet());
            return new DiscussionFlowResult(concluded, discussMode, stateData);
        } catch (Exception e) {
            log.error("SaaWorkflow.advance 执行异常", e);
            throw new RuntimeException("群聊流程执行失败: " + e.getMessage(), e);
        }
    }
}
