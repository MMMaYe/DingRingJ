package com.dingring.app.orchestrator;

import com.dingring.common.constant.WsConstants;
import com.dingring.domain.discussion.Topic;
import com.dingring.domain.discussion.TopicRepository;
import com.dingring.domain.discussion.TopicStatus;
import com.dingring.domain.service.DiscussionFlowService;
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

    private DiscussionFlowService discussionFlowService;
    private TopicRepository topicRepository;
    private GroupBroadcastService groupBroadcastService;
    private DiscussionEngine engine;

    /** 测试用规则：超短超时让阻塞场景快速返回 */
    private final DiscussionRules rules = new DiscussionRules(
            100,    // divergePaceMs（测试用短间隔）
            3,      // maxDivergeRounds
            100,    // maxRounds
            15,     // profileExtractThreshold
            15,     // backfillLimit
            200,    // contextWindow
            500     // concludeConfirmTimeoutMs（测试用 500ms 快速超时）
    );

    @BeforeEach
    void setUp() {
        discussionFlowService = mock(DiscussionFlowService.class);
        topicRepository = mock(TopicRepository.class);
        groupBroadcastService = mock(GroupBroadcastService.class);
        engine = new DiscussionEngine(discussionFlowService, rules,
                topicRepository, groupBroadcastService);
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
        @DisplayName("调用 advance 传入 CONCLUDE 意图和正确的输入参数")
        void shouldCallAdvanceWithConcludeIntent() {
            when(discussionFlowService.advance(any(), any()))
                    .thenReturn(result(true, StateKeys.MODE_CONCLUDE, null));

            engine.runConcludeFlow(100L, 1L, "USER", 77L);

            ArgumentCaptor<Map<String, Object>> inputsCaptor = ArgumentCaptor.forClass(Map.class);
            verify(discussionFlowService).advance(eq(rules), inputsCaptor.capture());
            Map<String, Object> inputs = inputsCaptor.getValue();
            assertThat(inputs.get(StateKeys.GROUP_ID)).isEqualTo(1L);
            assertThat(inputs.get(StateKeys.TOPIC_ID)).isEqualTo(100L);
            assertThat(inputs.get(StateKeys.INTENT)).isEqualTo("CONCLUDE");
            assertThat(inputs.get(StateKeys.TRIGGERED_BY)).isEqualTo("USER");
            assertThat(inputs.get(StateKeys.CONCLUDER_AGENT_ID)).isEqualTo(77L);
        }

        @Test
        @DisplayName("concluderAgentId 为 null 时不写入 CONCLUDER_AGENT_ID")
        void nullConcluderShouldOmitKey() {
            when(discussionFlowService.advance(any(), any()))
                    .thenReturn(result(true, StateKeys.MODE_CONCLUDE, null));

            engine.runConcludeFlow(100L, 1L, "MAX_ROUNDS", null);

            ArgumentCaptor<Map<String, Object>> inputsCaptor = ArgumentCaptor.forClass(Map.class);
            verify(discussionFlowService).advance(eq(rules), inputsCaptor.capture());
            assertThat(inputsCaptor.getValue()).doesNotContainKey(StateKeys.CONCLUDER_AGENT_ID);
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
            verify(discussionFlowService, never()).advance(any(), any());
        }

        @Test
        @DisplayName("有活跃话题 + 用户信号 + advance 返回 concluded：advance 调用一次后循环退出")
        void signalWithConcludedResultShouldExitAfterOneAdvance() {
            Topic topic = activeTopic(100L, 1L);
            when(topicRepository.findActiveByGroupId(1L)).thenReturn(Optional.of(topic));
            when(discussionFlowService.advance(any(), any()))
                    .thenReturn(result(true, StateKeys.MODE_CONCLUDE, null));

            engine.onUserSignal(1L, signal("结束讨论"));

            // advance 被调用一次，inputs 包含用户消息
            ArgumentCaptor<Map<String, Object>> inputsCaptor = ArgumentCaptor.forClass(Map.class);
            verify(discussionFlowService, timeout(WAIT)).advance(eq(rules), inputsCaptor.capture());
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
            when(discussionFlowService.advance(any(), any()))
                    .thenReturn(result(false, StateKeys.MODE_DIVERGE, null))
                    .thenReturn(result(true, StateKeys.MODE_CONCLUDE, null));

            engine.onUserSignal(1L, signal("发散讨论"));

            // advance 被调用两次：第一次用户信号驱动，第二次 DIVERGE 自主推进
            verify(discussionFlowService, timeout(WAIT).times(2)).advance(eq(rules), any());
        }

        @Test
        @DisplayName("CONCLUDE_PROPOSED 模式：广播提议 → 超时自动收束 → advance 调用两次")
        void concludeProposedTimeoutShouldAutoConclude() {
            Topic topic = activeTopic(100L, 1L);
            when(topicRepository.findActiveByGroupId(1L)).thenReturn(Optional.of(topic));

            // 第一次 advance（用户信号）：返回 CONCLUDE_PROPOSED，携带 topicId 和 concluderAgentId
            Map<String, Object> proposedState = new HashMap<>();
            proposedState.put(StateKeys.TOPIC_ID, 100L);
            proposedState.put(StateKeys.TOPIC_TITLE, "收束中主题");
            proposedState.put(StateKeys.CONCLUDER_AGENT_ID, 77L);
            when(discussionFlowService.advance(any(), any()))
                    .thenReturn(result(false, StateKeys.MODE_CONCLUDE_PROPOSED, proposedState))
                    .thenReturn(result(true, StateKeys.MODE_CONCLUDE, null));

            engine.onUserSignal(1L, signal("可以总结了吗"));

            // 第一次 advance：用户信号驱动
            verify(discussionFlowService, timeout(WAIT)).advance(eq(rules), any());
            // 广播提议收束状态
            verify(groupBroadcastService, timeout(WAIT))
                    .broadcast(eq(1L), eq(WsConstants.TOPIC_STATUS_CHANGED), any());
            // 超时后 runConcludeFlow 调用 advance 第二次（CONCLUDE 意图）
            verify(discussionFlowService, timeout(WAIT + rules.concludeConfirmTimeoutMs()).times(2))
                    .advance(eq(rules), any());
        }
    }
}
