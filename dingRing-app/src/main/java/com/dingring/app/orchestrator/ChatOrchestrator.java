package com.dingring.app.orchestrator;

import com.dingring.app.dto.response.MessageDTO;
import com.dingring.app.service.ChatPusher;
import com.dingring.app.service.MessageAssembler;
import com.dingring.common.constant.WsConstants;
import com.dingring.common.exception.BizException;
import com.dingring.common.exception.ErrorCode;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.agent.AgentRepository;
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

/**
 * 编排器（收束域）：用户消息入库广播后投递给 {@link DiscussionEngine} 异步驱动；
 * 本类保留收束域（状态流转 + 结论生成），结论任务排在引擎的群串行执行器上保证串行。
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
    private final MessageAssembler messageAssembler;
    private final LlmService llmService;
    private final DomainEventPublisher eventPublisher;
    private final ChatPusher chatPusher;
    private final DiscussionEngine discussionEngine;

    /* ==================== 接收域 ==================== */

    /**
     * 用户发送消息：入库→广播→投递信号给对话引擎（意图路由与应答由引擎异步驱动）。
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
        log.info("收到用户消息, groupId={}, topicId={}, messageId={}, 群内Agent数={}, @提及={}, 内容={}",
                groupId, topicId, message.getId(), groupAgents.size(),
                mentionedIds.isEmpty() ? "无" : mentionedIds, content);
        if (topicId == null) {
            log.info("当前群无活跃主题，消息不归属 Topic，仍正常触发调度, groupId={}", groupId);
        }
        // 消息发送事件（先预留在这）
//        eventPublisher.publish(new MessageSent(message.getId(), groupId, topicId, userId,
//                SenderType.USER.name(), content, replyToMessageId, mentionedIds));

        // 投递信号：意图路由与应答由对话引擎异步驱动
        discussionEngine.onUserSignal(groupId, new DiscussionEngine.UserSignal(
                userId, content, mentionedIds, resolveRepliedAgent(replyToMessageId)));
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
        log.info("触发讨论收束, topicId={}, triggeredBy={}, 指定总结AgentId={}", topicId, triggeredBy, concluderAgentId);
        Topic topic = topicRepository.findById(topicId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "主题不存在: " + topicId));
        topic.startConcluding();
        if (!topicRepository.update(topic)) {
            throw new BizException(ErrorCode.TOPIC_NOT_IN_PROGRESS, "主题状态已变更，请刷新后重试");
        }
        pushTopicStatus(topic, "IN_PROGRESS");
        eventPublisher.publish(new TopicConcluding(topic.getId(), topic.getChatGroupId(), topic.getTitle()));
        discussionEngine.execute(topic.getChatGroupId(), "结论生成",
                () -> generateConclusion(topic, triggeredBy, concluderAgentId));
    }

    /** 总结 Agent 生成 STAR 结论；失败回退 IN_PROGRESS */
    private void generateConclusion(Topic topic, String triggeredBy, Long designatedConcluderId) {
        Long groupId = topic.getChatGroupId();
        Group group = groupRepository.findById(groupId).orElse(null);
        Agent concluder = resolveConcluder(group, topic, designatedConcluderId);
        if (concluder == null) {
            log.warn("无可用总结 Agent，回滚讨论状态, topicId={}, 指定AgentId={}", topic.getId(), designatedConcluderId);
            rollbackConclusion(topic, "群内没有可用的总结 Agent");
            return;
        }
        log.info("开始生成结论, topicId={}, 总结Agent={}, triggeredBy={}", topic.getId(), concluder.getName(), triggeredBy);
        chatPusher.pushToGroup(groupId, WsConstants.AGENT_TYPING,
                Map.of("groupId", groupId, "agentId", concluder.getId(), "agentName", concluder.getName(), "isTyping", true));
        try {
            ContextBuilder.LlmContext ctx = contextBuilder.buildForConclusion(
                    concluder, groupId, topic.getId(), topic.getTitle(), messageAssembler::resolveSenderName);
            String conclusion = chatWithRetry(concluder, ctx);
            if (conclusion == null || conclusion.isBlank()) {
                throw new BizException(ErrorCode.TOPIC_CONCLUSION_FAILED, "总结 Agent 返回空结论");
            }
            // 结论中不应残留协作标记
            conclusion = ContextBuilder.stripMarkers(conclusion);
            topic.close(conclusion, concluder.getId());
            topicRepository.update(topic);
            log.info("结论生成成功，主题已关闭, topicId={}, 总结Agent={}, 结论长度={}",
                    topic.getId(), concluder.getName(), conclusion.length());

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

    /* ==================== 私有辅助 ==================== */

    /** LLM 调用（失败重试 1 次） */
    private String chatWithRetry(Agent agent, ContextBuilder.LlmContext ctx) {
        try {
            return llmService.chat(agent, ctx.systemPrompt(), ctx.turns());
        } catch (Exception first) {
            log.warn("LLM 首次调用失败，重试 1 次, agent={}, 失败原因: {}", agent.getName(), first.getMessage());
            return llmService.chat(agent, ctx.systemPrompt(), ctx.turns());
        }
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
}
