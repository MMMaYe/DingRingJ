package com.dingring.app.orchestrator;

import com.dingring.app.dto.response.MessageDTO;
import com.dingring.app.service.MessageAssembler;
import com.dingring.common.constant.WsConstants;
import com.dingring.common.exception.BizException;
import com.dingring.common.exception.ErrorCode;
import com.dingring.common.util.LogHelper;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.agent.AgentRepository;
import com.dingring.domain.discussion.Topic;
import com.dingring.domain.discussion.TopicRepository;
import com.dingring.domain.event.MessageSent;
import com.dingring.domain.group.Group;
import com.dingring.domain.group.GroupMessage;
import com.dingring.domain.group.GroupRepository;
import com.dingring.domain.group.MessageRepository;
import com.dingring.domain.group.MessageType;
import com.dingring.domain.group.SenderType;
import com.dingring.domain.service.DomainEventPublisher;
import com.dingring.domain.service.GroupBroadcastService;
import com.dingring.infrastructure.aop.Event;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 编排器（Phase C 重构）：接收域 + 收束域。
 * <p>接收域：用户消息入库广播后投递给 {@link DiscussionEngine} 异步驱动。
 * <p>收束域：同步状态流转（IN_PROGRESS→CONCLUDING）+ 异步触发收束流程（ConcludeNode 生成结论）。
 * <p>结论生成逻辑已迁移到 {@link com.dingring.app.workflow.node.ConcludeNode}，
 * 本类只负责同步状态流转和排队异步收束任务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatOrchestrator {

    private final GroupRepository groupRepository;
    private final MessageRepository messageRepository;
    private final TopicRepository topicRepository;
    private final AgentRepository agentRepository;
    private final MessageAssembler messageAssembler;
    private final GroupBroadcastService groupBroadcastService;
    private final DomainEventPublisher eventPublisher;
    private final DiscussionEngine discussionEngine;

    /* ==================== 接收域 ==================== */

    /**
     * 用户发送消息：入库→广播→投递信号给对话引擎（意图路由与应答由引擎异步驱动）。
     *
     * @return 入库后的消息 DTO
     */
    @Event(eventCode = "ON_USER_MESSAGE", eventName = "开始执行onUserMessage")
    public MessageDTO onUserMessage(Long groupId, Long userId, String content, Long replyToMessageId) {
        LogHelper.putTrace(groupId, null);

        try {
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
            groupBroadcastService.broadcast(groupId, WsConstants.NEW_MESSAGE, dto);

            List<Agent> groupAgents = agentRepository.findByIds(group.memberAgentIds());
            List<Long> mentionedIds = parseMentions(content, groupAgents);
            LogHelper.printLog(ChatOrchestrator.class, "ChatOrchestrator.onUserMessage", "ON_USER_MESSAGE", "收到消息",
                    "groupId={} topicId={} messageId={} 群内Agent数={} @提及={} 内容={}",
                    groupId, topicId, message.getId(), groupAgents.size(),
                    mentionedIds.isEmpty() ? "无" : mentionedIds, content);
            if (topicId == null) {
                LogHelper.printLog(ChatOrchestrator.class, "ChatOrchestrator.onUserMessage", "ON_USER_MESSAGE", "无活跃主题", "groupId={}", groupId);
            }

            // 补发 MessageSent：驱动消息打标签/摘要（MessageSummaryHandler 订阅）
            eventPublisher.publish(new MessageSent(message.getId(), groupId, topicId, userId,
                    SenderType.USER.name(), content, replyToMessageId, mentionedIds));

            // 投递信号：意图路由与应答由对话引擎异步驱动
            discussionEngine.onUserSignal(groupId, new DiscussionEngine.UserSignal(
                    userId, content, mentionedIds, resolveRepliedAgent(replyToMessageId)));
            return dto;
        } finally {
            LogHelper.clearTrace();
        }
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
     * <p>同步完成 IN_PROGRESS→CONCLUDING 状态流转（保证 REST API 即时响应），
     * 异步排队收束流程（ConcludeNode 检测已是 CONCLUDING 跳过状态流转，直接生成结论）。
     *
     * @param concluderAgentId 总结 Agent ID（null = 调度评分最高者兜底）
     */
    @Event(eventCode = "CONCLUDE", eventName = "触发讨论收束")
    public void conclude(Long topicId, Long operatorId, String triggeredBy, Long concluderAgentId) {
        Topic topic = topicRepository.findById(topicId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "主题不存在: " + topicId));

        // 同步状态流转（ConcludeNode 会检测已是 CONCLUDING 跳过此步）
        if (topic.isInProgress()) {
            topic.startConcluding();
            if (!topicRepository.update(topic)) {
                throw new BizException(ErrorCode.TOPIC_NOT_IN_PROGRESS, "主题状态已变更，请刷新后重试");
            }
            pushTopicStatus(topic, "IN_PROGRESS");
        }

        // 异步触发收束流程（排在群串行执行器上，保证与主循环串行）
        Long groupId = topic.getChatGroupId();
        discussionEngine.execute(groupId, "结论生成",
                () -> discussionEngine.runConcludeFlow(topicId, groupId, triggeredBy, concluderAgentId));
    }

    /* ==================== 私有辅助 ==================== */

    private void pushTopicStatus(Topic topic, String previousStatus) {
        groupBroadcastService.broadcast(topic.getChatGroupId(), WsConstants.TOPIC_STATUS_CHANGED, Map.of(
                "groupId", topic.getChatGroupId(),
                "topicId", topic.getId(),
                "title", topic.getTitle(),
                "status", topic.getStatus().name(),
                "previousStatus", previousStatus));
    }

    /** 解析 @花名 提及（按文本中出现顺序返回，保证首个被 @ 的 Agent 优先应答） */
    private List<Long> parseMentions(String content, List<Agent> groupAgents) {
        if (content == null || content.indexOf('@') < 0) {
            return List.of();
        }
        // 按 @ 在文本中的出现顺序排序，而非群成员列表顺序：
        // 否则 "@阿源...@老王..." 场景会选中群里排位靠前的老王，回错人
        return groupAgents.stream()
                .filter(agent -> content.contains("@" + agent.getName()))
                .sorted(Comparator.comparingInt(agent -> content.indexOf("@" + agent.getName())))
                .map(Agent::getId)
                .toList();
    }

    /** 引用回复的目标 Agent（引用的不是 Agent 消息则 null） */
    private Long resolveRepliedAgent(Long replyToMessageId) {
        if (replyToMessageId == null) {
            return null;
        }
        return messageRepository.findById(replyToMessageId)
                .map(m -> {
                    if (m.getSenderType() == SenderType.AGENT) {
                        LogHelper.printLog(ChatOrchestrator.class, "ChatOrchestrator.resolveRepliedAgent",
                                "ON_USER_MESSAGE", "引用回复目标为Agent消息",
                                "replyToMessageId={} senderType={} agentId={}",
                                replyToMessageId, m.getSenderType(), m.getSenderId());
                        return m.getSenderId();
                    }
                    LogHelper.printLog(ChatOrchestrator.class, "ChatOrchestrator.resolveRepliedAgent",
                            "ON_USER_MESSAGE", "引用回复目标非Agent消息忽略",
                            "replyToMessageId={} senderType={}", replyToMessageId, m.getSenderType());
                    return null;
                })
                .orElse(null);
    }
}
