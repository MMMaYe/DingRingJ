package com.dingring.app.workflow.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.dingring.app.orchestrator.ContextBuilder;
import com.dingring.app.orchestrator.MessageContext;
import com.dingring.app.orchestrator.SpeakerScheduler;
import com.dingring.app.service.MessageAssembler;
import com.dingring.common.constant.WsConstants;
import com.dingring.common.exception.BizException;
import com.dingring.common.exception.ErrorCode;
import com.dingring.common.util.JsonHelper;
import com.dingring.common.util.LogHelper;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.agent.AgentRepository;
import com.dingring.domain.discussion.Topic;
import com.dingring.domain.discussion.TopicRepository;
import com.dingring.domain.event.TopicClosed;
import com.dingring.domain.group.Group;
import com.dingring.domain.group.GroupMessage;
import com.dingring.domain.group.GroupRepository;
import com.dingring.domain.group.MessageRepository;
import com.dingring.domain.group.MessageType;
import com.dingring.domain.group.SenderType;
import com.dingring.domain.service.AgentSpeakerService;
import com.dingring.domain.service.DomainEventPublisher;
import com.dingring.domain.service.GroupBroadcastService;
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
 * 收束节点：生成 STAR 结论，关闭 Topic，广播收束事件。
 * <p>迁移自 {@link com.dingring.app.orchestrator.ChatOrchestrator#generateConclusion}。
 * <p>Phase C 设计：StateGraph 内同步执行结论生成（原 ChatOrchestrator 异步执行，Phase C 改为同步
 * ——DiscussionEngine 主循环等待 advance 返回，同步更可控且避免异步上下文丢失）。
 * <p>流程：startConcluding → 选 concluder → 构建 context → LLM 生成结论 → close Topic → 广播 + 事件
 * <p>失败回退：rollbackToInProgress + 广播 ERROR，discussMode 保持 CONCLUDE 但 concluded=false。
 */
@Slf4j
@Component("concludeHandler")
@RequiredArgsConstructor
public class ConcludeNode implements NodeAction {

    private final TopicRepository topicRepository;
    private final GroupRepository groupRepository;
    private final AgentRepository agentRepository;
    private final MessageRepository messageRepository;
    private final SpeakerScheduler speakerScheduler;
    private final ContextBuilder contextBuilder;
    private final MessageAssembler messageAssembler;
    private final AgentSpeakerService agentSpeakerService;
    private final DomainEventPublisher eventPublisher;
    private final GroupBroadcastService groupBroadcastService;

    /**
     * 生成结论并关闭 Topic。
     *
     * @param state OverAllState，包含 topicId/groupId/triggeredBy/concluderAgentId
     * @return 状态更新：concluded + conclusion + concluderAgentId（失败时 concluded=false）
     */
    @Override
    @Event(eventCode = "CONCLUDE_NODE", eventName = "收束节点")
    public Map<String, Object> apply(OverAllState state) {
        Long topicId = state.<Long>value(StateKeys.TOPIC_ID).orElse(null);
        Long groupId = state.<Long>value(StateKeys.GROUP_ID).orElse(null);
        String triggeredBy = state.value(StateKeys.TRIGGERED_BY, "USER");
        Long designatedConcluderId = state.<Long>value(StateKeys.CONCLUDER_AGENT_ID).orElse(null);

        LogHelper.printLog(ConcludeNode.class, "ConcludeNode.apply", "CONCLUDE_NODE", "收束开始",
                "request={}", JsonHelper.mapToJsonStr(Map.of(
                        "topicId", topicId,
                        "groupId", groupId,
                        "triggeredBy", triggeredBy,
                        "designatedConcluderId", designatedConcluderId)));

        if (topicId == null) {
            throw new IllegalStateException("ConcludeNode 缺少必要参数 topicId");
        }

        Topic topic = topicRepository.findById(topicId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "主题不存在: " + topicId));

        // IN_PROGRESS → CONCLUDING（乐观锁）；ChatOrchestrator 同步触发时已是 CONCLUDING 则跳过
        if (topic.isInProgress()) {
            topic.startConcluding();
            if (!topicRepository.update(topic)) {
                throw new BizException(ErrorCode.TOPIC_NOT_IN_PROGRESS, "主题状态已变更，请刷新后重试");
            }
            pushTopicStatus(topic, "IN_PROGRESS");
        }

        Group group = groupId != null ? groupRepository.findById(groupId).orElse(null) : null;
        Agent concluder = resolveConcluder(group, topic, designatedConcluderId);
        if (concluder == null) {
            LogHelper.printWarnLog(ConcludeNode.class, "ConcludeNode.apply", "CONCLUDE_NODE",
                    "无可用总结Agent回滚", "topicId={} 指定AgentId={}", topicId, designatedConcluderId);
            rollbackConclusion(topic, "群内没有可用的总结 Agent");
            Map<String, Object> result = new HashMap<>();
            result.put(StateKeys.CONCLUDED, false);
            return result;
        }

        LogHelper.printLog(ConcludeNode.class, "ConcludeNode.apply", "CONCLUDE_NODE", "开始生成结论",
                "topicId={} 总结Agent={} triggeredBy={}", topicId, concluder.getName(), triggeredBy);
        groupBroadcastService.broadcast(topic.getChatGroupId(), WsConstants.AGENT_TYPING, Map.of(
                "groupId", topic.getChatGroupId(),
                "agentId", concluder.getId(),
                "agentName", concluder.getName(),
                "isTyping", true));
        try {
            ContextBuilder.LlmContext ctx = contextBuilder.buildForConclusion(
                    concluder, topic.getChatGroupId(), topic.getId(), topic.getTitle(),
                    messageAssembler::resolveSenderName);
            // 构建 ReactAgent 上下文（群记忆/用户画像由 Hook 动态注入）
            Map<String, Object> context = new HashMap<>();
            context.put("groupId", topic.getChatGroupId());
            context.put("topicId", topic.getId());
            context.put("userId", 1L);  // 当前单用户系统默认 ID
            context.put("speakerAgentId", concluder.getId());
            // 收束节点用非流式 call（不需要流式输出，失败重试 1 次）
            AgentSpeakerService.AgentResult agentResult;
            try {
                agentResult = agentSpeakerService.call(concluder, ctx.systemPrompt(), ctx.turns(),
                        AgentSpeakerService.ToolSet.CONCLUDE, context);
            } catch (Exception first) {
                LogHelper.printWarnLog(ConcludeNode.class, "ConcludeNode.apply", "CONCLUDE_NODE",
                        "LLM首次失败重试", "agent={} 失败原因: {}", concluder.getName(), first.getMessage());
                agentResult = agentSpeakerService.call(concluder, ctx.systemPrompt(), ctx.turns(),
                        AgentSpeakerService.ToolSet.CONCLUDE, context);
            }
            String conclusion = agentResult.content();
            if (conclusion == null || conclusion.isBlank()) {
                throw new BizException(ErrorCode.TOPIC_CONCLUSION_FAILED, "总结 Agent 返回空结论");
            }
            // 结论中不应残留协作标记
            conclusion = ContextBuilder.stripMarkers(conclusion);

            // CONCLUDING → CLOSED
            topic.close(conclusion, concluder.getId());
            topicRepository.update(topic);
            LogHelper.printLog(ConcludeNode.class, "ConcludeNode.apply", "CONCLUDE_NODE", "结论生成成功主题已关闭",
                    "topicId={} 总结Agent={} 结论长度={}", topicId, concluder.getName(), conclusion.length());

            long messageCount = messageRepository.countByTopicId(topic.getId());
            saveSystemNotice(topic.getChatGroupId(), topic.getId(),
                    "讨论「" + topic.getTitle() + "」已结束，结论由「" + concluder.getName() + "」生成");
            groupBroadcastService.broadcast(topic.getChatGroupId(), WsConstants.TOPIC_CLOSED, Map.of(
                    "groupId", topic.getChatGroupId(),
                    "topicId", topic.getId(),
                    "title", topic.getTitle(),
                    "conclusion", conclusion,
                    "messageCount", messageCount,
                    "closedAt", topic.getClosedAt().toString()));
            eventPublisher.publish(new TopicClosed(topic.getId(), topic.getChatGroupId(), topic.getTitle(),
                    conclusion, messageCount, triggeredBy, concluder.getId()));

            Map<String, Object> result = new HashMap<>();
            result.put(StateKeys.CONCLUDED, true);
            result.put(StateKeys.CONCLUSION, conclusion);
            result.put(StateKeys.CONCLUDER_AGENT_ID, concluder.getId());
            return result;
        } catch (Exception e) {
            LogHelper.printWarnLog(ConcludeNode.class, "ConcludeNode.apply", "CONCLUDE_NODE",
                    "结论生成失败", "topicId={}", topicId, e);
            rollbackConclusion(topic, e.getMessage());
            Map<String, Object> result = new HashMap<>();
            result.put(StateKeys.CONCLUDED, false);
            return result;
        } finally {
            groupBroadcastService.broadcast(topic.getChatGroupId(), WsConstants.AGENT_TYPING, Map.of(
                    "groupId", topic.getChatGroupId(),
                    "agentId", concluder.getId(),
                    "agentName", concluder.getName(),
                    "isTyping", false));
        }
    }

    /** 解析总结 Agent：指定优先；否则按调度评分选最高的成员 Agent */
    private Agent resolveConcluder(Group group, Topic topic, Long designatedId) {
        if (designatedId != null) {
            return agentRepository.findById(designatedId).orElse(null);
        }
        if (group == null) {
            return null;
        }
        List<Agent> candidates = agentRepository.findByIds(group.memberAgentIds());
        if (candidates.isEmpty()) {
            return null;
        }
        Map<Long, Long> speakCounts = new HashMap<>();
        for (Long agentId : group.memberAgentIds()) {
            speakCounts.put(agentId, messageRepository.countByTopicIdAndSender(topic.getId(), agentId, SenderType.AGENT));
        }
        MessageContext ctx = MessageContext.builder()
                .groupId(group.getId())
                .topicId(topic.getId())
                .content("")
                .mentionedAgentIds(List.of())
                .repliedToAgentId(null)
                .speakCounts(speakCounts)
                .build();
        List<SpeakerScheduler.ScoredAgent> ranked = speakerScheduler.rank(candidates, ctx);
        return ranked.isEmpty() ? null : ranked.get(0).agent();
    }

    private void rollbackConclusion(Topic topic, String reason) {
        try {
            topic.rollbackToInProgress();
            topicRepository.update(topic);
            pushTopicStatus(topic, "CONCLUDING");
        } catch (Exception ex) {
            LogHelper.printWarnLog(ConcludeNode.class, "ConcludeNode.rollbackConclusion", "CONCLUDE_NODE",
                    "回退异常", "topicId={}", topic.getId(), ex);
        }
        groupBroadcastService.broadcast(topic.getChatGroupId(), WsConstants.ERROR, Map.of(
                "success", false,
                "errorCode", ErrorCode.TOPIC_CONCLUSION_FAILED.name(),
                "message", "结论生成失败，讨论已恢复：" + reason));
    }

    private void saveSystemNotice(Long groupId, Long topicId, String content) {
        GroupMessage notice = new GroupMessage();
        notice.setChatGroupId(groupId);
        notice.setTopicId(topicId);
        notice.setSenderId(0L);
        notice.setSenderType(SenderType.SYSTEM);
        notice.setMessageType(MessageType.SYSTEM_NOTICE);
        notice.setContent(content);
        messageRepository.save(notice);
        groupBroadcastService.broadcast(groupId, WsConstants.NEW_MESSAGE, messageAssembler.toDto(notice));
    }

    private void pushTopicStatus(Topic topic, String previousStatus) {
        groupBroadcastService.broadcast(topic.getChatGroupId(), WsConstants.TOPIC_STATUS_CHANGED, Map.of(
                "groupId", topic.getChatGroupId(),
                "topicId", topic.getId(),
                "title", topic.getTitle(),
                "status", topic.getStatus().name(),
                "previousStatus", previousStatus));
    }
}
