package com.dingring.app.orchestrator;

import com.dingring.app.dto.response.MessageDTO;
import com.dingring.app.service.ChatPusher;
import com.dingring.app.service.MessageAssembler;
import com.dingring.common.constant.WsConstants;
import com.dingring.common.exception.BizException;
import com.dingring.common.exception.ErrorCode;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.agent.AgentRepository;
import com.dingring.domain.event.AgentFailed;
import com.dingring.domain.event.AgentSelected;
import com.dingring.domain.event.MessageSent;
import com.dingring.domain.event.TopicClosed;
import com.dingring.domain.event.TopicConcluding;
import com.dingring.domain.group.Group;
import com.dingring.domain.group.GroupMessage;
import com.dingring.domain.group.GroupRepository;
import com.dingring.domain.group.MessageRepository;
import com.dingring.domain.group.MessageType;
import com.dingring.domain.group.SenderType;
import com.dingring.domain.discussion.Topic;
import com.dingring.domain.discussion.TopicRepository;
import com.dingring.domain.service.DomainEventPublisher;
import com.dingring.domain.service.LlmService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 编排引擎：驱动"接收→决策→生成→收束"主循环（见技术方案 4.1）。
 * <p>每个群一个单线程虚拟线程执行器，保证 Agent 串行发言；用户消息不阻塞（同步入库立即广播）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatOrchestrator {

    private final GroupRepository groupRepository;
    private final MessageRepository messageRepository;
    private final TopicRepository topicRepository;
    private final AgentRepository agentRepository;
    private final SpeakerScheduler speakerScheduler;
    private final ContextBuilder contextBuilder;
    private final Terminator terminator;
    private final MessageAssembler messageAssembler;
    private final LlmService llmService;
    private final DomainEventPublisher eventPublisher;
    private final ChatPusher chatPusher;
    private final ConcludeIntentDetector concludeIntentDetector;

    /** 每群一个串行执行器（虚拟线程） */
    private final Map<Long, ExecutorService> groupExecutors = new ConcurrentHashMap<>();

    /* ==================== 接收域 ==================== */

    /**
     * 用户发送消息：入库→广播→触发调度。@Agent 且带总结意图时，由该 Agent 触发结束流程。
     *
     * @return 入库后的消息 DTO
     */
    public MessageDTO onUserMessage(Long groupId, Long userId, String content, Long replyToMessageId) {
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "群不存在: " + groupId));
        Optional<Topic> activeTopic = topicRepository.findActiveByGroupId(groupId);
        Long topicId = activeTopic.map(Topic::getId).orElse(null);

        // 消息入库（topic_id 自动归属）
        GroupMessage message = new GroupMessage();
        message.setChatGroupId(groupId);
        message.setTopicId(topicId);
        message.setSenderId(userId);
        message.setSenderType(SenderType.USER);
        message.setMessageType(MessageType.TEXT);
        message.setContent(content);
        message.setReplyToMessageId(replyToMessageId);
        messageRepository.save(message);

        MessageDTO dto = messageAssembler.toDto(message);
        chatPusher.pushToGroup(groupId, WsConstants.NEW_MESSAGE, dto);

        List<Agent> groupAgents = agentRepository.findByIds(group.memberAgentIds());
        List<Long> mentionedIds = parseMentions(content, groupAgents);
        eventPublisher.publish(new MessageSent(message.getId(), groupId, topicId, userId,
                SenderType.USER.name(), content, replyToMessageId, mentionedIds));

        // 用户总结意图：@Agent 且 LLM 判定用户在请它总结 → 由被 @ 的 Agent 生成结论
        if (topicId != null && !mentionedIds.isEmpty()) {
            Agent mentioned = groupAgents.stream()
                    .filter(a -> a.getId().equals(mentionedIds.get(0)))
                    .findFirst().orElse(null);
            if (mentioned != null && concludeIntentDetector.isConcludeIntent(mentioned, content)) {
                conclude(topicId, userId, "USER", mentioned.getId());
                return dto;
            }
        }

        // 调度域：异步串行触发 Agent 发言
        MessageContext ctx = MessageContext.builder()
                .groupId(groupId)
                .topicId(topicId)
                .content(content)
                .mentionedAgentIds(mentionedIds)
                .repliedToAgentId(resolveRepliedAgent(replyToMessageId))
                .speakCounts(loadSpeakCounts(topicId, group))
                .build();
        executorOf(groupId).execute(() -> runScheduleLoop(group, ctx));
        return dto;
    }

    /* ==================== 收束域 ==================== */

    /**
     * 触发结束讨论：状态流转 CONCLUDING（同步）+ 结论生成（异步）。
     * <p>未指定总结 Agent 时，由调度评分最高的成员 Agent 兜底总结。
     *
     * @param triggeredBy USER / AGENT / MAX_ROUNDS
     */
    public void conclude(Long topicId, Long operatorId, String triggeredBy) {
        conclude(topicId, operatorId, triggeredBy, null);
    }

    /**
     * 触发结束讨论（指定总结 Agent）。
     *
     * @param concluderAgentId 总结 Agent ID（null = 调度评分最高者兜底）
     */
    public void conclude(Long topicId, Long operatorId, String triggeredBy, Long concluderAgentId) {
        Topic topic = topicRepository.findById(topicId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "主题不存在: " + topicId));
        topic.startConcluding();
        if (!topicRepository.update(topic)) {
            throw new BizException(ErrorCode.TOPIC_NOT_IN_PROGRESS, "主题状态已变更，请刷新后重试");
        }
        pushTopicStatus(topic, "IN_PROGRESS");
        eventPublisher.publish(new TopicConcluding(topic.getId(), topic.getChatGroupId(), topic.getTitle()));
        executorOf(topic.getChatGroupId()).execute(() -> generateConclusion(topic, triggeredBy, concluderAgentId));
    }

    /** 总结 Agent 生成 STAR 结论；失败回退 IN_PROGRESS */
    private void generateConclusion(Topic topic, String triggeredBy, Long designatedConcluderId) {
        Long groupId = topic.getChatGroupId();
        Group group = groupRepository.findById(groupId).orElse(null);
        Agent concluder = resolveConcluder(group, topic, designatedConcluderId);
        if (concluder == null) {
            rollbackConclusion(topic, "群内没有可用的总结 Agent");
            return;
        }
        chatPusher.pushToGroup(groupId, WsConstants.AGENT_TYPING,
                Map.of("groupId", groupId, "agentId", concluder.getId(), "agentName", concluder.getName(), "isTyping", true));
        try {
            ContextBuilder.LlmContext ctx = contextBuilder.buildForConclusion(
                    concluder, groupId, topic.getId(), topic.getTitle(), messageAssembler::resolveSenderName);
            String conclusion = chatWithRetry(concluder, ctx);
            if (conclusion == null || conclusion.isBlank()) {
                throw new BizException(ErrorCode.TOPIC_CONCLUSION_FAILED, "总结 Agent 返回空结论");
            }
            // 结论中不应残留收束标记
            conclusion = ContextBuilder.stripConcludeMarker(conclusion);
            topic.close(conclusion, concluder.getId());
            topicRepository.update(topic);

            long messageCount = messageRepository.countByTopicId(topic.getId());
            saveSystemNotice(groupId, topic.getId(),
                    "讨论「" + topic.getTitle() + "」已结束，结论由「" + concluder.getName() + "」生成");
            chatPusher.pushToGroup(groupId, WsConstants.TOPIC_CLOSED, Map.of(
                    "groupId", groupId,
                    "topicId", topic.getId(),
                    "title", topic.getTitle(),
                    "conclusion", conclusion,
                    "messageCount", messageCount,
                    "closedAt", topic.getClosedAt().toString()));
            eventPublisher.publish(new TopicClosed(topic.getId(), groupId, topic.getTitle(),
                    conclusion, messageCount, triggeredBy, concluder.getId()));
        } catch (Exception e) {
            log.error("结论生成失败, topicId={}", topic.getId(), e);
            rollbackConclusion(topic, e.getMessage());
        } finally {
            chatPusher.pushToGroup(groupId, WsConstants.AGENT_TYPING,
                    Map.of("groupId", groupId, "agentId", concluder.getId(), "agentName", concluder.getName(), "isTyping", false));
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
        MessageContext ctx = MessageContext.builder()
                .groupId(group.getId())
                .topicId(topic.getId())
                .content("")
                .mentionedAgentIds(List.of())
                .repliedToAgentId(null)
                .speakCounts(loadSpeakCounts(topic.getId(), group))
                .build();
        return speakerScheduler.rank(candidates, ctx).get(0).agent();
    }

    private void rollbackConclusion(Topic topic, String reason) {
        try {
            topic.rollbackToInProgress();
            topicRepository.update(topic);
            pushTopicStatus(topic, "CONCLUDING");
        } catch (Exception ex) {
            log.error("结论失败回退异常, topicId={}", topic.getId(), ex);
        }
        chatPusher.pushToGroup(topic.getChatGroupId(), WsConstants.ERROR, Map.of(
                "success", false,
                "errorCode", ErrorCode.TOPIC_CONCLUSION_FAILED.name(),
                "message", "结论生成失败，讨论已恢复：" + reason));
    }

    /* ==================== 调度域 + 生成域 ==================== */

    /** 调度循环：连续触发至多 autoReplies 条 Agent 发言；@提及只回一条 */
    private void runScheduleLoop(Group group, MessageContext ctx) {
        boolean mentionRound = ctx.getMentionedAgentIds() != null && !ctx.getMentionedAgentIds().isEmpty();
        int maxReplies = mentionRound ? 1 : terminator.getAutoReplies();
        for (int i = 0; i < maxReplies; i++) {
            // 主题被关闭/收束则停止调度
            if (ctx.getTopicId() != null) {
                Optional<Topic> topic = topicRepository.findById(ctx.getTopicId());
                if (topic.isEmpty() || !topic.get().isInProgress()) {
                    return;
                }
                // 终止判定：达到最大轮次自动收束
                if (terminator.reachedMaxRounds(ctx.getTopicId())) {
                    autoConclude(ctx.getTopicId());
                    return;
                }
            }
            GroupMessage reply = speakOnce(group, ctx);
            if (reply == null) {
                return;
            }
            // 发言后重新评估：轮次+1，后续轮次为自由调度
            Map<Long, Long> counts = new HashMap<>(ctx.getSpeakCounts());
            counts.merge(reply.getSenderId(), 1L, Long::sum);
            ctx = MessageContext.builder()
                    .groupId(ctx.getGroupId())
                    .topicId(ctx.getTopicId())
                    .content(reply.getContent())
                    .mentionedAgentIds(List.of())
                    .repliedToAgentId(null)
                    .speakCounts(counts)
                    .build();
        }
        // 循环结束后再次终止判定
        if (ctx.getTopicId() != null && terminator.reachedMaxRounds(ctx.getTopicId())) {
            autoConclude(ctx.getTopicId());
        }
    }

    private void autoConclude(Long topicId) {
        try {
            log.info("达到最大轮次，自动触发收束, topicId={}", topicId);
            conclude(topicId, null, "MAX_ROUNDS");
        } catch (BizException e) {
            log.warn("自动收束跳过: {}", e.getMessage());
        }
    }

    /** Agent 主动触发收束（回复带收束标记），由它本人生成结论 */
    private void triggerAgentConclude(Long topicId, Agent agent) {
        try {
            log.info("Agent 主动触发收束, topicId={}, agent={}", topicId, agent.getName());
            conclude(topicId, null, "AGENT", agent.getId());
        } catch (BizException e) {
            log.warn("Agent 收束跳过: {}", e.getMessage());
        }
    }

    /**
     * 一次发言：按评分降序为降级链依次尝试（重试 1 次后接力下一个 Agent）。
     *
     * @return 成功入库的 Agent 消息；空内容(选择不发言)或全部失败返回 null
     */
    private GroupMessage speakOnce(Group group, MessageContext ctx) {
        List<Agent> candidates = agentRepository.findByIds(group.memberAgentIds());
        if (candidates.isEmpty()) {
            return null;
        }
        List<SpeakerScheduler.ScoredAgent> ranked = speakerScheduler.rank(candidates, ctx);
        for (int i = 0; i < ranked.size(); i++) {
            SpeakerScheduler.ScoredAgent scored = ranked.get(i);
            Agent agent = scored.agent();
            eventPublisher.publish(new AgentSelected(ctx.getGroupId(), ctx.getTopicId(),
                    agent.getId(), agent.getName(), scored.reason(), scored.score()));
            pushTyping(ctx.getGroupId(), agent, true);
            try {
                ContextBuilder.LlmContext llmCtx = contextBuilder.build(
                        agent, ctx.getGroupId(), ctx.getTopicId(), messageAssembler::resolveSenderName);
                String content = chatWithRetry(agent, llmCtx);
                if (content == null || content.isBlank()) {
                    // 返回内容为空不算失败：Agent 选择不发言
                    log.info("Agent 选择不发言, agent={}", agent.getName());
                    return null;
                }
                // Agent 自主收束：回复带 [[CONCLUDE]] 标记 → 剥离后由它触发结束流程
                boolean wantsConclude = ctx.getTopicId() != null
                        && content.contains(ContextBuilder.CONCLUDE_MARKER);
                if (wantsConclude) {
                    content = ContextBuilder.stripConcludeMarker(content);
                }
                GroupMessage reply = null;
                if (!content.isBlank()) {
                    reply = saveAgentMessage(ctx, agent, content);
                    chatPusher.pushToGroup(ctx.getGroupId(), WsConstants.NEW_MESSAGE, messageAssembler.toDto(reply));
                    eventPublisher.publish(new MessageSent(reply.getId(), ctx.getGroupId(), ctx.getTopicId(),
                            agent.getId(), SenderType.AGENT.name(), content, null, List.of()));
                }
                if (wantsConclude) {
                    triggerAgentConclude(ctx.getTopicId(), agent);
                    // 已进入收束流程，停止后续调度
                    return null;
                }
                return reply;
            } catch (Exception e) {
                // 降级路由：接力给下一个 Agent（失败 Agent 下一轮仍参与调度）
                Agent fallback = i + 1 < ranked.size() ? ranked.get(i + 1).agent() : null;
                log.warn("Agent 调用失败降级, agent={}, fallback={}", agent.getName(),
                        fallback == null ? "无" : fallback.getName(), e);
                eventPublisher.publish(new AgentFailed(ctx.getGroupId(), ctx.getTopicId(),
                        agent.getId(), agent.getName(), e.getMessage(),
                        fallback == null ? null : fallback.getId(),
                        fallback == null ? null : fallback.getName()));
            } finally {
                pushTyping(ctx.getGroupId(), agent, false);
            }
        }
        // 所有 Agent 都失败
        chatPusher.pushToGroup(ctx.getGroupId(), WsConstants.ERROR, Map.of(
                "success", false,
                "errorCode", ErrorCode.ALL_AGENTS_FAILED.name(),
                "message", ErrorCode.ALL_AGENTS_FAILED.getDefaultMessage()));
        return null;
    }

    /** LLM 调用（失败重试 1 次） */
    private String chatWithRetry(Agent agent, ContextBuilder.LlmContext ctx) {
        try {
            return llmService.chat(agent, ctx.systemPrompt(), ctx.turns());
        } catch (Exception first) {
            log.info("LLM 首次调用失败，重试 1 次, agent={}", agent.getName());
            return llmService.chat(agent, ctx.systemPrompt(), ctx.turns());
        }
    }

    /* ==================== 私有辅助 ==================== */

    private GroupMessage saveAgentMessage(MessageContext ctx, Agent agent, String content) {
        GroupMessage reply = new GroupMessage();
        reply.setChatGroupId(ctx.getGroupId());
        reply.setTopicId(ctx.getTopicId());
        reply.setSenderId(agent.getId());
        reply.setSenderType(SenderType.AGENT);
        reply.setMessageType(MessageType.TEXT);
        reply.setContent(content);
        messageRepository.save(reply);
        return reply;
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
        chatPusher.pushToGroup(groupId, WsConstants.NEW_MESSAGE, messageAssembler.toDto(notice));
    }

    private void pushTyping(Long groupId, Agent agent, boolean typing) {
        chatPusher.pushToGroup(groupId, WsConstants.AGENT_TYPING, Map.of(
                "groupId", groupId,
                "agentId", agent.getId(),
                "agentName", agent.getName(),
                "isTyping", typing));
    }

    private void pushTopicStatus(Topic topic, String previousStatus) {
        chatPusher.pushToGroup(topic.getChatGroupId(), WsConstants.TOPIC_STATUS_CHANGED, Map.of(
                "groupId", topic.getChatGroupId(),
                "topicId", topic.getId(),
                "title", topic.getTitle(),
                "status", topic.getStatus().name(),
                "previousStatus", previousStatus));
    }

    /** 解析 @花名 提及 */
    private List<Long> parseMentions(String content, List<Agent> groupAgents) {
        if (content == null || content.indexOf('@') < 0) {
            return List.of();
        }
        List<Long> mentioned = new ArrayList<>();
        for (Agent agent : groupAgents) {
            if (content.contains("@" + agent.getName())) {
                mentioned.add(agent.getId());
            }
        }
        return mentioned;
    }

    /** 引用回复的目标 Agent（引用的不是 Agent 消息则 null） */
    private Long resolveRepliedAgent(Long replyToMessageId) {
        if (replyToMessageId == null) {
            return null;
        }
        return messageRepository.findById(replyToMessageId)
                .filter(m -> m.getSenderType() == SenderType.AGENT)
                .map(GroupMessage::getSenderId)
                .orElse(null);
    }

    /** Topic 内各成员 Agent 已发言次数 */
    private Map<Long, Long> loadSpeakCounts(Long topicId, Group group) {
        Map<Long, Long> counts = new HashMap<>();
        if (topicId == null) {
            return counts;
        }
        for (Long agentId : group.memberAgentIds()) {
            counts.put(agentId, messageRepository.countByTopicIdAndSender(topicId, agentId, SenderType.AGENT));
        }
        return counts;
    }

    private ExecutorService executorOf(Long groupId) {
        return groupExecutors.computeIfAbsent(groupId,
                id -> Executors.newSingleThreadExecutor(Thread.ofVirtual().name("group-" + id + "-", 0).factory()));
    }
}
