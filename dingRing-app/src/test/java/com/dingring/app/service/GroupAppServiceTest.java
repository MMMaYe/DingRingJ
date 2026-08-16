package com.dingring.app.service;

import com.dingring.app.dto.request.CreateGroupRequest;
import com.dingring.app.dto.request.UpdateMembersRequest;
import com.dingring.app.dto.response.GroupDetail;
import com.dingring.app.dto.response.GroupSummary;
import com.dingring.app.dto.response.MemberInfo;
import com.dingring.app.orchestrator.Terminator;
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
import com.dingring.domain.knowledgebase.KnowledgeBase;
import com.dingring.domain.knowledgebase.KnowledgeBaseRepository;
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
    private KnowledgeBaseRepository knowledgeBaseRepository;
    private TopicRepository topicRepository;
    private MessageRepository messageRepository;
    private UserRepository userRepository;
    private DomainEventPublisher eventPublisher;
    private Terminator terminator;
    private GroupAppService service;

    @BeforeEach
    void setUp() {
        groupRepository = mock(GroupRepository.class);
        agentRepository = mock(AgentRepository.class);
        knowledgeBaseRepository = mock(KnowledgeBaseRepository.class);
        topicRepository = mock(TopicRepository.class);
        messageRepository = mock(MessageRepository.class);
        userRepository = mock(UserRepository.class);
        eventPublisher = mock(DomainEventPublisher.class);
        terminator = mock(Terminator.class);
        service = new GroupAppService(groupRepository, agentRepository, knowledgeBaseRepository, topicRepository,
                messageRepository, userRepository, eventPublisher, terminator);
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
    @DisplayName("知识库绑定（knowledge_base_config.kbIds）")
    class KbBinding {

        private KnowledgeBase kb(Long id) {
            KnowledgeBase k = new KnowledgeBase();
            k.setId(id);
            k.setName("kb-" + id);
            return k;
        }

        @Test
        @DisplayName("创建群携带 kbIds：校验通过后写入 config")
        void createWithKbIdsShouldPersistConfig() {
            CreateGroupRequest req = new CreateGroupRequest();
            req.setName("群");
            req.setAgentIds(List.of(10L));
            req.setKbIds(List.of(5L, 6L));
            when(agentRepository.findByIds(List.of(10L))).thenReturn(List.of(agent(10L, "老王")));
            when(knowledgeBaseRepository.findById(5L)).thenReturn(Optional.of(kb(5L)));
            when(knowledgeBaseRepository.findById(6L)).thenReturn(Optional.of(kb(6L)));
            when(groupRepository.save(any(Group.class))).thenAnswer(inv -> {
                Group g = inv.getArgument(0);
                g.setId(1L);
                return 1L;
            });
            Group saved = new Group();
            saved.setId(1L);
            saved.setGroupMember(List.of(new GroupMember(1L, MemberType.USER, MemberRole.OWNER)));
            when(groupRepository.findById(1L)).thenReturn(Optional.of(saved));
            when(topicRepository.findActiveByGroupId(1L)).thenReturn(Optional.empty());

            service.create(req);

            // save 收到的聚合已带 kbIds 配置
            var captor = org.mockito.ArgumentCaptor.forClass(Group.class);
            verify(groupRepository).save(captor.capture());
            assertThat(captor.getValue().getKnowledgeBaseConfig())
                    .containsEntry("kbIds", List.of(5L, 6L));
        }

        @Test
        @DisplayName("创建群携带无效 kbId：抛 ParamException")
        void createWithInvalidKbIdShouldThrow() {
            CreateGroupRequest req = new CreateGroupRequest();
            req.setName("群");
            req.setAgentIds(List.of(10L));
            req.setKbIds(List.of(99L));
            when(agentRepository.findByIds(List.of(10L))).thenReturn(List.of(agent(10L, "老王")));
            when(knowledgeBaseRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.create(req))
                    .isInstanceOf(ParamException.class)
                    .hasMessageContaining("无效的知识库 ID");
        }

        private Group groupWithBinding() {
            Group g = new Group();
            g.setId(1L);
            g.setName("群");
            g.setGroupMember(List.of(
                    new GroupMember(1L, MemberType.USER, MemberRole.OWNER),
                    new GroupMember(10L, MemberType.AGENT, MemberRole.MEMBER)
            ));
            // 模拟已有绑定（JsonMapTypeHandler 回读时数字为 Integer，kbIdsOf 需兼容）
            g.setKnowledgeBaseConfig(java.util.Map.of("kbIds", List.of(5)));
            return g;
        }

        private UpdateMembersRequest membersReq(List<Long> agentIds, List<Long> kbIds) {
            UpdateMembersRequest req = new UpdateMembersRequest();
            req.setAgentIds(agentIds);
            req.setKbIds(kbIds);
            return req;
        }

        @Test
        @DisplayName("updateMembers 不传 kbIds：保留原绑定")
        void updateWithoutKbIdsShouldKeepBinding() {
            Group g = groupWithBinding();
            when(groupRepository.findById(1L)).thenReturn(Optional.of(g));
            when(agentRepository.findByIds(List.of(10L))).thenReturn(List.of(agent(10L, "老王")));
            when(topicRepository.findActiveByGroupId(1L)).thenReturn(Optional.empty());

            GroupDetail detail = service.updateMembers(1L, membersReq(List.of(10L), null));

            // Integer 5 被归一为 Long 5
            assertThat(detail.getKbIds()).containsExactly(5L);
        }

        @Test
        @DisplayName("updateMembers 传空列表：清空绑定")
        void updateWithEmptyKbIdsShouldClearBinding() {
            Group g = groupWithBinding();
            when(groupRepository.findById(1L)).thenReturn(Optional.of(g));
            when(agentRepository.findByIds(List.of(10L))).thenReturn(List.of(agent(10L, "老王")));
            when(topicRepository.findActiveByGroupId(1L)).thenReturn(Optional.empty());

            GroupDetail detail = service.updateMembers(1L, membersReq(List.of(10L), List.of()));

            assertThat(detail.getKbIds()).isEmpty();
            assertThat(g.getKnowledgeBaseConfig()).isNull();
        }

        @Test
        @DisplayName("updateMembers 传新 kbIds：整体覆盖")
        void updateWithNewKbIdsShouldReplace() {
            Group g = groupWithBinding();
            when(groupRepository.findById(1L)).thenReturn(Optional.of(g));
            when(agentRepository.findByIds(List.of(10L))).thenReturn(List.of(agent(10L, "老王")));
            when(knowledgeBaseRepository.findById(7L)).thenReturn(Optional.of(kb(7L)));
            when(topicRepository.findActiveByGroupId(1L)).thenReturn(Optional.empty());

            GroupDetail detail = service.updateMembers(1L, membersReq(List.of(10L), List.of(7L)));

            assertThat(detail.getKbIds()).containsExactly(7L);
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
