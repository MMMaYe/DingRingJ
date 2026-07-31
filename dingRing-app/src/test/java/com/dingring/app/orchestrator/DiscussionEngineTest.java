package com.dingring.app.orchestrator;

import com.dingring.app.service.ChatPusher;
import com.dingring.app.service.MessageAssembler;
import com.dingring.common.constant.WsConstants;
import com.dingring.common.exception.BizException;
import com.dingring.common.exception.ErrorCode;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.agent.AgentRepository;
import com.dingring.domain.discussion.Topic;
import com.dingring.domain.discussion.TopicRepository;
import com.dingring.domain.discussion.TopicStatus;
import com.dingring.domain.event.TopicCreated;
import com.dingring.domain.group.Group;
import com.dingring.domain.group.GroupMember;
import com.dingring.domain.group.GroupMessage;
import com.dingring.domain.group.GroupRepository;
import com.dingring.domain.group.MemberRole;
import com.dingring.domain.group.MemberType;
import com.dingring.domain.group.MessageRepository;
import com.dingring.domain.group.SenderType;
import com.dingring.domain.service.DomainEventPublisher;
import com.dingring.domain.service.LlmService;
import com.dingring.domain.service.ProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DiscussionEngine} 对话引擎单元测试。
 * <p>引擎循环跑在群串行虚拟线程上，全部用 {@code verify(mock, timeout(..))} 异步验证；
 * pace 反射注 0 让讨论态推进无等待。MessageRouter 直接 mock，不经过 LLM JSON。
 */
@DisplayName("DiscussionEngine 对话引擎")
class DiscussionEngineTest {

    private static final long WAIT = 3000;

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
    private MessageRouter messageRouter;
    private ProfileService profileService;
    private ModeratorService moderatorService;
    private ChatOrchestrator chatOrchestrator;
    private DiscussionEngine engine;

    @BeforeEach
    void setUp() throws Exception {
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
        messageRouter = mock(MessageRouter.class);
        profileService = mock(ProfileService.class);
        moderatorService = mock(ModeratorService.class);
        chatOrchestrator = mock(ChatOrchestrator.class);
        engine = new DiscussionEngine(groupRepository, messageRepository, topicRepository,
                agentRepository, speakerScheduler, contextBuilder, terminator, messageAssembler,
                llmService, eventPublisher, chatPusher, messageRouter, profileService,
                moderatorService, chatOrchestrator);
        // 测试无等待：pace 0；两个不同 Agent PASS 即收敛
        setField("paceMinMs", 0L);
        setField("paceMaxMs", 0L);
        setField("convergePassCount", 2);
        setField("profileExtractThreshold", 15);
        setField("backfillLimit", 15);
    }

    private void setField(String name, Object value) throws Exception {
        Field f = DiscussionEngine.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(engine, value);
    }

    /* ==================== 辅助构造 ==================== */

    private Group group(Long groupId, List<Long> agentIds) {
        Group g = new Group();
        g.setId(groupId);
        g.setName("测试群");
        java.util.List<GroupMember> members = new java.util.ArrayList<>();
        agentIds.forEach(id -> members.add(new GroupMember(id, MemberType.AGENT, MemberRole.MEMBER)));
        g.setGroupMember(members);
        return g;
    }

    private Agent agent(Long id, String name) {
        Agent a = new Agent();
        a.setId(id);
        a.setName(name);
        return a;
    }

    private Topic activeTopic(Long topicId, Long groupId) {
        Topic t = new Topic();
        t.setId(topicId);
        t.setChatGroupId(groupId);
        t.setTitle("进行中的主题");
        t.setStatus(TopicStatus.IN_PROGRESS);
        return t;
    }

    private DiscussionEngine.UserSignal signal(String content) {
        return new DiscussionEngine.UserSignal(1L, content, List.of(), null);
    }

    private void stubRoute(MessageRouter.Intent intent, String title, MessageRouter.Confidence confidence) {
        when(messageRouter.route(any(Agent.class), any(), any()))
                .thenReturn(new MessageRouter.Route(intent, title, confidence));
    }

    /** 群 1L 两个 Agent（10 老王 / 11 小李），rank 按候选原序打分 */
    private void stubGroupWithTwoAgents() {
        Agent laoWang = agent(10L, "老王");
        Agent xiaoLi = agent(11L, "小李");
        when(groupRepository.findById(1L)).thenReturn(Optional.of(group(1L, List.of(10L, 11L))));
        when(agentRepository.findByIds(any())).thenAnswer(inv -> {
            List<Long> ids = inv.getArgument(0);
            return List.of(laoWang, xiaoLi).stream().filter(a -> ids.contains(a.getId())).toList();
        });
        when(speakerScheduler.rank(anyList(), any())).thenAnswer(inv -> {
            List<Agent> candidates = inv.getArgument(0);
            return candidates.stream()
                    .map(a -> new SpeakerScheduler.ScoredAgent(a, 100, "测试"))
                    .toList();
        });
        when(contextBuilder.build(any(), anyList(), anyLong(), any(), any()))
                .thenReturn(new ContextBuilder.LlmContext("sp", List.of(LlmService.ChatTurn.user("hi"))));
    }

    /* ==================== 闲聊态 ==================== */

    @Nested
    @DisplayName("闲聊态")
    class ChatMode {

        @Test
        @DisplayName("CHAT 意图无活跃主题：Agent 轻量应答一次并入库广播")
        void chatSignalShouldGetOneReply() {
            stubGroupWithTwoAgents();
            when(topicRepository.findActiveByGroupId(1L)).thenReturn(Optional.empty());
            stubRoute(MessageRouter.Intent.CHAT, "", MessageRouter.Confidence.LOW);
            when(terminator.getAutoReplies()).thenReturn(1);
            when(llmService.chat(any(), any(), anyList())).thenReturn("你好呀");

            engine.onUserSignal(1L, signal("哈喽"));

            ArgumentCaptor<GroupMessage> captor = ArgumentCaptor.forClass(GroupMessage.class);
            verify(messageRepository, timeout(WAIT)).save(captor.capture());
            assertThat(captor.getValue().getSenderType()).isEqualTo(SenderType.AGENT);
            assertThat(captor.getValue().getContent()).isEqualTo("你好呀");
            assertThat(captor.getValue().getTopicId()).isNull();
            verify(chatPusher, timeout(WAIT)).pushToGroup(eq(1L), eq(WsConstants.NEW_MESSAGE), any());
        }

        @Test
        @DisplayName("闲聊积累达阈值触发画像提炼")
        void chatBufferReachingThresholdShouldTriggerProfileExtraction() throws Exception {
            setField("profileExtractThreshold", 2);
            stubGroupWithTwoAgents();
            when(topicRepository.findActiveByGroupId(1L)).thenReturn(Optional.empty());
            stubRoute(MessageRouter.Intent.CHAT, "", MessageRouter.Confidence.LOW);
            when(terminator.getAutoReplies()).thenReturn(0);
            GroupMessage m = new GroupMessage();
            m.setContent("哈喽");
            when(messageRepository.findRecentChatByGroupId(eq(1L), anyInt())).thenReturn(List.of(m));
            when(messageAssembler.resolveSenderName(any())).thenReturn("用户");

            engine.onUserSignal(1L, signal("第一条"));
            engine.onUserSignal(1L, signal("第二条"));

            verify(profileService, timeout(WAIT)).extractAndMerge(anyLong(), any(Agent.class),
                    eq("测试群"), any(String.class));
        }
    }

    /* ==================== 追溯式建题 ==================== */

    @Nested
    @DisplayName("追溯式建题")
    class EnsureTopic {

        @Test
        @DisplayName("HIGH 置信度 DISCUSS 立即建题：回填闲聊 + 发布 TopicCreated + 推送")
        void highConfidenceDiscussShouldCreateTopicImmediately() {
            stubGroupWithTwoAgents();
            when(topicRepository.findActiveByGroupId(1L)).thenReturn(Optional.empty());
            stubRoute(MessageRouter.Intent.DISCUSS, "周末去哪玩", MessageRouter.Confidence.HIGH);
            doAnswer(inv -> {
                Topic t = inv.getArgument(0);
                t.setId(500L);
                return 500L;
            }).when(topicRepository).save(any(Topic.class));
            when(topicRepository.findClosedByGroupId(1L)).thenReturn(List.of());
            GroupMessage chat = new GroupMessage();
            chat.setId(80L);
            when(messageRepository.findRecentChatByGroupId(eq(1L), anyInt())).thenReturn(List.of(chat));
            when(messageRepository.updateTopicId(anyList(), eq(500L))).thenReturn(1);

            engine.onUserSignal(1L, signal("周末去哪玩好呢"));

            ArgumentCaptor<Topic> captor = ArgumentCaptor.forClass(Topic.class);
            verify(topicRepository, timeout(WAIT)).save(captor.capture());
            assertThat(captor.getValue().getTitle()).isEqualTo("周末去哪玩");
            assertThat(captor.getValue().getStatus()).isEqualTo(TopicStatus.IN_PROGRESS);
            verify(messageRepository, timeout(WAIT)).updateTopicId(eq(List.of(80L)), eq(500L));
            verify(eventPublisher, timeout(WAIT)).publish(any(TopicCreated.class));
            verify(chatPusher, timeout(WAIT)).pushToGroup(eq(1L), eq(WsConstants.TOPIC_CREATED), any());
        }

        @Test
        @DisplayName("LOW 置信度 DISCUSS 首条不建题，连续第 2 条才建题")
        void lowConfidenceDiscussShouldCreateOnSecondStreak() throws Exception {
            stubGroupWithTwoAgents();
            when(topicRepository.findActiveByGroupId(1L)).thenReturn(Optional.empty());
            stubRoute(MessageRouter.Intent.DISCUSS, "模糊话题", MessageRouter.Confidence.LOW);
            when(terminator.getAutoReplies()).thenReturn(0);
            doAnswer(inv -> {
                Topic t = inv.getArgument(0);
                t.setId(501L);
                return 501L;
            }).when(topicRepository).save(any(Topic.class));
            when(topicRepository.findClosedByGroupId(1L)).thenReturn(List.of());
            when(messageRepository.findRecentChatByGroupId(eq(1L), anyInt())).thenReturn(List.of());

            engine.onUserSignal(1L, signal("第一条模糊讨论"));
            // 首条不建题（用 route 调用次数确认第一条已处理完）
            verify(messageRouter, timeout(WAIT)).route(any(), eq("第一条模糊讨论"), any());
            verify(topicRepository, never()).save(any(Topic.class));

            engine.onUserSignal(1L, signal("第二条模糊讨论"));

            verify(topicRepository, timeout(WAIT)).save(any(Topic.class));
        }

        @Test
        @DisplayName("建题标题撞历史唯一键：加时间后缀重试")
        void duplicateTitleShouldRetryWithSuffix() {
            stubGroupWithTwoAgents();
            when(topicRepository.findActiveByGroupId(1L)).thenReturn(Optional.empty());
            stubRoute(MessageRouter.Intent.DISCUSS, "老话题", MessageRouter.Confidence.HIGH);
            when(topicRepository.findClosedByGroupId(1L)).thenReturn(List.of());
            when(messageRepository.findRecentChatByGroupId(eq(1L), anyInt())).thenReturn(List.of());
            // 第一次撞唯一键，重试成功
            doThrow(new DuplicateKeyException("uk_group_title"))
                    .doAnswer(inv -> {
                        Topic t = inv.getArgument(0);
                        t.setId(502L);
                        return 502L;
                    })
                    .when(topicRepository).save(any(Topic.class));

            engine.onUserSignal(1L, signal("再聊聊老话题"));

            ArgumentCaptor<Topic> captor = ArgumentCaptor.forClass(Topic.class);
            verify(topicRepository, timeout(WAIT).times(2)).save(captor.capture());
            assertThat(captor.getValue().getTitle()).startsWith("老话题·");
            verify(eventPublisher, timeout(WAIT)).publish(any(TopicCreated.class));
        }
    }

    /* ==================== 收束触发 ==================== */

    @Nested
    @DisplayName("收束触发")
    class ConcludeTrigger {

        @Test
        @DisplayName("CONCLUDE 意图有活跃主题：@提及者作为指定总结人")
        void concludeIntentShouldTriggerWithMentionedConcluder() {
            stubGroupWithTwoAgents();
            Topic topic = activeTopic(100L, 1L);
            when(topicRepository.findActiveByGroupId(1L)).thenReturn(Optional.of(topic));
            stubRoute(MessageRouter.Intent.CONCLUDE, "", MessageRouter.Confidence.HIGH);

            engine.onUserSignal(1L, new DiscussionEngine.UserSignal(7L, "@小李 总结一下", List.of(11L), null));

            verify(chatOrchestrator, timeout(WAIT)).conclude(eq(100L), eq(7L), eq("USER"), eq(11L));
        }

        @Test
        @DisplayName("收束触发失败（BizException）时循环不崩溃，闲聊信号仍可处理")
        void concludeFailureShouldNotCrashLoop() {
            stubGroupWithTwoAgents();
            Topic topic = activeTopic(100L, 1L);
            // 主循环与 handleSignal 各查一次；收束失败后主题被别处关闭 → 闲聊态退出
            when(topicRepository.findActiveByGroupId(1L))
                    .thenReturn(Optional.of(topic))
                    .thenReturn(Optional.of(topic))
                    .thenReturn(Optional.empty());
            stubRoute(MessageRouter.Intent.CONCLUDE, "", MessageRouter.Confidence.HIGH);
            doThrow(new BizException(ErrorCode.TOPIC_NOT_IN_PROGRESS, "状态已变更"))
                    .when(chatOrchestrator).conclude(anyLong(), any(), any(), any());

            engine.onUserSignal(1L, signal("总结一下"));

            verify(chatOrchestrator, timeout(WAIT)).conclude(eq(100L), eq(1L), eq("USER"), isNull());
            // 循环未崩溃：路由已完成一次即说明 handleSignal 正常返回
            verify(messageRouter, timeout(WAIT)).route(any(), eq("总结一下"), any());
        }
    }

    /* ==================== 讨论态自主推进 ==================== */

    @Nested
    @DisplayName("讨论态自主推进")
    class Discussion {

        @Test
        @DisplayName("达到最大轮次自动收束（MAX_ROUNDS）")
        void maxRoundsShouldAutoConclude() {
            stubGroupWithTwoAgents();
            Topic topic = activeTopic(100L, 1L);
            when(topicRepository.findActiveByGroupId(1L)).thenReturn(Optional.of(topic));
            when(terminator.reachedMaxRounds(100L)).thenReturn(true);

            engine.wake(1L);

            verify(chatOrchestrator, timeout(WAIT)).conclude(eq(100L), isNull(), eq("MAX_ROUNDS"), isNull());
        }

        @Test
        @DisplayName("两个不同 Agent 连续 PASS 触发收敛收束（CONVERGED）")
        void twoDistinctPassesShouldConverge() {
            stubGroupWithTwoAgents();
            Topic topic = activeTopic(100L, 1L);
            when(topicRepository.findActiveByGroupId(1L)).thenReturn(Optional.of(topic));
            when(terminator.reachedMaxRounds(100L)).thenReturn(false);
            when(messageRepository.countByTopicIdAndSender(anyLong(), anyLong(), any())).thenReturn(0L);
            when(llmService.chat(any(), any(), anyList())).thenReturn(ContextBuilder.PASS_MARKER);

            engine.wake(1L);

            verify(chatOrchestrator, timeout(WAIT)).conclude(eq(100L), isNull(), eq("CONVERGED"), isNull());
            // PASS 不入库不广播
            verify(messageRepository, never()).save(any(GroupMessage.class));
        }

        @Test
        @DisplayName("Agent 回复携带 [[CONCLUDE]] 标记：入库剥离标记并触发 AGENT 收束")
        void concludeMarkerShouldStripAndTriggerAgentConclude() {
            stubGroupWithTwoAgents();
            Topic topic = activeTopic(100L, 1L);
            when(topicRepository.findActiveByGroupId(1L)).thenReturn(Optional.of(topic));
            when(terminator.reachedMaxRounds(100L)).thenReturn(false);
            when(messageRepository.countByTopicIdAndSender(anyLong(), anyLong(), any())).thenReturn(0L);
            when(llmService.chat(any(), any(), anyList()))
                    .thenReturn("我觉得可以定了 " + ContextBuilder.CONCLUDE_MARKER);

            engine.wake(1L);

            ArgumentCaptor<GroupMessage> captor = ArgumentCaptor.forClass(GroupMessage.class);
            verify(messageRepository, timeout(WAIT)).save(captor.capture());
            assertThat(captor.getValue().getContent()).isEqualTo("我觉得可以定了");
            verify(chatOrchestrator, timeout(WAIT)).conclude(eq(100L), isNull(), eq("AGENT"), eq(10L));
        }
    }

    /* ==================== Moderator 主持（Phase 2） ==================== */

    @Nested
    @DisplayName("Moderator 主持")
    class Moderator {

        private Topic stubDiscussionOneRound() {
            stubGroupWithTwoAgents();
            Topic topic = activeTopic(100L, 1L);
            // 推进一轮后主题失活，循环自然退出
            when(topicRepository.findActiveByGroupId(1L))
                    .thenReturn(Optional.of(topic))
                    .thenReturn(Optional.empty());
            when(terminator.reachedMaxRounds(100L)).thenReturn(false);
            when(messageRepository.countByTopicIdAndSender(anyLong(), anyLong(), any())).thenReturn(0L);
            return topic;
        }

        @Test
        @DisplayName("主持人判断可收束：触发 MODERATOR 收束")
        void moderatorConcludeShouldTriggerConclusion() {
            stubGroupWithTwoAgents();
            Topic topic = activeTopic(100L, 1L);
            when(topicRepository.findActiveByGroupId(1L)).thenReturn(Optional.of(topic));
            when(terminator.reachedMaxRounds(100L)).thenReturn(false);
            when(messageRepository.countByTopicIdAndSender(anyLong(), anyLong(), any())).thenReturn(0L);
            when(moderatorService.isEnabled()).thenReturn(true);
            when(moderatorService.decide(any(Topic.class), anyList(), any()))
                    .thenReturn(new ModeratorService.Decision(true, null, true, ""));

            engine.wake(1L);

            verify(chatOrchestrator, timeout(WAIT)).conclude(eq(100L), isNull(), eq("MODERATOR"), isNull());
        }

        @Test
        @DisplayName("主持人指定发言者：提到降级链首位且引导语注入 system prompt")
        void moderatorPickShouldPromoteSpeakerAndInjectGuidance() {
            stubDiscussionOneRound();
            when(moderatorService.isEnabled()).thenReturn(true);
            when(moderatorService.decide(any(Topic.class), anyList(), any()))
                    .thenReturn(new ModeratorService.Decision(true, 11L, false, "请从成本角度补充"));
            when(llmService.chat(any(), any(), anyList())).thenReturn("成本角度的看法");

            engine.wake(1L);

            ArgumentCaptor<Agent> agentCaptor = ArgumentCaptor.forClass(Agent.class);
            ArgumentCaptor<String> spCaptor = ArgumentCaptor.forClass(String.class);
            verify(llmService, timeout(WAIT)).chat(agentCaptor.capture(), spCaptor.capture(), anyList());
            assertThat(agentCaptor.getValue().getId()).isEqualTo(11L);
            assertThat(spCaptor.getValue()).contains("主持人提示：请从成本角度补充");
        }

        @Test
        @DisplayName("主持人判定失败（返回 null）：回退评分调度照常推进")
        void moderatorFailureShouldFallbackToScheduler() {
            stubDiscussionOneRound();
            when(moderatorService.isEnabled()).thenReturn(true);
            when(moderatorService.decide(any(Topic.class), anyList(), any())).thenReturn(null);
            when(llmService.chat(any(), any(), anyList())).thenReturn("回退后的发言");

            engine.wake(1L);

            // 评分调度原序：老王（10L）先说，且无主持人提示
            ArgumentCaptor<Agent> agentCaptor = ArgumentCaptor.forClass(Agent.class);
            ArgumentCaptor<String> spCaptor = ArgumentCaptor.forClass(String.class);
            verify(llmService, timeout(WAIT)).chat(agentCaptor.capture(), spCaptor.capture(), anyList());
            assertThat(agentCaptor.getValue().getId()).isEqualTo(10L);
            assertThat(spCaptor.getValue()).doesNotContain("主持人提示");
        }

        @Test
        @DisplayName("用户 @提及短路主持人（指名优先级最高）")
        void mentionShouldShortCircuitModerator() {
            stubGroupWithTwoAgents();
            Topic topic = activeTopic(100L, 1L);
            // 判活序列：循环判活 → handleSignal 判活 → 第二轮循环判活（进 advanceDiscussion）→ 第三轮退出
            when(topicRepository.findActiveByGroupId(1L))
                    .thenReturn(Optional.of(topic))
                    .thenReturn(Optional.of(topic))
                    .thenReturn(Optional.of(topic))
                    .thenReturn(Optional.empty());
            when(terminator.reachedMaxRounds(100L)).thenReturn(false);
            when(messageRepository.countByTopicIdAndSender(anyLong(), anyLong(), any())).thenReturn(0L);
            when(moderatorService.isEnabled()).thenReturn(true);
            stubRoute(MessageRouter.Intent.DISCUSS, "", MessageRouter.Confidence.HIGH);
            when(llmService.chat(any(), any(), anyList())).thenReturn("被@后的应答");

            engine.onUserSignal(1L, new DiscussionEngine.UserSignal(1L, "@小李 你怎么看", List.of(11L), null));

            verify(llmService, timeout(WAIT)).chat(any(Agent.class), any(), anyList());
            verify(moderatorService, never()).decide(any(), anyList(), any());
        }
    }

    /* ==================== 流式输出（Phase 3） ==================== */

    @Nested
    @DisplayName("流式输出")
    class Streaming {

        /** stub chatStream：逐块回调 deltas 后返回完整内容；同时 stub toDto（COMPLETE 载荷 Map.of 不接受 null） */
        private void stubChatStream(String full, String... deltas) {
            when(messageAssembler.toDto(any(GroupMessage.class)))
                    .thenReturn(mock(com.dingring.app.dto.response.MessageDTO.class));
            when(llmService.chatStream(any(), any(), anyList(), any())).thenAnswer(inv -> {
                Consumer<String> onDelta = inv.getArgument(3);
                for (String d : deltas) {
                    onDelta.accept(d);
                }
                return full;
            });
        }

        @Test
        @DisplayName("开启流式：逐块推 DELTA，完成推 COMPLETE 替代 NEW_MESSAGE")
        void streamingShouldPushDeltaAndCompleteInsteadOfNewMessage() throws Exception {
            setField("streamingEnabled", true);
            stubGroupWithTwoAgents();
            when(topicRepository.findActiveByGroupId(1L)).thenReturn(Optional.empty());
            stubRoute(MessageRouter.Intent.CHAT, "", MessageRouter.Confidence.LOW);
            when(terminator.getAutoReplies()).thenReturn(1);
            stubChatStream("你好呀", "你好", "呀");

            engine.onUserSignal(1L, signal("哈喽"));

            verify(chatPusher, timeout(WAIT).times(2)).pushToGroup(eq(1L), eq(WsConstants.MESSAGE_DELTA), any());
            verify(chatPusher, timeout(WAIT)).pushToGroup(eq(1L), eq(WsConstants.MESSAGE_COMPLETE), any());
            ArgumentCaptor<GroupMessage> captor = ArgumentCaptor.forClass(GroupMessage.class);
            verify(messageRepository, timeout(WAIT)).save(captor.capture());
            assertThat(captor.getValue().getContent()).isEqualTo("你好呀");
            verify(chatPusher, never()).pushToGroup(eq(1L), eq(WsConstants.NEW_MESSAGE), any());
        }

        @Test
        @DisplayName("流式中途 PASS：已发 delta 时推 ABORT 且不入库")
        void streamingPassShouldAbortAfterDeltas() throws Exception {
            setField("streamingEnabled", true);
            stubGroupWithTwoAgents();
            Topic topic = activeTopic(100L, 1L);
            when(topicRepository.findActiveByGroupId(1L)).thenReturn(Optional.of(topic));
            when(terminator.reachedMaxRounds(100L)).thenReturn(false);
            when(messageRepository.countByTopicIdAndSender(anyLong(), anyLong(), any())).thenReturn(0L);
            stubChatStream("想了想" + ContextBuilder.PASS_MARKER, "想了想", ContextBuilder.PASS_MARKER);

            engine.wake(1L);

            verify(chatPusher, timeout(WAIT).atLeastOnce()).pushToGroup(eq(1L), eq(WsConstants.MESSAGE_ABORT), any());
            verify(messageRepository, never()).save(any(GroupMessage.class));
            verify(chatPusher, never()).pushToGroup(eq(1L), eq(WsConstants.MESSAGE_COMPLETE), any());
        }

        @Test
        @DisplayName("流式实现回退整段返回（无 delta）：仍推 NEW_MESSAGE")
        void streamingWithoutDeltasShouldFallbackToNewMessage() throws Exception {
            setField("streamingEnabled", true);
            stubGroupWithTwoAgents();
            when(topicRepository.findActiveByGroupId(1L)).thenReturn(Optional.empty());
            stubRoute(MessageRouter.Intent.CHAT, "", MessageRouter.Confidence.LOW);
            when(terminator.getAutoReplies()).thenReturn(1);
            stubChatStream("整段直接返回");

            engine.onUserSignal(1L, signal("哈喽"));

            verify(chatPusher, timeout(WAIT)).pushToGroup(eq(1L), eq(WsConstants.NEW_MESSAGE), any());
            verify(chatPusher, never()).pushToGroup(eq(1L), eq(WsConstants.MESSAGE_COMPLETE), any());
            verify(chatPusher, never()).pushToGroup(eq(1L), eq(WsConstants.MESSAGE_DELTA), any());
        }

        @Test
        @DisplayName("关闭流式（默认）：走非流式 chat，行为不变")
        void streamingDisabledShouldUseBlockingChat() {
            stubGroupWithTwoAgents();
            when(topicRepository.findActiveByGroupId(1L)).thenReturn(Optional.empty());
            stubRoute(MessageRouter.Intent.CHAT, "", MessageRouter.Confidence.LOW);
            when(terminator.getAutoReplies()).thenReturn(1);
            when(llmService.chat(any(), any(), anyList())).thenReturn("非流式回复");

            engine.onUserSignal(1L, signal("哈喽"));

            verify(chatPusher, timeout(WAIT)).pushToGroup(eq(1L), eq(WsConstants.NEW_MESSAGE), any());
            verify(llmService, never()).chatStream(any(), any(), anyList(), any());
        }
    }

    /* ==================== execute 串行执行器 ==================== */

    @Nested
    @DisplayName("execute 串行任务")
    class Execute {

        @Test
        @DisplayName("任务在群串行执行器上执行")
        void taskShouldRunOnGroupExecutor() throws Exception {
            CountDownLatch latch = new CountDownLatch(1);

            engine.execute(1L, "测试任务", latch::countDown);

            assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        }

        @Test
        @DisplayName("任务抛异常不影响后续任务执行")
        void taskExceptionShouldNotBreakExecutor() throws Exception {
            CountDownLatch latch = new CountDownLatch(1);

            engine.execute(1L, "会失败的任务", () -> {
                throw new RuntimeException("boom");
            });
            engine.execute(1L, "后续任务", latch::countDown);

            assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        }
    }
}
