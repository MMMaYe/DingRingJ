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

import java.util.Map;

/**
 * 工作流节点：处理 WORK 意图（用户要求执行具体任务，如生成文档/查询信息等）。
 * <p>设计要点（方案 6.4）：
 * <ul>
 *   <li>Phase C 占位实现：当前 MessageRouter 仅支持 CHAT/DISCUSS/CONCLUDE 三种意图，
 *       WORK 是 Phase 2 扩展意图（用户要求 Agent 执行结构化任务）</li>
 *   <li>保留节点和条件边占位，确保 StateGraph 流程完整，后续 Phase 2 可直接填充逻辑</li>
 *   <li>当前命中路径：意图分类返回 WORK 时进入此节点，记录日志后结束流程</li>
 * </ul>
 */
@Slf4j
@Component("workHandler")
@RequiredArgsConstructor
public class WorkNode implements NodeAction {

    /**
     * WORK 意图处理（Phase 2 占位）。
     *
     * @param state OverAllState，包含 groupId/input
     * @return 空状态更新（Phase 2 填充具体任务执行逻辑后写入结果）
     */
    @Override
    @Event(eventCode = "WORK_NODE", eventName = "工作流节点")
    public Map<String, Object> apply(OverAllState state) {
        Long groupId = state.<Long>value(StateKeys.GROUP_ID).orElse(null);
        String input = state.value(StateKeys.INPUT, "");

        LogHelper.printLog(WorkNode.class, "WorkNode.apply", "WORK_NODE",
                "WORK意图处理（Phase 2 占位）", "request={}", JsonHelper.mapToJsonStr(Map.of(
                        "groupId", groupId,
                        "inputLen", input == null ? 0 : input.length())));

        // Phase 2 待实现：解析任务意图 → 调用工具/函数 → 返回结构化结果 → 入库广播
        LogHelper.printLog(WorkNode.class, "WorkNode.apply", "WORK_NODE",
                "WORK意图暂未实现，按空操作处理", "groupId={}", groupId);

        return Map.of();
    }
}
