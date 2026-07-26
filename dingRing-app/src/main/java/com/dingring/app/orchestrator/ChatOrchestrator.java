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

    /** 每群一个串行执行器（虚拟线程） */
    private final Map<Long, ExecutorService> groupExecutors = new ConcurrentHashMap<>();

    /* ==================== 接收域 ==================== */

    /**
     * 用户发送消息：入库→广播→触发调度。@专家 时直接触发结束流程。
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

        List<Agent> groupAgents = agentRepository.findByIds(allAgentIds(group));
        List<Long> mentionedIds = parseMentions(content, groupAgents);
        eventPublisher.publish(new MessageSent(message.getId(), groupId, topicId, userId,
                SenderType.USER.name(), content, replyToMessageId, mentionedIds));

        // @专家 → 直接触发结束流程而非让专家参与讨论
        Optional<Long> expertId = group.expertAgentId();
        if (topicId != null && expertId.isPresent() && mentionedIds.contains(expertId.get())) {
            conclude(topicId, userId, "USER");
            return dto;
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
     * 触发结束讨论：状态流转 CONCLUDING（同步）+ 专家结论生成（异步）。
     *
     * @param triggeredBy USER / MAX_ROUNDS
     */
    public void conclude(Long topicId, Long operatorId, String triggeredBy) {
        Topic topic = topicRepository.findById(topicId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "主题不存在: " + topicId));
        topic.startConcluding(operatorId);
        if (!topicRepository.update(topic)) {
            throw new BizException(ErrorCode.TOPIC_NOT_IN_PROGRESS, "主题状态已变更，请刷新后重试");
        }
        pushTopicStatus(topic, "IN_PROGRESS");
        eventPublisher.publish(new TopicConcluding(topic.getId(), topic.getChatGroupId(), topic.getTitle()));
        executorOf(topic.getChatGroupId()).execute(() -> generateConclusion(topic, triggeredBy));
    }

    /** 专家生成 STAR 结论；失败回退 IN_PROGRESS */
    private void generateConclusion(Topic topic, String triggeredBy) {
        Long groupId = topic.getChatGroupId();
        Group group = groupRepository.findById(groupId).orElse(null);
        Agent expert = Optional.ofNullable(group)
                .flatMap(Group::expertAgentId)
                .flatMap(agentRepository::findById)
                .orElse(null);
        if (expert == null) {
            rollbackConclusion(topic, "群未配置专家 Agent");
            return;
        }
        chatPusher.pushToGroup(groupId, WsConstants.AGENT_TYPING,
                Map.of("groupId", groupId, "agentId", expert.getId(), "agentName", expert.getName(), "isTyping", true));
        try {
            ContextBuilder.LlmContext ctx = contextBuilder.buildForConclusion(
                    expert, groupId, topic.getId(), topic.getTitle(), messageAssembler::resolveSenderName);
            String conclusion = chatWithRetry(expert, ctx);
            if (conclusion == null || conclusion.isBlank()) {
                throw new BizException(ErrorCode.TOPIC_CONCLUSION_FAILED, "专家返回空结论");
            }
            topic.close(conclusion);
            topicRepository.update(topic);

            long messageCount = messageRepository.countByTopicId(topic.getId());
            saveSystemNotice(groupId, topic.getId(), "讨论「" + topic.getTitle() + "」已结束，专家结论已生成");
            chatPusher.pushToGroup(groupId, WsConstants.TOPIC_CLOSED, Map.of(
                    "groupId", groupId,
                    "topicId", topic.getId(),
                    "title", topic.getTitle(),
                    "conclusion", conclusion,
                    "messageCount", messageCount,
                    "closedAt", topic.getClosedAt().toString()));
            eventPublisher.publish(new TopicClosed(topic.getId(), groupId, topic.getTitle(),
                    conclusion, messageCount, triggeredBy, expert.getId()));
        } catch (Exception e) {
            log.error("结论生成失败, topicId={}", topic.getId(), e);
            rollbackConclusion(topic, e.getMessage());
        } finally {
            chatPusher.pushToGroup(groupId, WsConstants.AGENT_TYPING,
                    Map.of("groupId", groupId, "agentId", expert.getId(), "agentName", expert.getName(), "isTyping", false));
        }
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
                GroupMessage reply = saveAgentMessage(ctx, agent, content);
                chatPusher.pushToGroup(ctx.getGroupId(), WsConstants.NEW_MESSAGE, messageAssembler.toDto(reply));
                eventPublisher.publish(new MessageSent(reply.getId(), ctx.getGroupId(), ctx.getTopicId(),
                        agent.getId(), SenderType.AGENT.name(), content, null, List.of()));
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

    /** 解析 @花名 提及（含专家） */
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

    /** Topic 内各普通 Agent 已发言次数 */
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

    private List<Long> allAgentIds(Group group) {
        List<Long> ids = new ArrayList<>(group.memberAgentIds());
        group.expertAgentId().ifPresent(ids::add);
        return ids;
    }

    private ExecutorService executorOf(Long groupId) {
        return groupExecutors.computeIfAbsent(groupId,
                id -> Executors.newSingleThreadExecutor(Thread.ofVirtual().name("group-" + id + "-", 0).factory()));
    }
}
