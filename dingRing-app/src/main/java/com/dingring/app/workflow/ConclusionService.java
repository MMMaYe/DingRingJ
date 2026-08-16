package com.dingring.app.workflow;

import com.dingring.app.orchestrator.ContextBuilder;
import com.dingring.app.orchestrator.MessageContext;
import com.dingring.app.orchestrator.SpeakerScheduler;
import com.dingring.app.service.MessageAssembler;
import com.dingring.common.constant.WsConstants;
import com.dingring.common.exception.BizException;
import com.dingring.common.exception.ErrorCode;
import com.dingring.common.util.LogHelper;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.agent.AgentRepository;
import com.dingring.domain.discussion.Topic;
import com.dingring.domain.discussion.TopicRepository;
import com.dingring.domain.discussion.TopicStatus;
import com.dingring.domain.event.TopicClosed;
import com.dingring.domain.group.Group;
import com.dingring.domain.group.GroupMessage;
import com.dingring.domain.group.GroupRepository;
import com.dingring.domain.group.MessageRepository;
import com.dingring.domain.group.MessageType;
import com.dingring.domain.group.SenderType;
import com.dingring.domain.service.LlmService;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 结论生成服务：从图内 ConcludeNode 迁出的异步结论生成逻辑。
 * <p>设计要点（解决「结论 LLM 内联在群执行器上阻塞整群」问题）：
 * <ul>
 *   <li>triggerAsync 是唯一触发入口：同步完成 IN_PROGRESS→CONCLUDING 状态流转（保证前端即时看到状态、乐观锁防并发流转），
 *       然后把真正的 LLM 生成 {@link #generate} 提交到 {@link ConclusionExecutor}，不阻塞调用线程</li>
 *   <li>per-topic inflight 守卫：同一 Topic 同时最多一个生成任务，双击/双通道触发不重复跑 LLM</li>
 *   <li>generate 幂等：Topic 已 CLOSED 直接放弃；CONCLUDING→CLOSED 用乐观锁，更新失败（已被并发关闭/看门狗回滚）不广播，杜绝重复 TOPIC_CLOSED/重复卡片</li>
 *   <li>失败回滚：LLM 失败/无总结 Agent 时 CONCLUDING→IN_PROGRESS + 广播 ERROR，讨论可继续</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConclusionService {

    private final TopicRepository topicRepository;
    private final GroupRepository groupRepository;
    private final AgentRepository agentRepository;
    private final MessageRepository messageRepository;
    private final SpeakerScheduler speakerScheduler;
    private final ContextBuilder contextBuilder;
    private final MessageAssembler messageAssembler;
    private final LlmService llmService;
    private final DomainEventPublisher eventPublisher;
    private final GroupBroadcastService groupBroadcastService;
    private final ConclusionExecutor conclusionExecutor;

    /** 每个 Topic 的生成中标记：防止同一 Topic 并发触发产生重复结论（单实例内存守卫，Topic 生命周期内常驻） */
    private final Map<Long, AtomicBoolean> inflight = new ConcurrentHashMap<>();

    /**
     * 触发收束（所有路径的唯一入口：图内 ConcludeNode / REST / WS / CONCLUDE_PROPOSED 超时）。
     * <p>同步完成状态流转 + 广播，随后异步提交结论生成，立即返回。
     *
     * @param topicId          话题 ID
     * @param groupId          群 ID（日志用；业务内从 Topic 派生）
     * @param triggeredBy      USER / AGENT / MAX_ROUNDS / CONVERGED / FAILED / TIMEOUT
     * @param concluderAgentId 指定总结 Agent ID（null = 调度评分最高者兜底）
     */
    @Event(eventCode = "TRIGGER_CONCLUDE", eventName = "触发收束（同步流转+异步生成）")
    public void triggerAsync(Long topicId, Long groupId, String triggeredBy, Long concluderAgentId) {
        Topic topic = topicRepository.findById(topicId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "主题不存在: " + topicId));
        LogHelper.printLog(ConclusionService.class, "ConclusionService.triggerAsync",
                "TRIGGER_CONCLUDE", "触发收束", "topicId={} groupId={} triggeredBy={} concluderAgentId={}",
                topicId, groupId, triggeredBy, concluderAgentId);

        if (topic.getStatus() == TopicStatus.CLOSED) {
            LogHelper.printLog(ConclusionService.class, "ConclusionService.triggerAsync",
                    "TRIGGER_CONCLUDE", "话题已关闭忽略重复收束", "topicId={}", topicId);
            return;
        }
        // 同步状态流转（ConcludeNode 会检测已是 CONCLUDING 跳过此步）
        if (topic.isInProgress()) {
            topic.startConcluding();
            if (!topicRepository.update(topic)) {
                throw new BizException(ErrorCode.TOPIC_NOT_IN_PROGRESS, "主题状态已变更，请刷新后重试");
            }
            pushTopicStatus(topic, "IN_PROGRESS");
        }
        conclusionExecutor.execute(() -> guardedGenerate(topicId, groupId, triggeredBy, concluderAgentId));
    }

    /** 提交前用 per-topic 标记去重：同一 Topic 同时只跑一个生成任务 */
    private void guardedGenerate(Long topicId, Long groupId, String triggeredBy, Long concluderAgentId) {
        AtomicBoolean gate = inflight.computeIfAbsent(topicId, k -> new AtomicBoolean(false));
        if (!gate.compareAndSet(false, true)) {
            LogHelper.printLog(ConclusionService.class, "ConclusionService.guardedGenerate",
                    "GENERATE_CONCLUSION", "结论生成已在途，跳过重复触发", "topicId={}", topicId);
            return;
        }
        try {
            generate(topicId, groupId, triggeredBy, concluderAgentId);
        } finally {
            gate.set(false);
        }
    }

    /**
     * 异步生成结论并关闭 Topic（运行在 {@link ConclusionExecutor} 上）。
     * <p>幂等：CLOSED 直接放弃；关闭用乐观锁，失败说明已被并发处理，不广播。
     */
    @Event(eventCode = "GENERATE_CONCLUSION", eventName = "异步生成结论")
    public void generate(Long topicId, Long groupId, String triggeredBy, Long concluderAgentId) {
        Topic topic = topicRepository.findById(topicId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "主题不存在: " + topicId));
        LogHelper.printLog(ConclusionService.class, "ConclusionService.generate",
                "GENERATE_CONCLUSION", "异步生成结论开始", "topicId={} triggeredBy={} concluderAgentId={}",
                topicId, triggeredBy, concluderAgentId);

        if (topic.getStatus() == TopicStatus.CLOSED) {
            LogHelper.printLog(ConclusionService.class, "ConclusionService.generate",
                    "GENERATE_CONCLUSION", "话题已关闭，放弃本次生成", "topicId={}", topicId);
            return;
        }
        // 防御：触发时流转未生效（如看门狗已回滚为 IN_PROGRESS），在此补流转
        if (topic.isInProgress()) {
            topic.startConcluding();
            if (!topicRepository.update(topic)) {
                LogHelper.printWarnLog(ConclusionService.class, "ConclusionService.generate",
                        "GENERATE_CONCLUSION", "状态流转失败，放弃生成", "topicId={}", topicId);
                return;
            }
        }

        Group group = groupId != null ? groupRepository.findById(groupId).orElse(null) : null;
        Agent concluder = resolveConcluder(group, topic, concluderAgentId);
        LogHelper.printLog(ConclusionService.class, "ConclusionService.generate",
                "GENERATE_CONCLUSION", "总结Agent解析结果", "topicId={} concluder={} 指定AgentId={}",
                topicId, concluder == null ? "无" : concluder.getName(), concluderAgentId);
        if (concluder == null) {
            LogHelper.printWarnLog(ConclusionService.class, "ConclusionService.generate",
                    "GENERATE_CONCLUSION", "无可用总结Agent回滚", "topicId={} 指定AgentId={}", topicId, concluderAgentId);
            rollbackConclusion(topic, "群内没有可用的总结 Agent");
            return;
        }

        groupBroadcastService.broadcast(topic.getChatGroupId(), WsConstants.AGENT_TYPING, Map.of(
                "groupId", topic.getChatGroupId(),
                "agentId", concluder.getId(),
                "agentName", concluder.getName(),
                "isTyping", true));
        try {
            ContextBuilder.LlmContext ctx = contextBuilder.buildForConclusion(
                    concluder, topic.getChatGroupId(), topic.getId(), topic.getTitle(),
                    messageAssembler::resolveSenderName);
            Map<String, Object> context = new HashMap<>();
            context.put("groupId", topic.getChatGroupId());
            context.put("topicId", topic.getId());
            context.put("userId", 1L);  // 当前单用户系统默认 ID
            context.put("speakerAgentId", concluder.getId());
            // 场景意图（InjectKbHook：CONCLUDE 仅 topic 源，相似历史结论辅助总结）
            context.put(StateKeys.INTENT, "CONCLUDE");
            // RAG 检索词：以主题标题为查询（CONCLUDE 意图下 InjectKbHook 不消费 kb 源，保留供检索语义）
            context.put("ragQuery", topic.getTitle());
            LlmService.AgentResult agentResult;
            try {
                agentResult = llmService.chat(concluder, ctx.systemPrompt(), ctx.turns(),
                        LlmService.ToolSet.CONCLUDE, context);
            } catch (Exception first) {
                LogHelper.printWarnLog(ConclusionService.class, "ConclusionService.generate",
                        "GENERATE_CONCLUSION", "LLM首次失败重试", "agent={} 失败原因: {}",
                        concluder.getName(), first.getMessage());
                agentResult = llmService.chat(concluder, ctx.systemPrompt(), ctx.turns(),
                        LlmService.ToolSet.CONCLUDE, context);
            }
            String conclusion = agentResult.content();
            if (conclusion == null || conclusion.isBlank()) {
                LogHelper.printWarnLog(ConclusionService.class, "ConclusionService.generate",
                        "GENERATE_CONCLUSION", "总结 Agent 返回空结论", "topicId={} agent={}",
                        topicId, concluder.getName());
                throw new BizException(ErrorCode.TOPIC_CONCLUSION_FAILED, "总结 Agent 返回空结论");
            }
            // 结论中不应残留协作标记
            conclusion = ContextBuilder.stripMarkers(conclusion);

            // CONCLUDING → CLOSED（乐观锁：更新失败说明已被并发关闭/看门狗回滚，放弃本次落库与广播）
            Topic fresh = topicRepository.findById(topicId).orElse(null);
            if (fresh == null || fresh.getStatus() == TopicStatus.CLOSED) {
                LogHelper.printLog(ConclusionService.class, "ConclusionService.generate",
                        "GENERATE_CONCLUSION", "话题已被并发关闭，放弃本次结论", "topicId={}", topicId);
                return;
            }
            if (fresh.isInProgress()) {
                LogHelper.printWarnLog(ConclusionService.class, "ConclusionService.generate",
                        "GENERATE_CONCLUSION", "话题已被回滚为IN_PROGRESS，放弃关闭", "topicId={}", topicId);
                return;
            }
            fresh.close(conclusion, concluder.getId());
            if (!topicRepository.update(fresh)) {
                LogHelper.printWarnLog(ConclusionService.class, "ConclusionService.generate",
                        "GENERATE_CONCLUSION", "乐观锁冲突关闭失败，放弃广播", "topicId={}", topicId);
                return;
            }
            LogHelper.printLog(ConclusionService.class, "ConclusionService.generate",
                    "GENERATE_CONCLUSION", "结论生成成功主题已关闭", "topicId={} 总结Agent={} 结论长度={}",
                    topicId, concluder.getName(), conclusion.length());

            long messageCount = messageRepository.countByTopicId(topic.getId());
            saveSystemNotice(topic.getChatGroupId(), topic.getId(),
                    "讨论「" + topic.getTitle() + "」已结束，结论由「" + concluder.getName() + "」生成");
            groupBroadcastService.broadcast(topic.getChatGroupId(), WsConstants.TOPIC_CLOSED, Map.of(
                    "groupId", topic.getChatGroupId(),
                    "topicId", topic.getId(),
                    "title", topic.getTitle(),
                    "conclusion", conclusion,
                    "messageCount", messageCount,
                    "closedAt", fresh.getClosedAt().toString()));

            //讨论收束完毕，发送卡片生成事件
            eventPublisher.publish(new TopicClosed(topic.getId(), topic.getChatGroupId(), topic.getTitle(),
                    conclusion, messageCount, triggeredBy, concluder.getId()));
        } catch (Exception e) {
            LogHelper.printWarnLog(ConclusionService.class, "ConclusionService.generate",
                    "GENERATE_CONCLUSION", "结论生成失败", "topicId={}", topicId, e);
            rollbackConclusion(topic, e.getMessage());
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
            LogHelper.printLog(ConclusionService.class, "ConclusionService.rollbackConclusion",
                    "GENERATE_CONCLUSION", "讨论已回滚至IN_PROGRESS", "topicId={} 原因={}", topic.getId(), reason);
            pushTopicStatus(topic, "CONCLUDING");
        } catch (Exception ex) {
            LogHelper.printWarnLog(ConclusionService.class, "ConclusionService.rollbackConclusion",
                    "GENERATE_CONCLUSION", "回退异常", "topicId={}", topic.getId(), ex);
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
