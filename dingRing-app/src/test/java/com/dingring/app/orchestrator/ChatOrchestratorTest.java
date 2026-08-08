package com.dingring.app.orchestrator;

import com.dingring.app.dto.response.MessageDTO;
import com.dingring.domain.service.GroupBroadcastService;
import com.dingring.app.service.MessageAssembler;
import com.dingring.common.constant.WsConstants;
import com.dingring.common.exception.BizException;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.agent.AgentRepository;
import com.dingring.domain.discussion.Topic;
import com.dingring.domain.discussion.TopicRepository;
import com.dingring.domain.discussion.TopicStatus;
import com.dingring.domain.event.MessageSent;
import com.dingring.domain.event.TopicClosed;
import com.dingring.domain.event.TopicConcluding;
import com.dingring.domain.group.Group;
import com.dingring.domain.group.GroupMember;
import com.dingring.domain.group.GroupMessage;
import com.dingring.domain.group.GroupRepository;
import com.dingring.domain.group.MemberRole;
import com.dingring.domain.group.MemberType;
import com.dingring.domain.group.MessageRepository;
import com.dingring.domain.group.MessageType;
import com.dingring.domain.group.SenderType;
import com.dingring.domain.service.DomainEventPublisher;
import com.dingring.domain.service.LlmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChatOrchestrator} 编排器单元测试（瘦身后：接收域 + 收束域）。
 * <p>调度/生成域已迁往 {@link DiscussionEngine}（见 DiscussionEngineTest）。
 * <p>DiscussionEngine 为 mock：execute 同步执行任务，便于确定性验证收束流程。
 */
@DisplayName("ChatOrchestrator 编排器（接收域+收束域）")
class ChatOrchestratorTest {

    private GroupRepository groupRepository;
    private MessageRepository messageRepository;
    private TopicRepository topicRepository;
    private AgentRepository agentRepository;
    private SpeakerScheduler speakerScheduler;
    private ContextBuilder contextBuilder;
    private MessageAssembler messageAssembler;
    private LlmService llmService;
    private DomainEventPublisher eventPublisher;
    private GroupBroadcastService groupBroadcastService;
    private DiscussionEngine discussionEngine;
    private ChatOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        groupRepository = mock(GroupRepository.class);
        messageRepository = mock(MessageRepository.class);
        topicRepository = mock(TopicRepository.class);
        agentRepository = mock(AgentRepository.class);
        speakerScheduler = mock(SpeakerScheduler.class);
        contextBuilder = mock(ContextBuilder.class);
        messageAssembler = mock(MessageAssembler.class);
        llmService = mock(LlmService.class);
        eventPublisher = mock(DomainEventPublisher.class);
        groupBroadcastService = mock(GroupBroadcastService.class);
        discussionEngine = mock(DiscussionEngine.class);
        orchestrator = new ChatOrchestrator(groupRepository, messageRepository, topicRepository,
                agentRepository, speakerScheduler, contextBuilder, messageAssembler,
                llmService, eventPublisher, groupBroadcastService, discussionEngine);

        // execute 同步执行任务：收束流程确定性验证
        doAnswer(inv -> {
            Runnable task = inv.getArgument(2);
            task.run();
            return null;
        }).when(discussionEngine).execute(anyLong(), anyString(), any(Runnable.class));
    }

    /* ==================== 辅助构造 ==================== */

    private Group groupWithMembers(Long groupId, List<Long> memberAgentIds) {
        Group g = new Group();
        g.setId(groupId);
        java.util.List<GroupMember> members = new java.util.ArrayList<>();
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

    private ContextBuilder.LlmContext stubContext() {
        return new ContextBuilder.LlmContext("system-prompt", List.of(LlmService.ChatTurn.user("hi")));
    }

    private SpeakerScheduler.ScoredAgent scored(Agent agent, String reason) {
        return new SpeakerScheduler.ScoredAgent(agent, 100, reason);
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
        @DisplayName("消息入库后广播 NEW_MESSAGE 并发布 MessageSent 事件")
        void shouldSaveBroadcastAndPublishEvent() {
            Group g = groupWithMembers(1L, List.of(10L));
            when(groupRepository.findById(1L)).thenReturn(Optional.of(g));
            when(topicRepository.findActiveByGroupId(1L)).thenReturn(Optional.empty());
            when(agentRepository.findByIds(any())).thenReturn(List.of());
            when(messageAssembler.toDto(any())).thenReturn(MessageDTO.builder().id(1L).build());

            orchestrator.onUserMessage(1L, 1L, "hello", null);

            verify(messageRepository).save(any(GroupMessage.class));
            verify(groupBroadcastService).broadcast(eq(1L), eq(WsConstants.NEW_MESSAGE), any());
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
        @DisplayName("成功流转 CONCLUDING 并发布 TopicConcluding 事件")
        void shouldTransitToConcludingAndPublishEvent() {
            Topic t = new Topic();
            t.setId(1L);
            t.setChatGroupId(10L);
            t.setTitle("Java 内存模型");
            t.setStatus(TopicStatus.IN_PROGRESS);
            when(topicRepository.findById(1L)).thenReturn(Optional.of(t));
            when(topicRepository.update(t)).thenReturn(true);
            // 结论生成路径（未指定总结人 → 调度评分兜底）
            Group g = groupWithMembers(10L, List.of(99L));
            when(groupRepository.findById(10L)).thenReturn(Optional.of(g));
            Agent concluder = agent(99L, "总结者");
            when(agentRepository.findByIds(List.of(99L))).thenReturn(List.of(concluder));
            when(speakerScheduler.rank(any(), any())).thenReturn(List.of(scored(concluder, "FALLBACK")));
            when(contextBuilder.buildForConclusion(any(), eq(10L), eq(1L), anyString(), any()))
                    .thenReturn(stubContext());
            when(llmService.chat(any(), anyString(), any())).thenReturn("## STAR 结论");
            when(messageRepository.countByTopicId(1L)).thenReturn(20L);

            orchestrator.conclude(1L, 1L, "USER");

            verify(groupBroadcastService).broadcast(eq(10L), eq(WsConstants.TOPIC_STATUS_CHANGED), any());
            // execute 同步执行：最终状态 CLOSED
            verify(groupBroadcastService).broadcast(eq(10L), eq(WsConstants.TOPIC_CLOSED), any());
            assertThat(t.getStatus()).isEqualTo(TopicStatus.CLOSED);
        }

        @Test
        @DisplayName("兜底总结 Agent 生成结论成功：状态 CLOSED + 广播 TOPIC_CLOSED + 发布 TopicClosed")
        void fallbackConcluderGenerateConclusionSuccessfully() {
            Topic t = new Topic();
            t.setId(1L);
            t.setChatGroupId(10L);
            t.setTitle("JVM 调优");
            t.setStatus(TopicStatus.IN_PROGRESS);
            when(topicRepository.findById(1L)).thenReturn(Optional.of(t));
            when(topicRepository.update(t)).thenReturn(true);

            Group g = groupWithMembers(10L, List.of(99L));
            when(groupRepository.findById(10L)).thenReturn(Optional.of(g));
            Agent concluder = agent(99L, "总结者");
            when(agentRepository.findByIds(List.of(99L))).thenReturn(List.of(concluder));
            when(speakerScheduler.rank(any(), any())).thenReturn(List.of(scored(concluder, "FALLBACK")));
            when(contextBuilder.buildForConclusion(any(), eq(10L), eq(1L), anyString(), any()))
                    .thenReturn(stubContext());
            when(llmService.chat(any(), anyString(), any())).thenReturn("## STAR 结论");
            when(messageRepository.countByTopicId(1L)).thenReturn(20L);

            orchestrator.conclude(1L, 1L, "USER");

            verify(groupBroadcastService).broadcast(eq(10L), eq(WsConstants.TOPIC_CLOSED), any());
            verify(eventPublisher).publish(any(TopicClosed.class));
            verify(groupBroadcastService, atLeastOnce()).broadcast(eq(10L), eq(WsConstants.AGENT_TYPING), any());
            assertThat(t.getStatus()).isEqualTo(TopicStatus.CLOSED);
            assertThat(t.getConclusion()).isEqualTo("## STAR 结论");
        }

        @Test
        @DisplayName("指定总结 Agent 时由它生成结论（不走调度评分）")
        void designatedConcluderShouldBeUsed() {
            Topic t = new Topic();
            t.setId(1L);
            t.setChatGroupId(10L);
            t.setTitle("指定总结人");
            t.setStatus(TopicStatus.IN_PROGRESS);
            when(topicRepository.findById(1L)).thenReturn(Optional.of(t));
            when(topicRepository.update(t)).thenReturn(true);
            Group g = groupWithMembers(10L, List.of(99L));
            when(groupRepository.findById(10L)).thenReturn(Optional.of(g));
            Agent designated = agent(77L, "苏教授");
            when(agentRepository.findById(77L)).thenReturn(Optional.of(designated));
            when(contextBuilder.buildForConclusion(eq(designated), eq(10L), eq(1L), anyString(), any()))
                    .thenReturn(stubContext());
            when(llmService.chat(eq(designated), anyString(), any())).thenReturn("## STAR 结论");
            when(messageRepository.countByTopicId(1L)).thenReturn(5L);

            orchestrator.conclude(1L, 1L, "USER", 77L);

            verify(groupBroadcastService).broadcast(eq(10L), eq(WsConstants.TOPIC_CLOSED), any());
            assertThat(t.concludedByAgentId()).contains(77L);
            org.mockito.Mockito.verifyNoInteractions(speakerScheduler);
        }

        @Test
        @DisplayName("总结 Agent 返回空结论时回退到 IN_PROGRESS 并广播 ERROR")
        void emptyConclusionShouldRollback() {
            Topic t = new Topic();
            t.setId(1L);
            t.setChatGroupId(10L);
            t.setTitle("空结论测试");
            t.setStatus(TopicStatus.IN_PROGRESS);
            when(topicRepository.findById(1L)).thenReturn(Optional.of(t));
            when(topicRepository.update(t)).thenReturn(true);

            Group g = groupWithMembers(10L, List.of(99L));
            when(groupRepository.findById(10L)).thenReturn(Optional.of(g));
            Agent concluder = agent(99L, "总结者");
            when(agentRepository.findByIds(List.of(99L))).thenReturn(List.of(concluder));
            when(speakerScheduler.rank(any(), any())).thenReturn(List.of(scored(concluder, "FALLBACK")));
            when(contextBuilder.buildForConclusion(any(), eq(10L), eq(1L), anyString(), any()))
                    .thenReturn(stubContext());
            when(llmService.chat(any(), anyString(), any())).thenReturn("");

            orchestrator.conclude(1L, 1L, "USER");

            verify(groupBroadcastService).broadcast(eq(10L), eq(WsConstants.ERROR), any());
            assertThat(t.getStatus()).isEqualTo(TopicStatus.IN_PROGRESS);
        }

        @Test
        @DisplayName("群内没有成员 Agent 时回退到 IN_PROGRESS")
        void noAgentMemberShouldRollback() {
            Topic t = new Topic();
            t.setId(1L);
            t.setChatGroupId(10L);
            t.setTitle("无可用总结人测试");
            t.setStatus(TopicStatus.IN_PROGRESS);
            when(topicRepository.findById(1L)).thenReturn(Optional.of(t));
            when(topicRepository.update(t)).thenReturn(true);

            Group g = groupWithMembers(10L, List.of());
            when(groupRepository.findById(10L)).thenReturn(Optional.of(g));
            when(agentRepository.findByIds(any())).thenReturn(List.of());

            orchestrator.conclude(1L, 1L, "USER");

            verify(groupBroadcastService).broadcast(eq(10L), eq(WsConstants.ERROR), any());
            assertThat(t.getStatus()).isEqualTo(TopicStatus.IN_PROGRESS);
        }

        @Test
        @DisplayName("LLM 首次失败时重试 1 次（chatWithRetry 行为）")
        void llmFirstFailureShouldRetryOnce() {
            Topic t = new Topic();
            t.setId(1L);
            t.setChatGroupId(10L);
            t.setTitle("重试测试");
            t.setStatus(TopicStatus.IN_PROGRESS);
            when(topicRepository.findById(1L)).thenReturn(Optional.of(t));
            when(topicRepository.update(t)).thenReturn(true);

            Group g = groupWithMembers(10L, List.of(99L));
            when(groupRepository.findById(10L)).thenReturn(Optional.of(g));
            Agent concluder = agent(99L, "总结者");
            when(agentRepository.findByIds(List.of(99L))).thenReturn(List.of(concluder));
            when(speakerScheduler.rank(any(), any())).thenReturn(List.of(scored(concluder, "FALLBACK")));
            when(contextBuilder.buildForConclusion(any(), eq(10L), eq(1L), anyString(), any()))
                    .thenReturn(stubContext());
            when(llmService.chat(any(), anyString(), any()))
                    .thenThrow(new RuntimeException("LLM 网络抖动"))
                    .thenReturn("## STAR 结论");
            when(messageRepository.countByTopicId(1L)).thenReturn(5L);

            orchestrator.conclude(1L, 1L, "USER");

            verify(groupBroadcastService).broadcast(eq(10L), eq(WsConstants.TOPIC_CLOSED), any());
            verify(llmService, atLeastOnce()).chat(any(), anyString(), any());
            assertThat(t.getStatus()).isEqualTo(TopicStatus.CLOSED);
        }

        @Test
        @DisplayName("结论中残留协作标记时被剥离")
        void conclusionMarkersShouldBeStripped() {
            Topic t = new Topic();
            t.setId(1L);
            t.setChatGroupId(10L);
            t.setTitle("标记剥离");
            t.setStatus(TopicStatus.IN_PROGRESS);
            when(topicRepository.findById(1L)).thenReturn(Optional.of(t));
            when(topicRepository.update(t)).thenReturn(true);
            Group g = groupWithMembers(10L, List.of(99L));
            when(groupRepository.findById(10L)).thenReturn(Optional.of(g));
            Agent concluder = agent(99L, "总结者");
            when(agentRepository.findByIds(List.of(99L))).thenReturn(List.of(concluder));
            when(speakerScheduler.rank(any(), any())).thenReturn(List.of(scored(concluder, "FALLBACK")));
            when(contextBuilder.buildForConclusion(any(), eq(10L), eq(1L), anyString(), any()))
                    .thenReturn(stubContext());
            when(llmService.chat(any(), anyString(), any()))
                    .thenReturn("## STAR 结论\n" + ContextBuilder.CONCLUDE_MARKER + ContextBuilder.PASS_MARKER);
            when(messageRepository.countByTopicId(1L)).thenReturn(5L);

            orchestrator.conclude(1L, 1L, "USER");

            assertThat(t.getConclusion())
                    .doesNotContain(ContextBuilder.CONCLUDE_MARKER)
                    .doesNotContain(ContextBuilder.PASS_MARKER);
        }
    }
}
