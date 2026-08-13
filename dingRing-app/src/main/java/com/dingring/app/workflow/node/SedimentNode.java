package com.dingring.app.workflow.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.dingring.common.util.JsonHelper;
import com.dingring.common.util.LogHelper;
import com.dingring.domain.workflow.StateKeys;
import com.dingring.infrastructure.aop.Event;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 沉淀节点：讨论结束后沉淀知识产物。
 * <p>设计要点（方案 6.4）：
 * <ul>
 *   <li>知识卡片生成由 {@link com.dingring.app.event.CardEventHandler} 监听 TopicClosed 事件异步驱动，
 *       不在本节点同步执行（避免 LLM 调用阻塞 StateGraph）</li>
 *   <li>本节点负责记录讨论沉淀日志，后续可扩展为调用 MemoryService 沉淀结论到长期记忆</li>
 *   <li>无论 ConcludeNode 成功或失败都会经过此节点（流程图 conclude → sediment → END）</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SedimentNode implements NodeAction {

    /**
     * 记录讨论沉淀日志。
     *
     * @param state OverAllState，包含 topicId/conclusion/concluderAgentId/concluded
     * @return 空状态更新（沉淀由事件驱动，无需写入 state）
     */
    @Override
    @Event(eventCode = "SEDIMENT_NODE", eventName = "沉淀节点")
    public Map<String, Object> apply(OverAllState state) {
        Long topicId = state.<Long>value(StateKeys.TOPIC_ID).orElse(null);
        Boolean concluded = state.value(StateKeys.CONCLUDED, false);
        String conclusion = state.<String>value(StateKeys.CONCLUSION).orElse(null);
        Long concluderAgentId = state.<Long>value(StateKeys.CONCLUDER_AGENT_ID).orElse(null);

        // WORK 流程无 topicId（topicId 为 null），Map.of 不接受 null，需用 HashMap
        Map<String, Object> logFields = new HashMap<>();
        logFields.put("topicId", topicId);
        logFields.put("concluded", concluded);
        logFields.put("conclusionLen", conclusion == null ? 0 : conclusion.length());
        logFields.put("concluderAgentId", concluderAgentId);

        LogHelper.printLog(SedimentNode.class, "SedimentNode.apply", "SEDIMENT_NODE", "讨论沉淀",
                "request={}", JsonHelper.mapToJsonStr(logFields));

        if (Boolean.TRUE.equals(concluded)) {
            // 收束已触发：结论由 ConclusionService 异步生成，知识卡片由 CardEventHandler 监听 TopicClosed 事件异步生成
            // 后续可在此扩展：调用 MemoryService 沉淀结论到长期记忆
            LogHelper.printLog(SedimentNode.class, "SedimentNode.apply", "SEDIMENT_NODE", "收束已触发，结论异步生成中",
                    "topicId={}", topicId);
        } else {
            LogHelper.printLog(SedimentNode.class, "SedimentNode.apply", "SEDIMENT_NODE", "未触发收束跳过沉淀",
                    "topicId={}", topicId);
        }

        return Map.of();
    }
}
