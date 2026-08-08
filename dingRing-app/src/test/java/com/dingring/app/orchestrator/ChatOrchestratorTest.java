package com.dingring.app.orchestrator;

import com.dingring.app.dto.response.MessageDTO;
import com.dingring.app.service.MessageAssembler;
import com.dingring.common.constant.WsConstants;
import com.dingring.common.exception.BizException;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.agent.AgentRepository;
import com.dingring.domain.discussion.Topic;
import com.dingring.domain.discussion.TopicRepository;
import com.dingring.domain.discussion.TopicStatus;
import com.dingring.domain.group.Group;
import com.dingring.domain.group.GroupMember;
import com.dingring.domain.group.GroupMessage;
import com.dingring.domain.group.GroupRepository;
import com.dingring.domain.group.MemberRole;
import com.dingring.domain.group.MemberType;
import com.dingring.domain.group.MessageRepository;
import com.dingring.domain.group.MessageType;
import com.dingring.domain.group.SenderType;
import com.dingring.domain.service.GroupBroadcastService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChatOrchestrator} 编排器单元测试（Phase C 瘦身后：接收域 + 收束域）。
 * <p>结论生成逻辑已迁移到 {@link com.dingring.app.workflow.node.ConcludeNode}，
 * 本类只验证：消息入库广播 + 信号投递 + 收束状态流转 + 异步触发。
 * <p>DiscussionEngine 为 mock：仅验证 execute 被调用，不验证收束流程内部（见 ConcludeNodeTest）。
 */
@DisplayName("ChatOrchestrator 编排器（接收域+收束域）")
class ChatOrchestratorTest {

    private GroupRepository groupRepository;
    private MessageRepository messageRepository;
    private TopicRepository topicRepository;
    private AgentRepository agentRepository;
    private MessageAssembler messageAssembler;
    private GroupBroadcastService groupBroadcastService;
    private DiscussionEngine discussionEngine;
    private ChatOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        groupRepository = mock(GroupRepository.class);
        messageRepository = mock(MessageRepository.class);
        topicRepository = mock(TopicRepository.class);
        agentRepository = mock(AgentRepository.class);
        messageAssembler = mock(MessageAssembler.class);
        groupBroadcastService = mock(GroupBroadcastService.class);
        discussionEngine = mock(DiscussionEngine.class);
        orchestrator = new ChatOrchestrator(groupRepository, messageRepository, topicRepository,
                agentRepository, messageAssembler, groupBroadcastService, discussionEngine);
    }

    /* ==================== 辅助构造 ==================== */

    private Group groupWithMembers(Long groupId, List<Long> memberAgentIds) {
        Group g = new Group();
        g.setId(groupId);
        List<GroupMember> members = new ArrayList<>();
        memberAgentIds.forEach(id -> members.add(new GroupMember(id, MemberType.AGENT, MemberRole.MEMBER)));
        g.setGroupMember(members);
        return g;
    }

    private Agent agent(Long id, String name) {
        Agent a = new Agent();
        a.setId(id);
        a.setName(name);
        return a;
    }

    private GroupMessage savedMessage(Long id, Long groupId, Long topicId, Long senderId, SenderType type) {
        GroupMessage m = new GroupMessage();
        m.setId(id);
        m.setChatGroupId(groupId);
        m.setTopicId(topicId);
        m.setSenderId(senderId);
        m.setSenderType(type);
        m.setMessageType(MessageType.TEXT);
        m.setContent("content");
        return m;
    }

    /* ==================== 接收域：onUserMessage ==================== */

    @Nested
    @DisplayName("onUserMessage 用户消息处理")
    class OnUserMessage {

        @Test
        @DisplayName("群不存在时抛 BizException")
        void groupNotFoundShouldThrow() {
            when(groupRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> orchestrator.onUserMessage(99L, 1L, "hello", null))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("群不存在");
        }

        @Test
        @DisplayName("消息入库后广播 NEW_MESSAGE 并投递信号给引擎")
        void shouldSaveBroadcastAndDeliverSignal() {
            Group g = groupWithMembers(1L, List.of(10L));
            when(groupRepository.findById(1L)).thenReturn(Optional.of(g));
            when(topicRepository.findActiveByGroupId(1L)).thenReturn(Optional.empty());
            when(agentRepository.findByIds(any())).thenReturn(List.of());
            when(messageAssembler.toDto(any())).thenReturn(MessageDTO.builder().id(1L).build());

            orchestrator.onUserMessage(1L, 1L, "hello", null);

            verify(messageRepository).save(any(GroupMessage.class));
            verify(groupBroadcastService).broadcast(eq(1L), eq(WsConstants.NEW_MESSAGE), any());
            verify(discussionEngine).onUserSignal(eq(1L), any(DiscussionEngine.UserSignal.class));
        }

        @Test
        @DisplayName("有活跃 Topic 时 topicId 自动归属消息")
        void activeTopicShouldBeAttachedToMessage() {
            Group g = groupWithMembers(1L, List.of(10L));
            when(groupRepository.findById(1L)).thenReturn(Optional.of(g));
            Topic t = new Topic();
            t.setId(100L);
            t.setStatus(TopicStatus.IN_PROGRESS);
            when(topicRepository.findActiveByGroupId(1L)).thenReturn(Optional.of(t));
            when(agentRepository.findByIds(any())).thenReturn(List.of());
            when(messageAssembler.toDto(any())).thenReturn(MessageDTO.builder().build());

            orchestrator.onUserMessage(1L, 1L, "hello", null);

            ArgumentCaptor<GroupMessage> captor = ArgumentCaptor.forClass(GroupMessage.class);
            verify(messageRepository).save(captor.capture());
            assertThat(captor.getValue().getTopicId()).isEqualTo(100L);
        }

        @Test
        @DisplayName("投递信号给引擎：@提及被解析进 UserSignal")
        void shouldDeliverSignalWithParsedMentions() {
            Agent laoWang = agent(10L, "老王");
            Group g = groupWithMembers(1L, List.of(10L));
            when(groupRepository.findById(1L)).thenReturn(Optional.of(g));
            when(topicRepository.findActiveByGroupId(1L)).thenReturn(Optional.empty());
            when(agentRepository.findByIds(any())).thenReturn(List.of(laoWang));
            when(messageAssembler.toDto(any())).thenReturn(MessageDTO.builder().build());

            orchestrator.onUserMessage(1L, 7L, "@老王 你怎么看", null);

            ArgumentCaptor<DiscussionEngine.UserSignal> captor =
                    ArgumentCaptor.forClass(DiscussionEngine.UserSignal.class);
            verify(discussionEngine).onUserSignal(eq(1L), captor.capture());
            assertThat(captor.getValue().userId()).isEqualTo(7L);
            assertThat(captor.getValue().content()).isEqualTo("@老王 你怎么看");
            assertThat(captor.getValue().mentionedAgentIds()).containsExactly(10L);
        }

        @Test
        @DisplayName("引用 Agent 消息时 repliedToAgentId 被解析进 UserSignal")
        void replyToAgentMessageShouldBeResolved() {
            Group g = groupWithMembers(1L, List.of(10L));
            when(groupRepository.findById(1L)).thenReturn(Optional.of(g));
            when(topicRepository.findActiveByGroupId(1L)).thenReturn(Optional.empty());
            GroupMessage replied = savedMessage(50L, 1L, null, 10L, SenderType.AGENT);
            when(messageRepository.findById(50L)).thenReturn(Optional.of(replied));
            when(agentRepository.findByIds(any())).thenReturn(List.of());
            when(messageAssembler.toDto(any())).thenReturn(MessageDTO.builder().build());

            orchestrator.onUserMessage(1L, 1L, "回复", 50L);

            ArgumentCaptor<DiscussionEngine.UserSignal> captor =
                    ArgumentCaptor.forClass(DiscussionEngine.UserSignal.class);
            verify(discussionEngine).onUserSignal(eq(1L), captor.capture());
            assertThat(captor.getValue().repliedToAgentId()).isEqualTo(10L);
        }
    }

    /* ==================== 收束域：conclude ==================== */

    @Nested
    @DisplayName("conclude 触发收束")
    class Conclude {

        @Test
        @DisplayName("主题不存在时抛 BizException")
        void topicNotFoundShouldThrow() {
            when(topicRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> orchestrator.conclude(99L, 1L, "USER"))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("主题不存在");
        }

        @Test
        @DisplayName("乐观锁更新失败时抛 TOPIC_NOT_IN_PROGRESS")
        void optimisticLockFailShouldThrow() {
            Topic t = new Topic();
            t.setId(1L);
            t.setStatus(TopicStatus.IN_PROGRESS);
            when(topicRepository.findById(1L)).thenReturn(Optional.of(t));
            when(topicRepository.update(t)).thenReturn(false);

            assertThatThrownBy(() -> orchestrator.conclude(1L, 1L, "USER"))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("主题状态已变更");
        }

        @Test
        @DisplayName("成功流转 CONCLUDING 并广播状态变更 + 异步触发收束流程")
        void shouldTransitToConcludingAndTriggerAsyncConclude() {
            Topic t = new Topic();
            t.setId(1L);
            t.setChatGroupId(10L);
            t.setTitle("Java 内存模型");
            t.setStatus(TopicStatus.IN_PROGRESS);
            when(topicRepository.findById(1L)).thenReturn(Optional.of(t));
            when(topicRepository.update(t)).thenReturn(true);

            orchestrator.conclude(1L, 1L, "USER", 77L);

            // 同步：状态流转到 CONCLUDING
            assertThat(t.getStatus()).isEqualTo(TopicStatus.CONCLUDING);
            verify(groupBroadcastService).broadcast(eq(10L), eq(WsConstants.TOPIC_STATUS_CHANGED), any());
            // 异步：排队收束任务到群串行执行器
            verify(discussionEngine).execute(eq(10L), anyString(), any(Runnable.class));
        }

        @Test
        @DisplayName("已是 CONCLUDING 状态时跳过状态流转但仍异步触发收束")
        void alreadyConcludingShouldSkipTransitionButTriggerAsync() {
            Topic t = new Topic();
            t.setId(1L);
            t.setChatGroupId(10L);
            t.setTitle("已收束中");
            t.setStatus(TopicStatus.CONCLUDING);
            when(topicRepository.findById(1L)).thenReturn(Optional.of(t));

            orchestrator.conclude(1L, 1L, "USER", 77L);

            // 不应再 update（避免重复状态流转）
            verify(topicRepository, never()).update(any(Topic.class));
            verify(groupBroadcastService, never()).broadcast(eq(10L), eq(WsConstants.TOPIC_STATUS_CHANGED), any());
            // 但仍应异步触发收束流程（ConcludeNode 检测已 CONCLUDING 直接生成结论）
            verify(discussionEngine).execute(eq(10L), anyString(), any(Runnable.class));
        }

        @Test
        @DisplayName("三参数重载 conclude：concluderAgentId 默认 null")
        void threeArgConcludeShouldDelegateToFourArg() {
            Topic t = new Topic();
            t.setId(1L);
            t.setChatGroupId(10L);
            t.setTitle("三参数重载");
            t.setStatus(TopicStatus.IN_PROGRESS);
            when(topicRepository.findById(1L)).thenReturn(Optional.of(t));
            when(topicRepository.update(t)).thenReturn(true);

            orchestrator.conclude(1L, 1L, "MAX_ROUNDS");

            assertThat(t.getStatus()).isEqualTo(TopicStatus.CONCLUDING);
            verify(discussionEngine).execute(eq(10L), anyString(), any(Runnable.class));
        }
    }
}
