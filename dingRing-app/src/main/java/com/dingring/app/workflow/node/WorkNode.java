package com.dingring.app.workflow.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.dingring.app.service.MessageAssembler;
import com.dingring.common.constant.WsConstants;
import com.dingring.common.util.JsonHelper;
import com.dingring.common.util.LogHelper;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.agent.AgentRepository;
import com.dingring.domain.group.Group;
import com.dingring.domain.group.GroupMessage;
import com.dingring.domain.group.GroupRepository;
import com.dingring.domain.group.MessageRepository;
import com.dingring.domain.group.MessageType;
import com.dingring.domain.group.SenderType;
import com.dingring.domain.service.AgentSpeakerService;
import com.dingring.domain.service.GroupBroadcastService;
import com.dingring.domain.service.LlmService.ChatTurn;
import com.dingring.domain.workflow.StateKeys;
import com.dingring.infrastructure.aop.Event;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流节点：处理 WORK 意图（用户要求执行具体任务，如生成文档/查询信息等）。
 * <p>Phase D 完善：用 ReactAgent（recursionLimit=15）+ 通用工具集执行深度 ReAct 任务。
 * <p>注意：当前 MessageRouter 尚未支持 WORK 意图（仅 CHAT/DISCUSS/CONCLUDE），
 * 本节点在 StateGraph 中保留占位路径，WORK 意图启用后可直接执行。
 * <p>设计要点：
 * <ul>
 *   <li>选群首成员作为工作 Agent（后续可配置专职工作 Agent）</li>
 *   <li>构建工作任务 systemPrompt（Agent 人设 + 任务指令）</li>
 *   <li>调用 agentSpeakerService.call() with ToolSet.WORK（recursionLimit=15，深度 ReAct）</li>
 *   <li>工作产出入库广播</li>
 * </ul>
 */
@Slf4j
@Component("workHandler")
@RequiredArgsConstructor
public class WorkNode implements NodeAction {

    private final GroupRepository groupRepository;
    private final AgentRepository agentRepository;
    private final MessageRepository messageRepository;
    private final MessageAssembler messageAssembler;
    private final AgentSpeakerService agentSpeakerService;
    private final GroupBroadcastService groupBroadcastService;

    /**
     * WORK 意图处理：选 Agent → 构建 systemPrompt → ReAct 执行 → 入库广播。
     *
     * @param state OverAllState，包含 groupId/input
     * @return 状态更新：workResult（工作产出文本）
     */
    @Override
    @Event(eventCode = "WORK_NODE", eventName = "工作流节点")
    public Map<String, Object> apply(OverAllState state) {
        Long groupId = state.<Long>value(StateKeys.GROUP_ID).orElse(null);
        String input = state.value(StateKeys.INPUT, "");

        LogHelper.printLog(WorkNode.class, "WorkNode.apply", "WORK_NODE",
                "WORK意图处理开始", "request={}", JsonHelper.mapToJsonStr(Map.of(
                        "groupId", groupId,
                        "inputLen", input == null ? 0 : input.length())));

        if (groupId == null || input == null || input.isBlank()) {
            LogHelper.printWarnLog(WorkNode.class, "WorkNode.apply", "WORK_NODE",
                    "缺少必要参数", "groupId={} inputLen={}", groupId, input == null ? 0 : input.length());
            return Map.of();
        }

        // 选工作 Agent：群首成员（后续可配置专职工作 Agent）
        Group group = groupRepository.findById(groupId).orElse(null);
        if (group == null) {
            LogHelper.printWarnLog(WorkNode.class, "WorkNode.apply", "WORK_NODE",
                    "群不存在", "groupId={}", groupId);
            return Map.of();
        }
        List<Agent> members = agentRepository.findByIds(group.memberAgentIds());
        if (members.isEmpty()) {
            LogHelper.printWarnLog(WorkNode.class, "WorkNode.apply", "WORK_NODE",
                    "群内无 Agent 成员", "groupId={}", groupId);
            return Map.of();
        }
        Agent workAgent = members.get(0);

        // 通知群聊：任务开始
        groupBroadcastService.broadcast(groupId, WsConstants.AGENT_TYPING, Map.of(
                "groupId", groupId,
                "agentId", workAgent.getId(),
                "agentName", workAgent.getName(),
                "isTyping", true));

        try {
            // 构建工作任务 systemPrompt
            String systemPrompt = buildWorkPrompt(workAgent);

            // 构建 context（供 Hook 读取 groupId/userId/speakerAgentId）
            Map<String, Object> context = new HashMap<>();
            context.put("groupId", groupId);
            context.put("userId", 1L);
            context.put("speakerAgentId", workAgent.getId());

            // 深度 ReAct 执行（recursionLimit=15，ToolSet.WORK）
            AgentSpeakerService.AgentResult result = agentSpeakerService.call(
                    workAgent, systemPrompt,
                    List.of(ChatTurn.user(input)),
                    AgentSpeakerService.ToolSet.WORK, context);

            String workResult = result.content();
            if (workResult == null || workResult.isBlank()) {
                LogHelper.printWarnLog(WorkNode.class, "WorkNode.apply", "WORK_NODE",
                        "工作 Agent 返回空结果", "agent={}", workAgent.getName());
                return Map.of();
            }

            // 工作产出入库广播
            GroupMessage msg = new GroupMessage();
            msg.setChatGroupId(groupId);
            msg.setTopicId(null);
            msg.setSenderId(workAgent.getId());
            msg.setSenderType(SenderType.AGENT);
            msg.setMessageType(MessageType.TEXT);
            msg.setContent(workResult);
            messageRepository.save(msg);
            groupBroadcastService.broadcast(groupId, WsConstants.NEW_MESSAGE,
                    messageAssembler.toDto(msg));

            LogHelper.printLog(WorkNode.class, "WorkNode.apply", "WORK_NODE",
                    "WORK意图处理完成", "agent={} 结果长度={} hasToolCalls={}",
                    workAgent.getName(), workResult.length(), result.hasToolCalls());

            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("workResult", workResult);
            return resultMap;
        } catch (Exception e) {
            LogHelper.printWarnLog(WorkNode.class, "WorkNode.apply", "WORK_NODE",
                    "工作 Agent 执行失败", "agent={} 错误: {}", workAgent.getName(), e.getMessage(), e);
            groupBroadcastService.broadcast(groupId, WsConstants.ERROR, Map.of(
                    "success", false,
                    "message", "任务执行失败：" + e.getMessage()));
            return Map.of();
        } finally {
            groupBroadcastService.broadcast(groupId, WsConstants.AGENT_TYPING, Map.of(
                    "groupId", groupId,
                    "agentId", workAgent.getId(),
                    "agentName", workAgent.getName(),
                    "isTyping", false));
        }
    }

    /** 构建工作任务 systemPrompt（Agent 人设 + 任务指令） */
    private String buildWorkPrompt(Agent agent) {
        StringBuilder sp = new StringBuilder();
        if (agent.getSystemPrompt() != null && !agent.getSystemPrompt().isBlank()) {
            sp.append(agent.getSystemPrompt()).append("\n\n");
        }
        sp.append("你是一个能够使用工具完成结构化任务的工作 Agent。");
        sp.append("请根据用户的需求，主动调用可用的工具来完成任务。");
        sp.append("如果工具不足以完成任务，请基于你的知识给出最佳方案。");
        return sp.toString();
    }
}
