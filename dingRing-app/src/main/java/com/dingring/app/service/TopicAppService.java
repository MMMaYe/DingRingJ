package com.dingring.app.service;

import com.dingring.app.dto.response.ConclusionDTO;
import com.dingring.app.dto.response.MessageDTO;
import com.dingring.app.dto.response.TopicDigest;
import com.dingring.app.dto.response.TopicSummary;
import com.dingring.app.orchestrator.ChatOrchestrator;
import com.dingring.app.orchestrator.Terminator;
import com.dingring.common.exception.BizException;
import com.dingring.common.exception.ErrorCode;
import com.dingring.common.response.PageResult;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.discussion.Topic;
import com.dingring.domain.discussion.TopicRepository;
import com.dingring.domain.group.Group;
import com.dingring.domain.group.GroupRepository;
import com.dingring.domain.group.MessageRepository;
import com.dingring.domain.agent.AgentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 主题讨论应用服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TopicAppService {

    private final TopicRepository topicRepository;
    private final MessageRepository messageRepository;
    private final AgentRepository agentRepository;
    private final GroupRepository groupRepository;
    private final MessageAssembler messageAssembler;
    private final ChatOrchestrator chatOrchestrator;
    private final Terminator terminator;

    /** 触发结束讨论（REST / WS CONCLUDE_TOPIC） */
    public void conclude(Long topicId) {
        chatOrchestrator.conclude(topicId, GroupAppService.DEFAULT_USER_ID, "USER");
    }

    /** 群的全部主题（倒序） */
    public List<TopicSummary> listByGroup(Long groupId) {
        return topicRepository.findByGroupId(groupId).stream().map(this::toSummary).toList();
    }

    /** 全部已关闭主题（主题沉淀区用，跨群，带来源群名） */
    public List<TopicDigest> listAllClosed() {
        Map<Long, Group> groupMap = groupRepository.findAll().stream()
                .collect(Collectors.toMap(Group::getId, Function.identity()));
        return topicRepository.findAllClosed().stream()
                .map(t -> TopicDigest.builder()
                        .id(t.getId())
                        .title(t.getTitle())
                        .groupId(t.getChatGroupId())
                        .groupName(groupMap.getOrDefault(t.getChatGroupId(), new Group()).getName())
                        .messageCount(messageRepository.countByTopicId(t.getId()))
                        .closedAt(t.getClosedAt())
                        .createTime(t.getCreateTime())
                        .build())
                .toList();
    }

    /** 主题消息分页（page 从 1 开始，时间升序） */
    public PageResult<MessageDTO> messages(Long topicId, int page, int pageSize) {
        long total = messageRepository.countByTopicId(topicId);
        List<MessageDTO> items = messageAssembler.toBatchDtos(
                messageRepository.findByTopicId(topicId, (page - 1) * pageSize, pageSize));
        return new PageResult<>(items, total, page, pageSize);
    }

    /** 群消息分页（含闲聊，群聊主窗口用）。
     *  page 为 1 基、从最早页开始计数；page<=0 时取【最新一页】，
     *  用于打开群聊即见最新消息，向上滚动再按 page-1 往前翻更早历史。 */
    public PageResult<MessageDTO> groupMessages(Long groupId, int page, int pageSize) {
        long total = messageRepository.countByGroupId(groupId);
        int lastPage = (int) Math.max(1, Math.ceil((double) total / pageSize));
        int effectivePage = page < 1 ? lastPage : Math.min(page, lastPage);
        List<MessageDTO> items = messageAssembler.toBatchDtos(
                messageRepository.findByGroupId(groupId, (effectivePage - 1) * pageSize, pageSize));
        return new PageResult<>(items, total, effectivePage, pageSize);
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
                .round(terminator.currentRound(topic.getId()))
                .maxRounds(terminator.getMaxRounds())
                .createTime(topic.getCreateTime())
                .build();
    }
}
