package com.dingring.app.orchestrator;

import com.dingring.app.dto.response.MessageDTO;
import com.dingring.app.service.ChatPusher;
import com.dingring.app.service.MessageAssembler;
import com.dingring.common.constant.WsConstants;
import com.dingring.common.exception.BizException;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.agent.AgentRepository;
import com.dingring.domain.discussion.Topic;
import com.dingring.domain.discussion.TopicRepository;
import com.dingring.domain.discussion.TopicStatus;
import com.dingring.domain.event.AgentFailed;
import com.dingring.domain.event.AgentSelected;
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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChatOrchestrator} 编排引擎单元测试。
 * <p>覆盖：接收域（onUserMessage）、收束域（conclude）、@专家短路、降级路由、重试机制。
 * <p>异步执行器使用 Mockito.timeout 等待虚拟线程任务完成。
 */
@DisplayName("ChatOrchestrator 编排引擎")
class ChatOrchestratorTest {

    private GroupRepository groupRepository;
    private MessageRepository messageRepository;
    private TopicRepository topicRepository;
    private AgentRepository agentRepository;
    private SpeakerScheduler speakerScheduler;
    private ContextBuilder contextBuilder;
    private Terminator terminator;
    private MessageAssembler messageAssembler;
    private LlmService llmService;
    private DomainEventPublisher eventPublisher;
    private ChatPusher chatPusher;
    private ChatOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        groupRepository = mock(GroupRepository.class);
        messageRepository = mock(MessageRepository.class);
        topicRepository = mock(TopicRepository.class);
        agentRepository = mock(AgentRepository.class);
        speakerScheduler = mock(SpeakerScheduler.class);
        contextBuilder = mock(ContextBuilder.class);
        terminator = mock(Terminator.class);
        messageAssembler = mock(MessageAssembler.class);
        llmService = mock(LlmService.class);
        eventPublisher = mock(DomainEventPublisher.class);
        chatPusher = mock(ChatPusher.class);
        orchestrator = new ChatOrchestrator(groupRepository, messageRepository, topicRepository,
                agentRepository, speakerScheduler, contextBuilder, terminator,
                messageAssembler, llmService, eventPublisher, chatPusher);

        // 默认配置：2 条自动回复，未达最大轮次
        when(terminator.getAutoReplies()).thenReturn(2);
        when(terminator.reachedMaxRounds(anyLong())).thenReturn(false);
    }

    /* ==================== 辅助构造 ==================== */

    private Group groupWithMembers(Long groupId, List<Long> memberAgentIds, Long expertId) {
        Group g = new Group();
        g.setId(groupId);
        java.util.List<GroupMember> members = new java.util.ArrayList<>();
        memberAgentIds.forEach(id -> members.add(new GroupMember(id, MemberType.AGENT, MemberRole.MEMBER)));
        if (expertId != null) {
            members.add(new GroupMember(expertId, MemberType.AGENT, MemberRole.EXPERT));
        }
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
            Group g = groupWithMembers(1L, List.of(10L), null);
            when(groupRepository.findById(1L)).thenReturn(Optional.of(g));
            when(topicRepository.findActiveByGroupId(1L)).thenReturn(Optional.empty());
            when(agentRepository.findByIds(any())).thenReturn(List.of());
            when(messageAssembler.toDto(any())).thenReturn(MessageDTO.builder().id(1L).build());

            orchestrator.onUserMessage(1L, 1L, "hello", null);

            verify(messageRepository).save(any(GroupMessage.class));
            verify(chatPusher).pushToGroup(eq(1L), eq(WsConstants.NEW_MESSAGE), any());
            verify(eventPublisher).publish(any(MessageSent.class));
        }

        @Test
        @DisplayName("有活跃 Topic 时 topicId 自动归属消息")
        void activeTopicShouldBeAttachedToMessage() {
            Group g = groupWithMembers(1L, List.of(10L), null);
            when(groupRepository.findById(1L)).thenReturn(Optional.of(g));
            Topic t = new Topic();
            t.setId(100L);
            t.setStatus(TopicStatus.IN_PROGRESS);
            when(topicRepository.findActiveByGroupId(1L)).thenReturn(Optional.of(t));
            when(agentRepository.findByIds(any())).thenReturn(List.of());
            when(messageAssembler.toDto(any())).thenReturn(MessageDTO.builder().build());

            orchestrator.onUserMessage(1L, 1L, "hello", null);

            // 校验入库的消息携带 topicId=100
            org.mockito.ArgumentCaptor<GroupMessage> captor =
                    org.mockito.ArgumentCaptor.forClass(GroupMessage.class);
            verify(messageRepository).save(captor.capture());
            assertThat(captor.getValue().getTopicId()).isEqualTo(100L);
        }

        @Test
        @DisplayName("@专家时短路触发收束，不进入调度循环")
        void mentionExpertShouldTriggerConclude() {
            Group g = groupWithMembers(1L, List.of(10L), 99L);
            when(groupRepository.findById(1L)).thenReturn(Optional.of(g));
            Topic t = new Topic();
            t.setId(100L);
            t.setStatus(TopicStatus.IN_PROGRESS);
            t.setChatGroupId(1L);
            t.setTitle("主题");
            when(topicRepository.findActiveByGroupId(1L)).thenReturn(Optional.of(t));
            when(agentRepository.findByIds(any())).thenReturn(List.of(agent(99L, "专家")));
            when(topicRepository.findById(100L)).thenReturn(Optional.of(t));
            when(topicRepository.update(any())).thenReturn(true);
            when(messageAssembler.toDto(any())).thenReturn(MessageDTO.builder().build());

            orchestrator.onUserMessage(1L, 1L, "@专家 总结一下", null);

            // 应当触发 startConcluding 状态流转，并发布 TopicConcluding 事件
            verify(topicRepository).update(any(Topic.class));
            verify(eventPublisher).publish(any(TopicConcluding.class));
            // 不应触发自由调度（speakerScheduler.rank 不应被调用）
            verify(speakerScheduler, never()).rank(any(), any());
        }

        @Test
        @DisplayName("引用 Agent 消息时 repliedToAgentId 被解析")
        void replyToAgentMessageShouldBeResolved() {
            Group g = groupWithMembers(1L, List.of(10L), null);
            when(groupRepository.findById(1L)).thenReturn(Optional.of(g));
            when(topicRepository.findActiveByGroupId(1L)).thenReturn(Optional.empty());
            GroupMessage replied = savedMessage(50L, 1L, null, 10L, SenderType.AGENT);
            when(messageRepository.findById(50L)).thenReturn(Optional.of(replied));
            when(agentRepository.findByIds(any())).thenReturn(List.of());
            when(messageAssembler.toDto(any())).thenReturn(MessageDTO.builder().build());

            orchestrator.onUserMessage(1L, 1L, "回复", 50L);

            verify(messageRepository).findById(50L);
        }

        @Test
        @DisplayName("自由调度：异步触发 SpeakerScheduler 并广播 Agent 发言")
        void shouldAsyncScheduleAgentSpeak() {
            Agent a10 = agent(10L, "老王");
            Group g = groupWithMembers(1L, List.of(10L), null);
            when(groupRepository.findById(1L)).thenReturn(Optional.of(g));
            Topic t = new Topic();
            t.setId(100L);
            t.setStatus(TopicStatus.IN_PROGRESS);
            when(topicRepository.findActiveByGroupId(1L)).thenReturn(Optional.of(t));
            when(topicRepository.findById(100L)).thenReturn(Optional.of(t));
            when(agentRepository.findByIds(List.of(10L))).thenReturn(List.of(a10));
            when(messageRepository.countByTopicIdAndSender(eq(100L), eq(10L), eq(SenderType.AGENT))).thenReturn(0L);
            when(speakerScheduler.rank(any(), any())).thenReturn(List.of(scored(a10, "FREE_SCHEDULE")));
            when(contextBuilder.build(any(), eq(1L), eq(100L), any())).thenReturn(stubContext());
            when(llmService.chat(any(), anyString(), any())).thenReturn("我的发言");
            // 入库回填
            when(messageRepository.save(any(GroupMessage.class))).thenAnswer(inv -> {
                GroupMessage m = inv.getArgument(0);
                m.setId(200L);
                return 200L;
            });
            when(messageAssembler.toDto(any())).thenReturn(MessageDTO.builder().id(200L).build());
            // 只调度 1 轮，便于精确验证
            when(terminator.getAutoReplies()).thenReturn(1);

            orchestrator.onUserMessage(1L, 1L, "讨论一下", null);

            // 异步等待：Agent 发言应当被广播
            verify(chatPusher, timeout(2000)).pushToGroup(eq(1L), eq(WsConstants.NEW_MESSAGE), any());
            verify(eventPublisher, timeout(2000)).publish(any(AgentSelected.class));
            // MessageSent 发布 2 次：用户消息 + Agent 发言
            verify(eventPublisher, timeout(2000).atLeast(2)).publish(any(MessageSent.class));
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
            // Mock 异步路径成功，避免 rollback 干扰同步验证
            Group g = groupWithMembers(10L, List.of(), 99L);
            when(groupRepository.findById(10L)).thenReturn(Optional.of(g));
            Agent expert = agent(99L, "专家");
            when(agentRepository.findById(99L)).thenReturn(Optional.of(expert));
            when(contextBuilder.buildForConclusion(any(), eq(10L), eq(1L), anyString(), any()))
                    .thenReturn(stubContext());
            when(llmService.chat(any(), anyString(), any())).thenReturn("## STAR 结论");
            when(messageRepository.countByTopicId(1L)).thenReturn(20L);

            orchestrator.conclude(1L, 1L, "USER");

            // 同步部分：发布 TopicConcluding 事件 + 推送 TOPIC_STATUS_CHANGED
            verify(eventPublisher).publish(any(TopicConcluding.class));
            verify(chatPusher).pushToGroup(eq(10L), eq(WsConstants.TOPIC_STATUS_CHANGED), any());
            // 异步完成后最终状态为 CLOSED
            verify(chatPusher, timeout(2000)).pushToGroup(eq(10L), eq(WsConstants.TOPIC_CLOSED), any());
            assertThat(t.getStatus()).isEqualTo(TopicStatus.CLOSED);
        }

        @Test
        @DisplayName("专家生成结论成功：状态 CLOSED + 广播 TOPIC_CLOSED + 发布 TopicClosed")
        void expertGenerateConclusionSuccessfully() {
            Topic t = new Topic();
            t.setId(1L);
            t.setChatGroupId(10L);
            t.setTitle("JVM 调优");
            t.setStatus(TopicStatus.IN_PROGRESS);
            when(topicRepository.findById(1L)).thenReturn(Optional.of(t));
            when(topicRepository.update(t)).thenReturn(true);

            Group g = groupWithMembers(10L, List.of(), 99L);
            when(groupRepository.findById(10L)).thenReturn(Optional.of(g));
            Agent expert = agent(99L, "专家");
            when(agentRepository.findById(99L)).thenReturn(Optional.of(expert));
            when(contextBuilder.buildForConclusion(any(), eq(10L), eq(1L), anyString(), any()))
                    .thenReturn(stubContext());
            when(llmService.chat(any(), anyString(), any())).thenReturn("## STAR 结论");
            when(messageRepository.countByTopicId(1L)).thenReturn(20L);

            orchestrator.conclude(1L, 1L, "USER");

            // 异步等待结论生成完成
            verify(chatPusher, timeout(2000)).pushToGroup(eq(10L), eq(WsConstants.TOPIC_CLOSED), any());
            verify(eventPublisher, timeout(2000)).publish(any(TopicClosed.class));
            verify(chatPusher, timeout(2000).atLeastOnce())
                    .pushToGroup(eq(10L), eq(WsConstants.AGENT_TYPING), any());
            assertThat(t.getStatus()).isEqualTo(TopicStatus.CLOSED);
            assertThat(t.getConclusion()).isEqualTo("## STAR 结论");
        }

        @Test
        @DisplayName("专家返回空结论时回退到 IN_PROGRESS 并广播 ERROR")
        void emptyConclusionShouldRollback() {
            Topic t = new Topic();
            t.setId(1L);
            t.setChatGroupId(10L);
            t.setTitle("空结论测试");
            t.setStatus(TopicStatus.IN_PROGRESS);
            when(topicRepository.findById(1L)).thenReturn(Optional.of(t));
            when(topicRepository.update(t)).thenReturn(true);

            Group g = groupWithMembers(10L, List.of(), 99L);
            when(groupRepository.findById(10L)).thenReturn(Optional.of(g));
            Agent expert = agent(99L, "专家");
            when(agentRepository.findById(99L)).thenReturn(Optional.of(expert));
            when(contextBuilder.buildForConclusion(any(), eq(10L), eq(1L), anyString(), any()))
                    .thenReturn(stubContext());
            when(llmService.chat(any(), anyString(), any())).thenReturn("");

            orchestrator.conclude(1L, 1L, "USER");

            // 异步等待回退完成
            verify(chatPusher, timeout(2000)).pushToGroup(eq(10L), eq(WsConstants.ERROR), any());
            assertThat(t.getStatus()).isEqualTo(TopicStatus.IN_PROGRESS);
        }

        @Test
        @DisplayName("群未配置专家 Agent 时回退到 IN_PROGRESS")
        void noExpertShouldRollback() {
            Topic t = new Topic();
            t.setId(1L);
            t.setChatGroupId(10L);
            t.setTitle("无专家测试");
            t.setStatus(TopicStatus.IN_PROGRESS);
            when(topicRepository.findById(1L)).thenReturn(Optional.of(t));
            when(topicRepository.update(t)).thenReturn(true);

            Group g = groupWithMembers(10L, List.of(), null); // 无专家
            when(groupRepository.findById(10L)).thenReturn(Optional.of(g));

            orchestrator.conclude(1L, 1L, "USER");

            verify(chatPusher, timeout(2000)).pushToGroup(eq(10L), eq(WsConstants.ERROR), any());
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

            Group g = groupWithMembers(10L, List.of(), 99L);
            when(groupRepository.findById(10L)).thenReturn(Optional.of(g));
            Agent expert = agent(99L, "专家");
            when(agentRepository.findById(99L)).thenReturn(Optional.of(expert));
            when(contextBuilder.buildForConclusion(any(), eq(10L), eq(1L), anyString(), any()))
                    .thenReturn(stubContext());
            // 第一次抛异常，第二次返回有效结论
            when(llmService.chat(any(), anyString(), any()))
                    .thenThrow(new RuntimeException("LLM 网络抖动"))
                    .thenReturn("## STAR 结论");
            when(messageRepository.countByTopicId(1L)).thenReturn(5L);

            orchestrator.conclude(1L, 1L, "USER");

            verify(chatPusher, timeout(2000)).pushToGroup(eq(10L), eq(WsConstants.TOPIC_CLOSED), any());
            verify(llmService, atLeastOnce()).chat(any(), anyString(), any());
            assertThat(t.getStatus()).isEqualTo(TopicStatus.CLOSED);
        }
    }

    /* ==================== 降级路由 ==================== */

    @Nested
    @DisplayName("降级路由（Agent 失败接力）")
    class FallbackRouting {

        @Test
        @DisplayName("首个 Agent 失败时降级给下一个 Agent")
        void firstAgentFailShouldFallbackToNext() {
            Agent a10 = agent(10L, "老王");
            Agent a11 = agent(11L, "小李");
            Group g = groupWithMembers(1L, List.of(10L, 11L), null);
            when(groupRepository.findById(1L)).thenReturn(Optional.of(g));
            Topic t = new Topic();
            t.setId(100L);
            t.setStatus(TopicStatus.IN_PROGRESS);
            when(topicRepository.findActiveByGroupId(1L)).thenReturn(Optional.of(t));
            when(topicRepository.findById(100L)).thenReturn(Optional.of(t));
            when(agentRepository.findByIds(List.of(10L, 11L))).thenReturn(List.of(a10, a11));
            when(messageRepository.countByTopicIdAndSender(eq(100L), anyLong(), eq(SenderType.AGENT))).thenReturn(0L);
            when(speakerScheduler.rank(any(), any())).thenReturn(List.of(
                    scored(a10, "FREE_SCHEDULE"), scored(a11, "FREE_SCHEDULE")));
            when(contextBuilder.build(any(), eq(1L), eq(100L), any())).thenReturn(stubContext());
            // a10 失败，a11 成功
            when(llmService.chat(eq(a10), anyString(), any())).thenThrow(new RuntimeException("a10 不可用"));
            when(llmService.chat(eq(a11), anyString(), any())).thenReturn("小李的发言");
            when(messageRepository.save(any(GroupMessage.class))).thenAnswer(inv -> {
                GroupMessage m = inv.getArgument(0);
                m.setId(200L);
                return 200L;
            });
            when(messageAssembler.toDto(any())).thenReturn(MessageDTO.builder().id(200L).build());
            // 只调度 1 轮，便于精确验证降级
            when(terminator.getAutoReplies()).thenReturn(1);

            orchestrator.onUserMessage(1L, 1L, "讨论", null);

            // 应当发布 AgentFailed 事件
            verify(eventPublisher, timeout(2000)).publish(any(AgentFailed.class));
            // 最终广播小李的发言
            verify(chatPusher, timeout(2000).atLeastOnce())
                    .pushToGroup(eq(1L), eq(WsConstants.NEW_MESSAGE), any());
        }

        @Test
        @DisplayName("所有 Agent 全部失败时广播 ALL_AGENTS_FAILED 错误")
        void allAgentsFailShouldBroadcastError() {
            Agent a10 = agent(10L, "老王");
            Group g = groupWithMembers(1L, List.of(10L), null);
            when(groupRepository.findById(1L)).thenReturn(Optional.of(g));
            Topic t = new Topic();
            t.setId(100L);
            t.setStatus(TopicStatus.IN_PROGRESS);
            when(topicRepository.findActiveByGroupId(1L)).thenReturn(Optional.of(t));
            when(topicRepository.findById(100L)).thenReturn(Optional.of(t));
            when(agentRepository.findByIds(List.of(10L))).thenReturn(List.of(a10));
            when(messageRepository.countByTopicIdAndSender(eq(100L), anyLong(), eq(SenderType.AGENT))).thenReturn(0L);
            when(speakerScheduler.rank(any(), any())).thenReturn(List.of(scored(a10, "FREE_SCHEDULE")));
            when(contextBuilder.build(any(), eq(1L), eq(100L), any())).thenReturn(stubContext());
            when(llmService.chat(any(), anyString(), any())).thenThrow(new RuntimeException("全部不可用"));

            orchestrator.onUserMessage(1L, 1L, "讨论", null);

            verify(chatPusher, timeout(2000)).pushToGroup(eq(1L), eq(WsConstants.ERROR), any());
            verify(eventPublisher, timeout(2000)).publish(any(AgentFailed.class));
        }

        @Test
        @DisplayName("Agent 返回空内容时视为选择不发言（不广播错误）")
        void emptyContentShouldBeTreatedAsNoSpeak() {
            Agent a10 = agent(10L, "老王");
            Group g = groupWithMembers(1L, List.of(10L), null);
            when(groupRepository.findById(1L)).thenReturn(Optional.of(g));
            Topic t = new Topic();
            t.setId(100L);
            t.setStatus(TopicStatus.IN_PROGRESS);
            when(topicRepository.findActiveByGroupId(1L)).thenReturn(Optional.of(t));
            when(topicRepository.findById(100L)).thenReturn(Optional.of(t));
            when(agentRepository.findByIds(List.of(10L))).thenReturn(List.of(a10));
            when(messageRepository.countByTopicIdAndSender(eq(100L), anyLong(), eq(SenderType.AGENT))).thenReturn(0L);
            when(speakerScheduler.rank(any(), any())).thenReturn(List.of(scored(a10, "FREE_SCHEDULE")));
            when(contextBuilder.build(any(), eq(1L), eq(100L), any())).thenReturn(stubContext());
            when(llmService.chat(any(), anyString(), any())).thenReturn("");

            orchestrator.onUserMessage(1L, 1L, "讨论", null);

            // 等待异步任务完成
            verify(eventPublisher, timeout(2000)).publish(any(AgentSelected.class));
            // 不应广播 ERROR，也不应广播 NEW_MESSAGE（Agent 选择不发言）
            verify(chatPusher, never()).pushToGroup(eq(1L), eq(WsConstants.ERROR), any());
        }
    }
}
