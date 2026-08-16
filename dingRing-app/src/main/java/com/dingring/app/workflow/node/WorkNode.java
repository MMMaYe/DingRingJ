package com.dingring.app.workflow.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
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
import com.dingring.domain.service.LlmService;
import com.dingring.domain.service.GroupBroadcastService;
import com.dingring.domain.service.LlmService.ChatTurn;
import com.dingring.domain.workflow.StateKeys;
import com.dingring.infrastructure.aop.Event;
import com.dingring.infrastructure.agent.runtime.SupervisorAgentFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流节点：处理 WORK 意图（用户要求执行具体任务，如生成文档/查询信息等）。
 * <p>Phase F 增强：Supervisor 编排模式——将群成员 Agent 包装为子 Agent 工具，
 * 由 Supervisor 拆解任务并委派执行；通过 {@code dingring.supervisor.enabled} 开关控制，
 * 关闭时降级为 Phase D 的单 Agent 深度 ReAct（方案十一回退策略）。
 * <p>Phase G 启用：MessageRouter 现已支持 WORK 意图分类（四选一），
 * WORK 消息经 StateGraph 条件边路由到本节点；任务开始时广播 {@link WsConstants#WORK_TASK_STARTED}。
 * <p>设计要点：
 * <ul>
 *   <li>单 Agent 模式：选群首成员作为工作 Agent（后续可配置专职工作 Agent）</li>
 *   <li>Supervisor 模式：选群首成员作为编排者（模型配置来源），其余成员作为执行者</li>
 *   <li>调用 llmService.chat() with ToolSet.WORK（recursionLimit=40，深度 ReAct）</li>
 *   <li>工作产出入库广播</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkNode implements NodeAction {

    private final GroupRepository groupRepository;
    private final AgentRepository agentRepository;
    private final MessageRepository messageRepository;
    private final MessageAssembler messageAssembler;
    private final LlmService llmService;
    private final GroupBroadcastService groupBroadcastService;
    private final SupervisorAgentFactory supervisorAgentFactory;

    /** Supervisor 编排模式开关（false = 降级单 Agent，方案十一回退策略） */
    @Value("${dingring.supervisor.enabled:false}")
    private boolean supervisorEnabled;

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

        // 用 HashMap 而非 Map.of：groupId 可能为 null（防御性日志不应在入口先抛 NPE）
        Map<String, Object> logMap = new HashMap<>();
        logMap.put("groupId", groupId);
        logMap.put("inputLen", input == null ? 0 : input.length());
        LogHelper.printLog(WorkNode.class, "WorkNode.apply", "WORK_NODE",
                "WORK意图处理开始", "request={}", JsonHelper.mapToJsonStr(logMap));

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

        // 选工作 Agent：优先被 @ 提及的 Agent（用户明确指派对象），否则群首成员
        List<Long> mentionedAgentIds = state.value(StateKeys.MENTIONED_AGENT_IDS, List.<Long>of());
        Agent workAgent = pickWorkAgent(members, mentionedAgentIds);
        LogHelper.printLog(WorkNode.class, "WorkNode.apply", "WORK_NODE",
                "选定工作 Agent", "groupId={} workAgent={} mentioned={}",
                groupId, workAgent.getName(), mentionedAgentIds);

        // Supervisor 模式需要至少 2 名成员（1 编排者 + 1 执行者），否则自动降级单 Agent
        if (supervisorEnabled && members.size() >= 2) {
            LogHelper.printLog(WorkNode.class, "WorkNode.apply", "WORK_NODE",
                    "选择 Supervisor 编排模式", "groupId={} supervisor={} 成员数={}",
                    groupId, workAgent.getName(), members.size());
            return executeWithSupervisor(state, groupId, input, group, members, workAgent, mentionedAgentIds);
        }
        LogHelper.printLog(WorkNode.class, "WorkNode.apply", "WORK_NODE",
                "选择单 Agent 深度 ReAct 模式", "groupId={} workAgent={} supervisorEnabled={} 成员数={}",
                groupId, workAgent.getName(), supervisorEnabled, members.size());
        return executeWithSingleAgent(state, groupId, input, group, workAgent);
    }

    /**
     * 选择执行任务的 Agent：优先用户 @ 提及的群成员（首个命中，按提及顺序），
     * 无提及或提及对象不在群内时回退群首成员。
     */
    private Agent pickWorkAgent(List<Agent> members, List<Long> mentionedAgentIds) {
        if (mentionedAgentIds != null && !mentionedAgentIds.isEmpty()) {
            for (Long id : mentionedAgentIds) {
                Agent hit = members.stream().filter(m -> m.getId().equals(id)).findFirst().orElse(null);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return members.get(0);
    }

    /**
     * 单 Agent 模式（Phase D 原实现）：选群首成员深度 ReAct 执行。
     * <p>保留为 Supervisor 模式的降级链（开关关闭 / 成员不足 / Supervisor 异常时兜底）。
     */
    private Map<String, Object> executeWithSingleAgent(OverAllState state, Long groupId, String input,
                                                       Group group, Agent workAgent) {

        // 通知群聊：任务开始（WORK_TASK_STARTED 前端 toast 提示）
        broadcastWorkTaskStarted(groupId, workAgent, input, false);

        // 通知群聊：打字状态
        groupBroadcastService.broadcast(groupId, WsConstants.AGENT_TYPING, Map.of(
                "groupId", groupId,
                "agentId", workAgent.getId(),
                "agentName", workAgent.getName(),
                "isTyping", true));

        try {
            // 构建工作任务 systemPrompt
            String systemPrompt = buildWorkPrompt(workAgent);

            // 构建 context（供 Hook 读取 groupId/userId/speakerAgentId/ragQuery）
            Map<String, Object> context = new HashMap<>();
            context.put("groupId", groupId);
            context.put("userId", 1L);
            context.put("speakerAgentId", workAgent.getId());
            // 场景意图（InjectKbHook：WORK 仅 kb 源）
            context.put(StateKeys.INTENT, "WORK");
            // RAG 检索词：以任务输入为查询（InjectKbHook 读取）
            context.put("ragQuery", input);

            // 深度 ReAct 执行（recursionLimit=40，ToolSet.WORK）
            long callStart = System.currentTimeMillis();
            LogHelper.printLog(WorkNode.class, "WorkNode.executeWithSingleAgent", "WORK_NODE",
                    "开始调用工作 Agent 深度 ReAct", "groupId={} agent={} inputLen={}",
                    groupId, workAgent.getName(), input.length());
            LlmService.AgentResult result = llmService.chat(
                    workAgent, systemPrompt,
                    List.of(ChatTurn.user(input)),
                    LlmService.ToolSet.WORK, context);
            LogHelper.printLog(WorkNode.class, "WorkNode.executeWithSingleAgent", "WORK_NODE",
                    "工作 Agent 调用完成", "groupId={} agent={} 耗时={}ms",
                    groupId, workAgent.getName(), System.currentTimeMillis() - callStart);

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

    /**
     * Supervisor 编排模式：群首成员为编排者，其余成员为执行者（子 Agent 工具）。
     * <p>执行流程：构建 Supervisor → 注入群上下文 → 委派执行 → 结果入库广播。
     * <p>降级策略：Supervisor 构建或执行失败时，记录 WARN 并回退单 Agent 模式，
     * 保证 WORK 意图不因编排故障而完全不可用（方案十一：WorkNode 降级为单 Agent）。
     */
    private Map<String, Object> executeWithSupervisor(OverAllState state, Long groupId, String input,
                                                      Group group, List<Agent> members, Agent supervisorAgent,
                                                      List<Long> mentionedAgentIds) {
        // 群首/被 @ 成员作为编排者（模型配置来源），其余成员作为执行者
        List<Agent> workers = members.stream()
                .filter(m -> !m.getId().equals(supervisorAgent.getId()))
                .toList();

        // 通知群聊：任务开始（WORK_TASK_STARTED 前端 toast 提示）
        broadcastWorkTaskStarted(groupId, supervisorAgent, input, true);

        groupBroadcastService.broadcast(groupId, WsConstants.AGENT_TYPING, Map.of(
                "groupId", groupId,
                "agentId", supervisorAgent.getId(),
                "agentName", supervisorAgent.getName(),
                "isTyping", true));

        try {
            // 构建 Supervisor：worker 成员名清单注入提示词，供其按名委派
            String supervisorPrompt = buildSupervisorPrompt(supervisorAgent, workers,
                    resolveMentionedNames(members, mentionedAgentIds));
            ReactAgent supervisor = supervisorAgentFactory.buildSupervisor(supervisorAgent, supervisorPrompt, workers);

            // 注入群上下文：Supervisor 的 state 依赖 Hook（记忆/画像/名单/RAG）靠这些 key 工作
            Map<String, Object> inputs = new HashMap<>();
            inputs.put("messages", List.of(new UserMessage(input)));
            inputs.put("groupId", groupId);
            inputs.put("userId", 1L);
            inputs.put("speakerAgentId", supervisorAgent.getId());
            inputs.put("ragQuery", input);
            // 场景意图（InjectKbHook：WORK 仅 kb 源，Supervisor 运行前注入一次）
            inputs.put(StateKeys.INTENT, "WORK");
            inputs.put(StateKeys.MENTIONED_AGENT_IDS, mentionedAgentIds);

            LogHelper.printLog(WorkNode.class, "executeWithSupervisor", "WORK_NODE",
                    "Supervisor 委派执行开始", "groupId={} supervisor={} workers={}",
                    groupId, supervisorAgent.getName(), workers.stream().map(Agent::getName).toList());

            AssistantMessage response = supervisor.call(inputs);
            String workResult = response.getText() != null ? response.getText() : "";
            if (workResult.isBlank()) {
                LogHelper.printWarnLog(WorkNode.class, "executeWithSupervisor", "WORK_NODE",
                        "Supervisor 返回空结果，降级单 Agent", "groupId={}", groupId);
                return executeWithSingleAgent(state, groupId, input, group, supervisorAgent);
            }

            broadcastWorkResult(groupId, supervisorAgent, workResult);
            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("workResult", workResult);
            return resultMap;
        } catch (Exception e) {
            LogHelper.printWarnLog(WorkNode.class, "executeWithSupervisor", "WORK_NODE",
                    "Supervisor 执行失败，降级单 Agent", "groupId={} 错误: {}", groupId, e.getMessage());
            return executeWithSingleAgent(state, groupId, input, group, supervisorAgent);
        } finally {
            groupBroadcastService.broadcast(groupId, WsConstants.AGENT_TYPING, Map.of(
                    "groupId", groupId,
                    "agentId", supervisorAgent.getId(),
                    "agentName", supervisorAgent.getName(),
                    "isTyping", false));
        }
    }

    /**
     * 广播 WORK 任务开始提示。
     *
     * @param groupId       群 ID
     * @param agent         执行任务的主 Agent（单 Agent 模式为工作 Agent，Supervisor 模式为编排者）
     * @param taskDescription 任务原文（用户输入）
     * @param supervisorMode 是否 Supervisor 编排模式
     */
    private void broadcastWorkTaskStarted(Long groupId, Agent agent, String taskDescription, boolean supervisorMode) {
        groupBroadcastService.broadcast(groupId, WsConstants.WORK_TASK_STARTED, Map.of(
                "groupId", groupId,
                "agentName", agent.getName(),
                "taskDescription", taskDescription,
                "supervisorMode", supervisorMode));
        LogHelper.printLog(WorkNode.class, "broadcastWorkTaskStarted", "WORK_NODE",
                "WORK 任务开始广播", "groupId={} agent={} supervisorMode={}", groupId, agent.getName(), supervisorMode);
    }

    /** 构建 Supervisor systemPrompt（编排者人设 + 拆解/委派/汇总协议 + 可委派成员清单） */
    private String buildSupervisorPrompt(Agent supervisorAgent, List<Agent> workers, List<String> mentionedNames) {
        StringBuilder sp = new StringBuilder();
        if (supervisorAgent.getSystemPrompt() != null && !supervisorAgent.getSystemPrompt().isBlank()) {
            sp.append(supervisorAgent.getSystemPrompt()).append("\n\n");
        }
        sp.append("你是工作流编排者。收到用户任务后，请按以下步骤执行：\n");
        sp.append("1. 将任务拆解为可并行/串行的子任务；\n");
        sp.append("2. 按子任务性质选择最合适的执行者（工具名即成员花名），通过工具委派；\n");
        sp.append("3. 委派时在参数中携带必要上下文（群 ID、任务背景），保证执行者理解任务；\n");
        sp.append("4. 收集所有子任务结果后，汇总为完整的最终答案输出。\n");
        sp.append("可委派的执行者：" + workers.stream().map(Agent::getName).reduce((a, b) -> a + "、" + b).orElse("无") + "\n");
        if (mentionedNames != null && !mentionedNames.isEmpty()) {
            sp.append("用户本次任务点名了「" + String.join("、", mentionedNames) + "」，请优先将该成员作为主要执行者委派。\n");
        }
        sp.append("群成员之间各有专长，请让合适的成员处理合适的问题。");
        return sp.toString();
    }

    /** 将 @ 提及的 Agent ID 解析为花名（仅保留群内成员，便于提示词点名） */
    private List<String> resolveMentionedNames(List<Agent> members, List<Long> mentionedAgentIds) {
        if (mentionedAgentIds == null || mentionedAgentIds.isEmpty()) {
            return List.of();
        }
        return mentionedAgentIds.stream()
                .flatMap(id -> members.stream().filter(m -> m.getId().equals(id)))
                .map(Agent::getName)
                .toList();
    }

    /** 工作结果入库广播（Supervisor 产出以编排者身份发言） */
    private void broadcastWorkResult(Long groupId, Agent supervisorAgent, String workResult) {
        GroupMessage msg = new GroupMessage();
        msg.setChatGroupId(groupId);
        msg.setTopicId(null);
        msg.setSenderId(supervisorAgent.getId());
        msg.setSenderType(SenderType.AGENT);
        msg.setMessageType(MessageType.TEXT);
        msg.setContent(workResult);
        messageRepository.save(msg);
        groupBroadcastService.broadcast(groupId, WsConstants.NEW_MESSAGE, messageAssembler.toDto(msg));
        LogHelper.printLog(WorkNode.class, "broadcastWorkResult", "WORK_NODE",
                "WORK 任务完成", "groupId={} supervisor={} 结果长度={}",
                groupId, supervisorAgent.getName(), workResult.length());
    }
}
