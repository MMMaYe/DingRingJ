package com.dingring.app.orchestrator;

import com.dingring.app.workflow.ConclusionService;
import com.dingring.common.constant.WsConstants;
import com.dingring.domain.discussion.Topic;
import com.dingring.domain.discussion.TopicRepository;
import com.dingring.domain.discussion.TopicStatus;
import com.dingring.domain.service.FlowService;
import com.dingring.domain.service.GroupBroadcastService;
import com.dingring.domain.workflow.DiscussionFlowResult;
import com.dingring.domain.workflow.DiscussionRules;
import com.dingring.domain.workflow.StateKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DiscussionEngine} 对话引擎单元测试（Phase C 重构后）。
 * <p>引擎职责简化为：信号队列 + 虚拟线程串行执行器 + DiscussionFlowService 状态图驱动。
 * 三态循环逻辑（意图分类/Agent发言/收束生成）已迁移到 SAA StateGraph 节点，
 * 本类只验证：信号投递 → advance 调用 → discussMode 分流 → 循环退出。
 * <p>DiscussionFlowService 为 mock：返回受控 DiscussionFlowResult，验证引擎驱动行为。
 */
@DisplayName("DiscussionEngine 对话引擎")
class DiscussionEngineTest {

    private static final long WAIT = 3000;

    private FlowService flowService;
    private TopicRepository topicRepository;
    private GroupBroadcastService groupBroadcastService;
    private ConclusionService conclusionService;
    private DiscussionEngine engine;

    /** 测试用规则：超短超时让阻塞场景快速返回 */
    private final DiscussionRules rules = new DiscussionRules(
            100,    // divergePaceMs（测试用短间隔）
            2,      // maxAutoRounds（自主推进兜底上限，测试用 2 便于验证让位）
            3,      // maxDivergeRounds
            100,    // maxRounds
            15,     // profileExtractThreshold
            15,     // backfillLimit
            200,    // contextWindow
            500     // concludeConfirmTimeoutMs（测试用 500ms 快速超时）
    );

    @BeforeEach
    void setUp() {
        flowService = mock(FlowService.class);
        topicRepository = mock(TopicRepository.class);
        groupBroadcastService = mock(GroupBroadcastService.class);
        conclusionService = mock(ConclusionService.class);
        engine = new DiscussionEngine(flowService, rules,
                topicRepository, groupBroadcastService, conclusionService);
    }

    /* ==================== 辅助构造 ==================== */

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

    private DiscussionFlowResult result(boolean concluded, String discussMode, Map<String, Object> state) {
        return new DiscussionFlowResult(concluded, discussMode, state);
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

    /* ==================== runConcludeFlow 收束流程触发 ==================== */

    @Nested
    @DisplayName("runConcludeFlow 收束流程")
    class RunConcludeFlow {

        @Test
        @DisplayName("委托 ConclusionService.triggerAsync 传入正确的收束参数")
        void shouldDelegateToConclusionService() {
            engine.runConcludeFlow(100L, 1L, "USER", 77L);

            verify(conclusionService).triggerAsync(100L, 1L, "USER", 77L);
        }

        @Test
        @DisplayName("concluderAgentId 为 null 时原样透传")
        void nullConcluderShouldPassThrough() {
            engine.runConcludeFlow(100L, 1L, "MAX_ROUNDS", null);

            verify(conclusionService).triggerAsync(100L, 1L, "MAX_ROUNDS", null);
        }
    }

    /* ==================== 主循环 runLoop ==================== */

    @Nested
    @DisplayName("runLoop 主循环")
    class RunLoop {

        @Test
        @DisplayName("闲聊态（无活跃话题）：wake 后 advance 不被调用，循环立即退出")
        void noActiveTopicShouldExitWithoutAdvance() {
            when(topicRepository.findActiveByGroupId(1L)).thenReturn(Optional.empty());

            engine.wake(1L);

            // 等待循环启动（findActive 被调用说明循环已执行），再确认 advance 未被调用
            verify(topicRepository, timeout(WAIT)).findActiveByGroupId(1L);
            verify(flowService, never()).advance(any(), any());
        }

        @Test
        @DisplayName("有活跃话题 + 用户信号 + advance 返回 concluded：advance 调用一次后循环退出")
        void signalWithConcludedResultShouldExitAfterOneAdvance() {
            Topic topic = activeTopic(100L, 1L);
            when(topicRepository.findActiveByGroupId(1L)).thenReturn(Optional.of(topic));
            when(flowService.advance(any(), any()))
                    .thenReturn(result(true, StateKeys.MODE_CONCLUDE, null));

            engine.onUserSignal(1L, signal("结束讨论"));

            // advance 被调用一次，inputs 包含用户消息
            ArgumentCaptor<Map<String, Object>> inputsCaptor = ArgumentCaptor.forClass(Map.class);
            verify(flowService, timeout(WAIT)).advance(eq(rules), inputsCaptor.capture());
            assertThat(inputsCaptor.getValue().get(StateKeys.INPUT)).isEqualTo("结束讨论");
            assertThat(inputsCaptor.getValue().get(StateKeys.GROUP_ID)).isEqualTo(1L);
        }

        @Test
        @DisplayName("有活跃话题 + DIVERGE 模式 + pace 超时：advanceAuto 被调用推进一轮")
        void divergePaceTimeoutShouldAutoAdvance() {
            Topic topic = activeTopic(100L, 1L);
            // 第一次：用户信号返回 DIVERGE（建立发散模式）
            // 第二次：无信号 pace 超时 → advanceAuto 返回 concluded 退出
            when(topicRepository.findActiveByGroupId(1L)).thenReturn(Optional.of(topic));
            when(flowService.advance(any(), any()))
                    .thenReturn(result(false, StateKeys.MODE_DIVERGE, null))
                    .thenReturn(result(true, StateKeys.MODE_CONCLUDE, null));

            engine.onUserSignal(1L, signal("发散讨论"));

            // advance 被调用两次：第一次用户信号驱动，第二次 DIVERGE 自主推进
            verify(flowService, timeout(WAIT).times(2)).advance(eq(rules), any());
        }

        @Test
        @DisplayName("CONCLUDE_PROPOSED 模式：广播提议 → 超时自动收束 → 委托 ConclusionService")
        void concludeProposedTimeoutShouldAutoConclude() {
            Topic topic = activeTopic(100L, 1L);
            when(topicRepository.findActiveByGroupId(1L)).thenReturn(Optional.of(topic));

            // 第一次 advance（用户信号）：返回 CONCLUDE_PROPOSED，携带 topicId 和 concluderAgentId
            Map<String, Object> proposedState = new HashMap<>();
            proposedState.put(StateKeys.TOPIC_ID, 100L);
            proposedState.put(StateKeys.TOPIC_TITLE, "收束中主题");
            proposedState.put(StateKeys.CONCLUDER_AGENT_ID, 77L);
            when(flowService.advance(any(), any()))
                    .thenReturn(result(false, StateKeys.MODE_CONCLUDE_PROPOSED, proposedState));

            engine.onUserSignal(1L, signal("可以总结了吗"));

            // 第一次 advance：用户信号驱动
            verify(flowService, timeout(WAIT)).advance(eq(rules), any());
            // 广播提议收束状态
            verify(groupBroadcastService, timeout(WAIT))
                    .broadcast(eq(1L), eq(WsConstants.TOPIC_STATUS_CHANGED), any());
            // 超时后 runConcludeFlow 委托 ConclusionService 触发收束（TIMEOUT）
            verify(conclusionService, timeout(WAIT + rules.concludeConfirmTimeoutMs()))
                    .triggerAsync(eq(100L), eq(1L), eq("TIMEOUT"), eq(77L));
        }

        @Test
        @DisplayName("有活跃话题 + CONVERGE 模式 + pace 超时：advanceAuto 被调用推进一轮")
        void convergePaceTimeoutShouldAutoAdvance() {
            Topic topic = activeTopic(100L, 1L);
            // 第一次：用户信号返回 CONVERGE（真实发言）
            // 第二次：无信号 pace 超时 → advanceAuto 返回 concluded 退出
            when(topicRepository.findActiveByGroupId(1L)).thenReturn(Optional.of(topic));
            when(flowService.advance(any(), any()))
                    .thenReturn(result(false, StateKeys.MODE_CONVERGE, null))
                    .thenReturn(result(true, StateKeys.MODE_CONCLUDE, null));

            engine.onUserSignal(1L, signal("讨论一下"));

            // advance 被调用两次：第一次用户信号驱动，第二次 CONVERGE 自主推进
            verify(flowService, timeout(WAIT).times(2)).advance(eq(rules), any());
        }

        @Test
        @DisplayName("CONVERGE 自主推进达 maxAutoRounds 上限后让位，不再 advance")
        void convergeReachesMaxAutoRoundsShouldYield() {
            Topic topic = activeTopic(100L, 1L);
            when(topicRepository.findActiveByGroupId(1L)).thenReturn(Optional.of(topic));
            // 所有 advance 都返回 CONVERGE（无人 [[ASK_USER]] 让位），靠 maxAutoRounds 兜底
            when(flowService.advance(any(), any()))
                    .thenReturn(result(false, StateKeys.MODE_CONVERGE, null));

            engine.onUserSignal(1L, signal("讨论一下"));

            // 1 次信号驱动 + maxAutoRounds(2) 次自主推进 = 3 次，之后强制让位不再推进
            verify(flowService, timeout(WAIT).times(3)).advance(eq(rules), any());
        }

        @Test
        @DisplayName("advance 返回 WAIT：阻塞后用户回答被 advanceFlow 消费（不吞消息）")
        void waitModeShouldConsumeUserAnswer() {
            Topic topic = activeTopic(100L, 1L);
            when(topicRepository.findActiveByGroupId(1L)).thenReturn(Optional.of(topic));
            // 第一次：用户消息返回 WAIT（Agent [[ASK_USER]] 让位）
            // 第二次：用户回答返回 CONCLUDE（结束，避免后续自主推进干扰计数）
            when(flowService.advance(any(), any()))
                    .thenReturn(result(false, StateKeys.MODE_WAIT, null))
                    .thenReturn(result(true, StateKeys.MODE_CONCLUDE, null));

            engine.onUserSignal(1L, signal("你更倾向哪个？"));
            // 等第一个 advance（返回 WAIT）处理完再发回答，避免背压信号被 queue.clear() 清掉
            verify(flowService, timeout(WAIT)).advance(eq(rules), any());
            engine.onUserSignal(1L, signal("我的回答"));

            // 第二次 advance 的 INPUT 应为用户回答（证明没被旧的 queue.take() 吞掉）
            ArgumentCaptor<Map<String, Object>> inputsCaptor = ArgumentCaptor.forClass(Map.class);
            verify(flowService, timeout(WAIT).times(2)).advance(eq(rules), inputsCaptor.capture());
            assertThat(inputsCaptor.getAllValues().get(1).get(StateKeys.INPUT)).isEqualTo("我的回答");
        }

        @Test
        @DisplayName("auto-advance 返回 CONCLUDE_PROPOSED：走确认流程（广播 + 超时兜底）")
        void autoAdvanceConcludeProposedShouldHandleConfirm() {
            Topic topic = activeTopic(100L, 1L);
            when(topicRepository.findActiveByGroupId(1L)).thenReturn(Optional.of(topic));
            // 第一次：用户信号返回 CONVERGE；第二次：pace 超时 auto-advance 返回 CONCLUDE_PROPOSED
            Map<String, Object> proposedState = new HashMap<>();
            proposedState.put(StateKeys.TOPIC_ID, 100L);
            proposedState.put(StateKeys.TOPIC_TITLE, "收束中主题");
            proposedState.put(StateKeys.CONCLUDER_AGENT_ID, 77L);
            when(flowService.advance(any(), any()))
                    .thenReturn(result(false, StateKeys.MODE_CONVERGE, null))
                    .thenReturn(result(false, StateKeys.MODE_CONCLUDE_PROPOSED, proposedState));

            engine.onUserSignal(1L, signal("讨论一下"));

            // auto-advance 产出 CONCLUDE_PROPOSED → 必须广播提议 + 等确认超时后委托 ConclusionService
            verify(flowService, timeout(WAIT).times(2)).advance(eq(rules), any());
            verify(groupBroadcastService, timeout(WAIT))
                    .broadcast(eq(1L), eq(WsConstants.TOPIC_STATUS_CHANGED), any());
            verify(conclusionService, timeout(WAIT + rules.concludeConfirmTimeoutMs()))
                    .triggerAsync(eq(100L), eq(1L), eq("TIMEOUT"), eq(77L));
        }
    }
}
