package com.dingring.app.workflow.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.dingring.app.workflow.ConclusionService;
import com.dingring.common.util.JsonHelper;
import com.dingring.common.util.LogHelper;
import com.dingring.domain.workflow.StateKeys;
import com.dingring.infrastructure.aop.Event;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 收束节点（图内调度器）：生成结论的实际逻辑已迁到 {@link ConclusionService}。
 * <p>本节点只负责：校验 topicId → 调用 {@link ConclusionService#triggerAsync}（同步完成
 * IN_PROGRESS→CONCLUDING 状态流转 + 把 LLM 生成提交到独立 {@code ConclusionExecutor}），
 * 然后返回 concluded=true 结束本轮讨论——不再在群执行器上内联跑结论 LLM（解决整群阻塞）。
 * <p>幂等：Topic 已 CONCLUDING/CLOSED 时 triggerAsync 直接跳过流转/忽略，结论生成由
 * ConclusionService 的 per-topic 守卫 + 乐观锁保证不重复。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConcludeNode implements NodeAction {

    private final ConclusionService conclusionService;

    @Override
//    @Event(eventCode = "CONCLUDE_NODE", eventName = "收束节点")
    public Map<String, Object> apply(OverAllState state) {

        LogHelper.printLog(ConcludeNode.class, "ConcludeNode.apply",
                "CONCLUDE_NODE", "开始执行收束节点",
                "state={}", JsonHelper.overAllStateToJsonStr(state));

        Long topicId = state.<Long>value(StateKeys.TOPIC_ID).orElse(null);
        Long groupId = state.<Long>value(StateKeys.GROUP_ID).orElse(null);
        String triggeredBy = state.value(StateKeys.TRIGGERED_BY, "USER");
        Long designatedConcluderId = state.<Long>value(StateKeys.CONCLUDER_AGENT_ID).orElse(null);

        if (topicId == null) {
            throw new IllegalStateException("ConcludeNode 缺少必要参数 topicId");
        }

        // 同步流转 + 异步提交结论生成（不阻塞本节点所在群执行器）
        conclusionService.triggerAsync(topicId, groupId, triggeredBy, designatedConcluderId);

        Map<String, Object> result = new HashMap<>();
        result.put(StateKeys.CONCLUDED, true);
        if (designatedConcluderId != null) {
            result.put(StateKeys.CONCLUDER_AGENT_ID, designatedConcluderId);
        }
        return result;
    }
}
