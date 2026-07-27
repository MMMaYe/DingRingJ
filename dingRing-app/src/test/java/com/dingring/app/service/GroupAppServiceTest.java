package com.dingring.app.service;

import com.dingring.app.dto.request.CreateGroupRequest;
import com.dingring.app.dto.response.GroupDetail;
import com.dingring.app.dto.response.GroupSummary;
import com.dingring.app.dto.response.MemberInfo;
import com.dingring.common.exception.BizException;
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
import com.dingring.domain.group.GroupMessage;
import com.dingring.domain.service.DomainEventPublisher;
import com.dingring.domain.user.User;
import com.dingring.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GroupAppService} 单元测试。
 */
@DisplayName("GroupAppService 群管理服务")
class GroupAppServiceTest {

    private GroupRepository groupRepository;
    private AgentRepository agentRepository;
    private TopicRepository topicRepository;
    private MessageRepository messageRepository;
    private UserRepository userRepository;
    private DomainEventPublisher eventPublisher;
    private GroupAppService service;

    @BeforeEach
    void setUp() {
        groupRepository = mock(GroupRepository.class);
        agentRepository = mock(AgentRepository.class);
        topicRepository = mock(TopicRepository.class);
        messageRepository = mock(MessageRepository.class);
        userRepository = mock(UserRepository.class);
        eventPublisher = mock(DomainEventPublisher.class);
        service = new GroupAppService(groupRepository, agentRepository, topicRepository,
                messageRepository, userRepository, eventPublisher);
    }

    private Agent agent(Long id, String name) {
        Agent a = new Agent();
        a.setId(id);
        a.setName(name);
        a.setProfilePicture("http://x/" + id + ".png");
        return a;
    }

    @Nested
    @DisplayName("create 创建群")
    class Create {

        @Test
        @DisplayName("存在无效 Agent ID 时抛 ParamException")
        void invalidAgentIdShouldThrow() {
            CreateGroupRequest req = new CreateGroupRequest();
            req.setName("群");
            req.setAgentIds(List.of(10L, 99L));
            // 仓储只返回 1 个，缺少 99
            when(agentRepository.findByIds(List.of(10L, 99L))).thenReturn(List.of(agent(10L, "老王")));

            assertThatThrownBy(() -> service.create(req))
                    .isInstanceOf(ParamException.class)
                    .hasMessageContaining("无效的 Agent ID");
        }

        @Test
        @DisplayName("成功创建群：成员含 OWNER 与成员 Agent")
        void shouldCreateGroupWithCorrectMembers() {
            CreateGroupRequest req = new CreateGroupRequest();
            req.setName("Java 学习群");
            req.setAgentIds(List.of(10L, 11L));
            when(agentRepository.findByIds(List.of(10L, 11L))).thenReturn(List.of(
                    agent(10L, "老王"), agent(11L, "小李")
            ));
            // 模拟 save 回填 id 后 detail 查询
            when(groupRepository.save(any(Group.class))).thenAnswer(inv -> {
                Group g = inv.getArgument(0);
                g.setId(1L);
                return 1L;
            });
            Group saved = new Group();
            saved.setId(1L);
            saved.setName("Java 学习群");
            saved.setOwnerId(GroupAppService.DEFAULT_USER_ID);
            saved.setGroupMember(List.of(
                    new GroupMember(1L, MemberType.USER, MemberRole.OWNER),
                    new GroupMember(10L, MemberType.AGENT, MemberRole.MEMBER),
                    new GroupMember(11L, MemberType.AGENT, MemberRole.MEMBER)
            ));
            when(groupRepository.findById(1L)).thenReturn(Optional.of(saved));
            when(agentRepository.findByIds(List.of(10L, 11L))).thenReturn(List.of(
                    agent(10L, "老王"), agent(11L, "小李")
            ));
            User owner = new User();
            owner.setId(1L);
            owner.setName("张三");
            when(userRepository.findById(1L)).thenReturn(Optional.of(owner));

            GroupDetail detail = service.create(req);

            assertThat(detail.getId()).isEqualTo(1L);
            assertThat(detail.getName()).isEqualTo("Java 学习群");
            // 3 个成员：1 USER + 2 成员 AGENT
            assertThat(detail.getMembers()).hasSize(3);
            // 发布 GroupCreated 事件
            verify(eventPublisher).publish(any(GroupCreated.class));
        }
    }

    @Nested
    @DisplayName("detail 群详情")
    class Detail {

        @Test
        @DisplayName("群不存在时抛 BizException")
        void groupNotFoundShouldThrow() {
            when(groupRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.detail(99L))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("群不存在");
        }

        @Test
        @DisplayName("返回群详情含成员信息（USER 与 AGENT 分别查询）")
        void shouldReturnDetailWithMembers() {
            Group g = new Group();
            g.setId(1L);
            g.setName("群");
            g.setOwnerId(1L);
            g.setGroupMember(List.of(
                    new GroupMember(1L, MemberType.USER, MemberRole.OWNER),
                    new GroupMember(10L, MemberType.AGENT, MemberRole.MEMBER)
            ));
            when(groupRepository.findById(1L)).thenReturn(Optional.of(g));
            when(agentRepository.findByIds(List.of(10L))).thenReturn(List.of(agent(10L, "老王")));
            User owner = new User();
            owner.setId(1L);
            owner.setName("张三");
            when(userRepository.findById(1L)).thenReturn(Optional.of(owner));
            when(topicRepository.findActiveByGroupId(1L)).thenReturn(Optional.empty());

            GroupDetail detail = service.detail(1L);

            assertThat(detail.getMembers()).hasSize(2);
            MemberInfo agentMember = detail.getMembers().stream()
                    .filter(m -> "AGENT".equals(m.getType())).findFirst().orElseThrow();
            assertThat(agentMember.getName()).isEqualTo("老王");
            MemberInfo userMember = detail.getMembers().stream()
                    .filter(m -> "USER".equals(m.getType())).findFirst().orElseThrow();
            assertThat(userMember.getName()).isEqualTo("张三");
            assertThat(detail.getActiveTopic()).isNull();
        }
    }

    @Nested
    @DisplayName("list 群列表")
    class ListGroups {

        @Test
        @DisplayName("返回群摘要含最后一条消息预览")
        void shouldContainLastMessagePreview() {
            Group g = new Group();
            g.setId(1L);
            g.setName("群1");
            g.setGroupMember(List.of(new GroupMember(1L, MemberType.USER, MemberRole.OWNER)));
            when(groupRepository.findAll()).thenReturn(List.of(g));
            when(topicRepository.findActiveByGroupId(1L)).thenReturn(Optional.empty());
            GroupMessage last = new GroupMessage();
            last.setContent("短消息");
            when(messageRepository.findLastByGroupId(1L)).thenReturn(Optional.of(last));

            List<GroupSummary> result = service.list();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getLastMessagePreview()).isEqualTo("短消息");
            assertThat(result.get(0).getMemberCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("最后消息超过 30 字符时截断")
        void longLastMessageShouldBeTruncated() {
            Group g = new Group();
            g.setId(1L);
            g.setName("群1");
            g.setGroupMember(List.of(new GroupMember(1L, MemberType.USER, MemberRole.OWNER)));
            when(groupRepository.findAll()).thenReturn(List.of(g));
            when(topicRepository.findActiveByGroupId(1L)).thenReturn(Optional.empty());
            GroupMessage last = new GroupMessage();
            last.setContent("a".repeat(50));
            when(messageRepository.findLastByGroupId(1L)).thenReturn(Optional.of(last));

            List<GroupSummary> result = service.list();

            // 30 + "…" = 31
            assertThat(result.get(0).getLastMessagePreview()).hasSize(31);
            assertThat(result.get(0).getLastMessagePreview()).endsWith("…");
        }
    }

    @Nested
    @DisplayName("delete 删除群")
    class Delete {

        @Test
        @DisplayName("群不存在时抛 BizException")
        void groupNotFoundShouldThrow() {
            when(groupRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.delete(99L))
                    .isInstanceOf(BizException.class);
        }

        @Test
        @DisplayName("存在时调用 deleteById")
        void shouldCallDeleteById() {
            Group g = new Group();
            g.setId(1L);
            when(groupRepository.findById(1L)).thenReturn(Optional.of(g));

            service.delete(1L);

            verify(groupRepository).deleteById(1L);
        }
    }
}
