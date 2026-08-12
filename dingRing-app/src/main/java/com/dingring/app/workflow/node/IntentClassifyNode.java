package com.dingring.app.workflow.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.dingring.app.orchestrator.MessageRouter;
import com.dingring.common.util.JsonHelper;
import com.dingring.common.util.LogHelper;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.agent.AgentRepository;
import com.dingring.domain.group.Group;
import com.dingring.domain.group.GroupRepository;
import com.dingring.domain.workflow.StateKeys;
import com.dingring.infrastructure.aop.Event;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 意图分类节点：对用户消息做 LLM 意图判定
 * （CHAT 闲聊 / DISCUSS 讨论 / CONCLUDE 收束 / WORK 任务执行）。
 * <p>迁移自 {@link MessageRouter#route}，复用现有 MessageRouter 业务逻辑。
 * <p>意图分类 prompt 从 Nacos 加载（热更新），但 MessageRouter.route 内部仍用 PromptConstants（Phase C 暂不修改 MessageRouter 内部实现，避免破坏现有测试）。
 * <p>设计要点（方案 6.4 决策 2）：
 * <ul>
 *   <li>CONCLUDE 仅在当前有活跃话题时才可能成立（无活跃话题的收束请求按 CHAT 处理）</li>
 *   <li>判定失败降级 CHAT（宁可少建题，不可乱建题）</li>
 *   <li>路由判定 Agent 优先用专职判定器（feature.routeJudge=true），未配置则降级为群首成员</li>
 * </ul>
 * <p>条件边：SaaWorkflow 中 intent-classify 节点的条件边按 intent 值分流到 chat/ensure-topic/work/conclude。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntentClassifyNode implements NodeAction {

    private final MessageRouter messageRouter;
    private final AgentRepository agentRepository;
    private final GroupRepository groupRepository;

    /**
     * 对用户消息做意图分类。
     *
     * @param state OverAllState，包含 groupId/input/topicTitle（活跃话题标题，null 表示无活跃话题）
     * @return 状态更新：intent（CHAT/DISCUSS/CONCLUDE）、topicTitle（DISCUSS 时为拟定标题，其他为空）
     */
    @Override
    @Event(eventCode = "INTENT_CLASSIFY", eventName = "意图分类节点")
    public Map<String, Object> apply(OverAllState state) {
        Long groupId = state.<Long>value(StateKeys.GROUP_ID).orElse(null);
        String input = state.value(StateKeys.INPUT, "");
        String activeTopicTitle = state.<String>value(StateKeys.TOPIC_TITLE).orElse(null);

        // 预设意图跳过：REST API 强制收束 / DIVERGE 自动推进 / 收束确认等场景直接透传
        String presetIntent = state.<String>value(StateKeys.INTENT).orElse(null);
        if (presetIntent != null && !presetIntent.isBlank()) {
            LogHelper.printLog(IntentClassifyNode.class, "IntentClassifyNode.apply", "INTENT_CLASSIFY",
                    "意图已预设跳过LLM", "intent={}", presetIntent);
            return Map.of();
        }

        // 用 HashMap 而非 Map.of：groupId 可能为 null（防御性日志不应在入口先抛 NPE）
        Map<String, Object> logMap = new HashMap<>();
        logMap.put("groupId", groupId);
        logMap.put("inputLen", input == null ? 0 : input.length());
        logMap.put("hasActiveTopic", activeTopicTitle != null);
        LogHelper.printLog(IntentClassifyNode.class, "IntentClassifyNode.apply", "INTENT_CLASSIFY", "意图分类开始",
                "request={}", JsonHelper.mapToJsonStr(logMap));

        if (groupId == null || input == null || input.isBlank()) {
            // 防御性兜底：空输入按 CHAT 处理
            LogHelper.printWarnLog(IntentClassifyNode.class, "IntentClassifyNode.apply", "INTENT_CLASSIFY",
                    "缺少必要参数按CHAT兜底", "groupId={} inputLen={}",
                    groupId, input == null ? 0 : input.length());
            Map<String, Object> result = new HashMap<>();
            result.put(StateKeys.INTENT, "CHAT");
            return result;
        }

        // 加载路由判定 Agent：专职判定器优先，未配置则降级为群首成员
        Agent judge = loadRouteJudge(groupId);

        // 调用 MessageRouter 做意图判定（复用现有业务逻辑）
        MessageRouter.Route route = messageRouter.route(judge, input, activeTopicTitle);

        // 路由修正：有活跃话题时，CHAT 强制重映射为 DISCUSS
        // 原因：ChatNode 是闲聊节点不携带话题上下文，IN_PROGRESS 期间的消息应进入讨论节点推进深度
        if (route.intent() == MessageRouter.Intent.CHAT && activeTopicTitle != null) {
            LogHelper.printLog(IntentClassifyNode.class, "IntentClassifyNode.apply", "INTENT_CLASSIFY",
                    "活跃话题期间CHAT重映射为DISCUSS",
                    "activeTopic={} originalIntent={}", activeTopicTitle, route.intent());
            route = new MessageRouter.Route(
                    MessageRouter.Intent.DISCUSS, activeTopicTitle, route.confidence());
        }

        Map<String, Object> result = new HashMap<>();
        result.put(StateKeys.INTENT, route.intent().name());

        // 写入置信度（HIGH/LOW）—— EnsureTopicNode 依据 HIGH 立即建题 / LOW 累计达门槛建题
        result.put(StateKeys.CONFIDENCE, route.confidence().name());

        // DISCUSS 时写入拟定的 topicTitle（供 EnsureTopicNode 建题用）
        // 活跃话题期间重映射的 DISCUSS 使用现有话题标题，不新建议题
        if (route.intent() == MessageRouter.Intent.DISCUSS && !route.topicTitle().isBlank()) {
            result.put(StateKeys.TOPIC_TITLE, route.topicTitle());
        }

        LogHelper.printLog(IntentClassifyNode.class, "IntentClassifyNode.apply", "INTENT_CLASSIFY", "意图分类完成",
                "intent={} topicTitle={} confidence={}",
                route.intent(), route.topicTitle(), route.confidence());

        return result;
    }

    /**
     * 加载路由判定器 Agent。
     * <p>优先级（方案 6.3.2 保留 MessageRouter 设计）：
     * <ol>
     *   <li>DB 查 feature.routeJudge=true 的专职判定器（独立于群成员配置，不参与讨论）</li>
     *   <li>未配置则降级为群首个成员，并打 WARN 日志提醒补齐配置</li>
     * </ol>
     * 降级目的：主流程不中断，路由判定用群成员模型兜底。
     *
     * @param groupId 群 ID
     * @return 用于意图分类的 Agent
     */
    private Agent loadRouteJudge(Long groupId) {
        Optional<Agent> judge = agentRepository.findRouteJudge();
        if (judge.isPresent()) {
            LogHelper.printLog(IntentClassifyNode.class, "IntentClassifyNode.loadRouteJudge",
                    "INTENT_CLASSIFY", "命中专职路由判定器", "judge={}", judge.get().getName());
            return judge.get();
        }

        // 降级：群首成员
        Optional<Group> groupOpt = groupRepository.findById(groupId);
        if (groupOpt.isEmpty()) {
            throw new IllegalStateException("群不存在: " + groupId);
        }
        List<Agent> members = agentRepository.findByIds(groupOpt.get().memberAgentIds());
        if (members.isEmpty()) {
            throw new IllegalStateException("群内无 Agent 成员，也未配置路由判定器: " + groupId);
        }
        LogHelper.printWarnLog(IntentClassifyNode.class, "IntentClassifyNode.loadRouteJudge",
                "ROUTE_JUDGE_MISSING",
                "未配置路由判定器 Agent（feature.routeJudge=true），降级为群首个成员",
                "fallbackAgent={}", members.get(0).getName());
        return members.get(0);
    }
}
