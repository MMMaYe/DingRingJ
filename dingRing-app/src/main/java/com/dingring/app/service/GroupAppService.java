package com.dingring.app.service;

import com.dingring.app.dto.request.CreateGroupRequest;
import com.dingring.app.dto.request.UpdateMembersRequest;
import com.dingring.app.dto.response.GroupDetail;
import com.dingring.app.dto.response.GroupSummary;
import com.dingring.app.dto.response.MemberInfo;
import com.dingring.app.dto.response.TopicSummary;
import com.dingring.app.orchestrator.Terminator;
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
import com.dingring.domain.knowledgebase.KnowledgeBaseRepository;
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
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final TopicRepository topicRepository;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final DomainEventPublisher eventPublisher;
    private final Terminator terminator;

    public GroupDetail create(CreateGroupRequest request) {
        List<Agent> agents = agentRepository.findByIds(request.getAgentIds());
        if (agents.size() != request.getAgentIds().size()) {
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
        group.setGroupMember(members);
        applyKbBinding(group, request.getKbIds());
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
                .kbIds(group.boundKbIds())
                .activeTopic(topicRepository.findActiveByGroupId(groupId)
                        .map(this::toTopicSummary).orElse(null))
                .createTime(group.getCreateTime())
                .build();
    }

    /** 删除群（逻辑删除，历史消息/主题/卡片数据保留） */
    public void delete(Long groupId) {
        groupRepository.findById(groupId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "群不存在: " + groupId));
        groupRepository.deleteById(groupId);
    }

    /**
     * 更新群成员配置（群设置-成员管理）。
     * <p>整体覆盖语义：用请求中的 agentIds 完整替换原有 Agent 成员，
     * 群主 USER 成员自动保留。校验逻辑与 create 一致。
     */
    public GroupDetail updateMembers(Long groupId, UpdateMembersRequest request) {
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "群不存在: " + groupId));

        // 校验所有 Agent ID 有效
        List<Agent> agents = agentRepository.findByIds(request.getAgentIds());
        if (agents.size() != request.getAgentIds().size()) {
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

        group.setGroupMember(members);
        // kbIds 为 null 时不动绑定（旧语义仅改成员）；空列表表示解绑全部
        applyKbBinding(group, request.getKbIds());
        group.setUpdateTime(LocalDateTime.now());
        groupRepository.update(group);
        return detail(groupId);
    }

    /**
     * 应用知识库绑定到 knowledge_base_config.kbIds。
     * <p>kbIds 为 null 时不修改（创建场景即不设置绑定；更新场景即保留原绑定），
     * 空列表表示清空绑定。
     * <p>绑定 ID 逐个校验存在性，防止落库脏引用（删除知识库不会级联清理群侧配置）。
     */
    private void applyKbBinding(Group group, List<Long> kbIds) {
        if (kbIds == null) {
            return;
        }
        for (Long kbId : kbIds) {
            knowledgeBaseRepository.findById(kbId)
                    .orElseThrow(() -> new ParamException("存在无效的知识库 ID: " + kbId));
        }
        // 当前 config 仅承载 kbIds 一个 key，直接整体写入；后续扩展多 key 时需改为合并语义
        group.setKnowledgeBaseConfig(kbIds.isEmpty()
                ? null
                : Map.of(Group.KB_IDS_KEY, kbIds.stream().distinct().toList()));
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
                .round(terminator.currentRound(topic.getId()))
                .maxRounds(terminator.getMaxRounds())
                .createTime(topic.getCreateTime())
                .build();
    }
}
