package com.dingring.app.service;

import com.dingring.app.dto.request.CreateGroupRequest;
import com.dingring.app.dto.request.UpdateMembersRequest;
import com.dingring.app.dto.response.GroupDetail;
import com.dingring.app.dto.response.GroupSummary;
import com.dingring.app.dto.response.MemberInfo;
import com.dingring.app.dto.response.TopicSummary;
import com.dingring.common.exception.BizException;
import com.dingring.common.exception.ErrorCode;
import com.dingring.common.exception.ParamException;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.agent.AgentRepository;
import com.dingring.domain.discussion.Topic;
import com.dingring.domain.discussion.TopicRepository;
import com.dingring.domain.event.GroupCreated;
import com.dingring.domain.group.Group;
import com.dingring.domain.group.GroupMember;
import com.dingring.domain.group.GroupRepository;
import com.dingring.domain.group.MemberRole;
import com.dingring.domain.group.MemberType;
import com.dingring.domain.group.MessageRepository;
import com.dingring.domain.service.DomainEventPublisher;
import com.dingring.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 群管理应用服务。
 */
@Service
@RequiredArgsConstructor
public class GroupAppService {

    /** v1.0 单用户 */
    public static final Long DEFAULT_USER_ID = 1L;

    private static final int PREVIEW_LEN = 30;

    private final GroupRepository groupRepository;
    private final AgentRepository agentRepository;
    private final TopicRepository topicRepository;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final DomainEventPublisher eventPublisher;

    public GroupDetail create(CreateGroupRequest request) {
        if (request.getAgentIds().contains(request.getExpertAgentId())) {
            throw new ParamException("专家 Agent 不能同时是普通成员");
        }
        List<Long> allIds = new ArrayList<>(request.getAgentIds());
        allIds.add(request.getExpertAgentId());
        List<Agent> agents = agentRepository.findByIds(allIds);
        if (agents.size() != allIds.size()) {
            throw new ParamException("存在无效的 Agent ID");
        }

        Group group = new Group();
        group.setName(request.getName());
        group.setOwnerId(DEFAULT_USER_ID);
        List<GroupMember> members = new ArrayList<>();
        members.add(new GroupMember(DEFAULT_USER_ID, MemberType.USER, MemberRole.OWNER));
        for (Long agentId : request.getAgentIds()) {
            members.add(new GroupMember(agentId, MemberType.AGENT, MemberRole.MEMBER));
        }
        members.add(new GroupMember(request.getExpertAgentId(), MemberType.AGENT, MemberRole.EXPERT));
        group.setGroupMember(members);
        groupRepository.save(group);
        eventPublisher.publish(new GroupCreated(group.getId(), group.getName()));
        return detail(group.getId());
    }

    public List<GroupSummary> list() {
        return groupRepository.findAll().stream()
                .map(this::toSummary)
                .toList();
    }

    public GroupDetail detail(Long groupId) {
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "群不存在: " + groupId));
        return GroupDetail.builder()
                .id(group.getId())
                .name(group.getName())
                .ownerId(group.getOwnerId())
                .members(toMemberInfos(group))
                .activeTopic(topicRepository.findActiveByGroupId(groupId)
                        .map(this::toTopicSummary).orElse(null))
                .createTime(group.getCreateTime())
                .build();
    }

    public void delete(Long groupId) {
        groupRepository.findById(groupId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "群不存在: " + groupId));
        groupRepository.deleteById(groupId);
    }

    /**
     * 更新群成员配置（群设置-成员管理）。
     * <p>整体覆盖语义：用请求中的 agentIds / expertAgentId 完整替换原有 Agent 成员，
     * 群主 USER 成员自动保留。校验逻辑与 create 一致。
     */
    public GroupDetail updateMembers(Long groupId, UpdateMembersRequest request) {
        if (request.getAgentIds().contains(request.getExpertAgentId())) {
            throw new ParamException("专家 Agent 不能同时是普通成员");
        }
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "群不存在: " + groupId));

        // 校验所有 Agent ID 有效
        List<Long> allIds = new ArrayList<>(request.getAgentIds());
        allIds.add(request.getExpertAgentId());
        List<Agent> agents = agentRepository.findByIds(allIds);
        if (agents.size() != allIds.size()) {
            throw new ParamException("存在无效的 Agent ID");
        }

        // 保留群主 USER 成员，重建 Agent 成员列表
        List<GroupMember> members = new ArrayList<>();
        group.getGroupMember().stream()
                .filter(m -> m.getType() == MemberType.USER)
                .forEach(members::add);
        for (Long agentId : request.getAgentIds()) {
            members.add(new GroupMember(agentId, MemberType.AGENT, MemberRole.MEMBER));
        }
        members.add(new GroupMember(request.getExpertAgentId(), MemberType.AGENT, MemberRole.EXPERT));

        group.setGroupMember(members);
        group.setUpdateTime(LocalDateTime.now());
        groupRepository.update(group);
        return detail(groupId);
    }

    private GroupSummary toSummary(Group group) {
        GroupSummary.GroupSummaryBuilder builder = GroupSummary.builder()
                .id(group.getId())
                .name(group.getName())
                .memberCount(group.getGroupMember() == null ? 0 : group.getGroupMember().size())
                .activeTopicTitle(topicRepository.findActiveByGroupId(group.getId())
                        .map(Topic::getTitle).orElse(null));
        messageRepository.findLastByGroupId(group.getId()).ifPresent(last -> {
            String preview = last.getContent();
            if (preview != null && preview.length() > PREVIEW_LEN) {
                preview = preview.substring(0, PREVIEW_LEN) + "…";
            }
            builder.lastMessagePreview(preview);
            builder.lastMessageTime(last.getCreateTime());
        });
        return builder.build();
    }

    private List<MemberInfo> toMemberInfos(Group group) {
        if (group.getGroupMember() == null) {
            return List.of();
        }
        List<Long> agentIds = group.getGroupMember().stream()
                .filter(GroupMember::isAgent).map(GroupMember::getId).toList();
        Map<Long, Agent> agentMap = agentRepository.findByIds(agentIds).stream()
                .collect(Collectors.toMap(Agent::getId, Function.identity()));
        return group.getGroupMember().stream().map(m -> {
            MemberInfo.MemberInfoBuilder builder = MemberInfo.builder()
                    .id(m.getId())
                    .type(m.getType().name())
                    .role(m.getRole().name());
            if (m.isAgent()) {
                Agent agent = agentMap.get(m.getId());
                if (agent != null) {
                    builder.name(agent.getName()).avatar(agent.getProfilePicture());
                }
            } else {
                userRepository.findById(m.getId()).ifPresent(u ->
                        builder.name(u.getName()).avatar(u.getProfilePicture()));
            }
            return builder.build();
        }).toList();
    }

    private TopicSummary toTopicSummary(Topic topic) {
        return TopicSummary.builder()
                .id(topic.getId())
                .title(topic.getTitle())
                .status(topic.getStatus().name())
                .messageCount(messageRepository.countByTopicId(topic.getId()))
                .createTime(topic.getCreateTime())
                .build();
    }
}
