package com.dingring.app.service;

import com.dingring.app.dto.response.ConclusionDTO;
import com.dingring.app.dto.response.MessageDTO;
import com.dingring.app.dto.response.TopicSummary;
import com.dingring.app.orchestrator.ChatOrchestrator;
import com.dingring.app.orchestrator.DiscussionEngine;
import com.dingring.common.constant.WsConstants;
import com.dingring.common.exception.BizException;
import com.dingring.common.exception.ErrorCode;
import com.dingring.common.response.PageResult;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.discussion.Topic;
import com.dingring.domain.discussion.TopicRepository;
import com.dingring.domain.discussion.TopicStatus;
import com.dingring.domain.event.TopicCreated;
import com.dingring.domain.group.GroupRepository;
import com.dingring.domain.group.MessageRepository;
import com.dingring.domain.agent.AgentRepository;
import com.dingring.domain.service.DomainEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 主题讨论应用服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TopicAppService {

    private final TopicRepository topicRepository;
    private final GroupRepository groupRepository;
    private final MessageRepository messageRepository;
    private final AgentRepository agentRepository;
    private final MessageAssembler messageAssembler;
    private final ChatOrchestrator chatOrchestrator;
    private final DiscussionEngine discussionEngine;
    private final DomainEventPublisher eventPublisher;
    private final ChatPusher chatPusher;

    /** 创建主题（不变式：一个群同时只有一个 IN_PROGRESS 的 Topic） */
    public TopicSummary createTopic(Long groupId, String title) {
        groupRepository.findById(groupId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "群不存在: " + groupId));
        if (topicRepository.findActiveByGroupId(groupId).isPresent()) {
            throw new BizException(ErrorCode.TOPIC_ALREADY_IN_PROGRESS);
        }
        Topic topic = new Topic();
        topic.setChatGroupId(groupId);
        topic.setTitle(title);
        topic.setStatus(TopicStatus.IN_PROGRESS);
        try {
            topicRepository.save(topic);
        } catch (DuplicateKeyException e) {
            // UK(chat_group_id, title) 或并发创建
            throw new BizException(ErrorCode.TOPIC_ALREADY_IN_PROGRESS, "主题已存在或群内已有进行中的讨论");
        }
        eventPublisher.publish(new TopicCreated(topic.getId(), groupId, title));
        chatPusher.pushToGroup(groupId, WsConstants.TOPIC_CREATED, Map.of(
                "groupId", groupId,
                "topicId", topic.getId(),
                "title", title,
                "status", topic.getStatus().name()));
        // 手动建题后唤醒引擎：进入讨论态自主推进
        discussionEngine.wake(groupId);
        return toSummary(topic);
    }

    /** 触发结束讨论（REST / WS CONCLUDE_TOPIC） */
    public void conclude(Long topicId) {
        chatOrchestrator.conclude(topicId, GroupAppService.DEFAULT_USER_ID, "USER");
    }

    /** 群的全部主题（倒序） */
    public List<TopicSummary> listByGroup(Long groupId) {
        return topicRepository.findByGroupId(groupId).stream().map(this::toSummary).toList();
    }

    /** 主题消息分页（page 从 1 开始，时间升序） */
    public PageResult<MessageDTO> messages(Long topicId, int page, int pageSize) {
        long total = messageRepository.countByTopicId(topicId);
        List<MessageDTO> items = messageAssembler.toDtos(
                messageRepository.findByTopicId(topicId, (page - 1) * pageSize, pageSize));
        return new PageResult<>(items, total, page, pageSize);
    }

    /** 群消息分页（含闲聊，群聊主窗口用） */
    public PageResult<MessageDTO> groupMessages(Long groupId, int page, int pageSize) {
        long total = messageRepository.countByGroupId(groupId);
        List<MessageDTO> items = messageAssembler.toDtos(
                messageRepository.findByGroupId(groupId, (page - 1) * pageSize, pageSize));
        return new PageResult<>(items, total, page, pageSize);
    }

    /** 获取结论 */
    public ConclusionDTO conclusion(Long topicId) {
        Topic topic = topicRepository.findById(topicId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "主题不存在: " + topicId));
        if (topic.getConclusion() == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "主题尚未生成结论");
        }
        String concluderName = topic.concludedByAgentId()
                .flatMap(agentRepository::findById)
                .map(Agent::getName)
                .orElse(null);
        return ConclusionDTO.builder()
                .topicId(topic.getId())
                .title(topic.getTitle())
                .conclusion(topic.getConclusion())
                .messageCount(messageRepository.countByTopicId(topicId))
                .closedAt(topic.getClosedAt())
                .concluderAgentName(concluderName)
                .build();
    }

    private TopicSummary toSummary(Topic topic) {
        return TopicSummary.builder()
                .id(topic.getId())
                .title(topic.getTitle())
                .status(topic.getStatus().name())
                .messageCount(messageRepository.countByTopicId(topic.getId()))
                .createTime(topic.getCreateTime())
                .build();
    }
}
