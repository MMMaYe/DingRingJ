package com.dingring.app.orchestrator;

import com.dingring.app.workflow.ConclusionService;
import com.dingring.common.constant.WsConstants;
import com.dingring.common.util.JsonHelper;
import com.dingring.common.util.LogHelper;
import com.dingring.domain.discussion.Topic;
import com.dingring.domain.discussion.TopicRepository;
import com.dingring.domain.service.DiscussionFlowService;
import com.dingring.domain.service.GroupBroadcastService;
import com.dingring.domain.workflow.DiscussionFlowResult;
import com.dingring.domain.workflow.DiscussionRules;
import com.dingring.domain.workflow.StateKeys;
import com.dingring.infrastructure.aop.Event;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 每群对话引擎（Phase C 重构）：信号队列 + 虚拟线程串行执行器 + DiscussionFlowService 状态图驱动。
 * <p>核心职责：
 * <ul>
 *   <li>维护每群信号队列和串行执行器（与原版一致）</li>
 *   <li>维护运行时状态（discussMode/passedAgentIds/lowDiscussStreak/chatBuffer/divergeRounds）</li>
 *   <li>每次循环调用 {@link DiscussionFlowService#advance} 推进流程，根据返回的 discussMode 决定后续行为</li>
 * </ul>
 * <p>三态循环逻辑已迁移到 SAA StateGraph 节点（PreprocessNode/IntentClassifyNode/ChatNode/EnsureTopicNode/
 * DiscussNode/ConcludeNode/ProfileExtractNode/WorkNode），本类只负责"驱动"和"状态维护"。
 */
@Slf4j
@Component
public class DiscussionEngine {

    /** 用户消息信号（由 ChatOrchestrator 入库广播后投递） */
    public record UserSignal(Long userId, String content, List<Long> mentionedAgentIds, Long repliedToAgentId) {
    }

    /** 每群运行时状态（除 queue/running 外仅循环线程访问） */
    private static class GroupState {
        final BlockingQueue<UserSignal> queue = new LinkedBlockingQueue<>();
        final AtomicBoolean running = new AtomicBoolean(false);
        /** 当前讨论模式（CONVERGE/DIVERGE/WAIT/CONCLUDE_PROPOSED/CONCLUDE），用于决定 pace */
        String discussMode;
        /** 本轮已 PASS 的 Agent ID 列表（有人发言即清空） */
        List<Long> passedAgentIds = List.of();
        /** @提及的一次性发言权是否已消费（新用户消息含提及时重置，发言/跳过一次后置 true） */
        boolean mentionHandled;
        /** 连续低置信度 DISCUSS 计数（追溯式建题） */
        int lowDiscussStreak;
        /** 闲聊缓冲计数（达阈值触发画像提炼） */
        int chatBuffer;
        /** 发散轮次计数（达 maxDivergeRounds 自动回拉收敛） */
        int divergeRounds;
    }

    private final DiscussionFlowService discussionFlowService;
    private final DiscussionRules rules;
    private final TopicRepository topicRepository;
    private final GroupBroadcastService groupBroadcastService;
    private final ConclusionService conclusionService;

    private final Map<Long, GroupState> states = new ConcurrentHashMap<>();
    private final Map<Long, ExecutorService> groupExecutors = new ConcurrentHashMap<>();

    public DiscussionEngine(DiscussionFlowService discussionFlowService,
                           DiscussionRules rules,
                           TopicRepository topicRepository,
                           GroupBroadcastService groupBroadcastService,
                           ConclusionService conclusionService) {
        this.discussionFlowService = discussionFlowService;
        this.rules = rules;
        this.topicRepository = topicRepository;
        this.groupBroadcastService = groupBroadcastService;
        this.conclusionService = conclusionService;
    }

    /* ==================== 对外入口 ==================== */

    /** 用户消息信号入队并唤醒引擎循环 */
    public void onUserSignal(Long groupId, UserSignal signal) {
        LogHelper.printLog(DiscussionEngine.class, "DiscussionEngine.onUserSignal",
                "ON_USER_SIGNAL", "消息信号准备入队",
                "request={}", JsonHelper.toJsonPretty(signal));
        stateOf(groupId).queue.offer(signal);
        wake(groupId);
    }

    /** 唤醒群引擎循环（CAS 防重入；TopicAppService 手动建题后也调用） */
    public void wake(Long groupId) {
        GroupState state = stateOf(groupId);
        if (state.running.compareAndSet(false, true)) {
            executorOf(groupId).execute(() -> runLoopSafely(groupId, state));
        }
    }

    /** 在群串行执行器上排队执行任务（与循环串行；结论生成已改用 ConclusionService，不再走此队列） */
    public void execute(Long groupId, String taskName, Runnable task) {
        executorOf(groupId).execute(() -> safeRun(taskName, groupId, task));
    }

    /**
     * 触发收束流程（供 ChatOrchestrator 调用）。
     * <p>委托 {@link ConclusionService#triggerAsync}：同步完成 IN_PROGRESS→CONCLUDING 状态流转，
     * 结论 LLM 生成提交到独立 {@link ConclusionExecutor}，不再占用群串行执行器。
     */
    @Event(eventCode = "RUN_CONCLUDE_FLOW", eventName = "触发收束流程")
    public void runConcludeFlow(Long topicId, Long groupId, String triggeredBy, Long concluderAgentId) {
        LogHelper.printLog(DiscussionEngine.class, "DiscussionEngine.runConcludeFlow",
                "RUN_CONCLUDE_FLOW", "触发收束流程",
                "topicId={} groupId={} triggeredBy={} concluderAgentId={}",
                topicId, groupId, triggeredBy, concluderAgentId);
        conclusionService.triggerAsync(topicId, groupId, triggeredBy, concluderAgentId);
    }

    /* ==================== 主循环 ==================== */

    private void runLoopSafely(Long groupId, GroupState state) {
        try {
            runLoop(groupId, state);
        } catch (Exception e) {
            LogHelper.printWarnLog(DiscussionEngine.class, "DiscussionEngine.runLoopSafely",
                    "RUN_LOOP_SAFELY", "主循环异常退出", "groupId={}", groupId, e);
        } finally {
            state.running.set(false);
            // 防丢唤醒：退出瞬间又有新信号到达则 CAS 抢回重启
            if (!state.queue.isEmpty()) {
                wake(groupId);
            }
        }
    }

    /**
     * 主循环：poll 信号 → advance 流程 → 根据 discussMode 决定后续行为。
     * <p>讨论模式行为：
     * <ul>
     *   <li>CONVERGE：阻塞等用户消息（不自主推进）</li>
     *   <li>DIVERGE：divergePaceMs 超时后自主推进一轮</li>
     *   <li>WAIT：阻塞等用户回答（queue.take）</li>
     *   <li>CONCLUDE_PROPOSED：等用户确认（5分钟超时自动收束）</li>
     *   <li>CONCLUDE：流程内已收束，退出循环</li>
     * </ul>
     */
    @Event(eventCode = "RUN_LOOP", eventName = "主循环")
    private void runLoop(Long groupId, GroupState state) throws InterruptedException {
        //TODO：先取时间戳作为标识
        long startTime = System.currentTimeMillis();
        LogHelper.putTrace(groupId,  startTime);
        try {
            while (true) {
                Optional<Topic> active = topicRepository.findActiveByGroupId(groupId)
                        .filter(Topic::isInProgress);

                // 闲聊态（无活跃话题）：poll 不等待，空即退出
                // 讨论态：按 discussMode 决定 pace（DIVERGE=divergePaceMs，其他=阻塞等待）
                long timeout = paceForMode(state, active.isPresent());
                //打印超时日志
                LogHelper.printLog(DiscussionEngine.class, "DiscussionEngine.runLoop",
                        "RUN_LOOP_WAIT_TIME",
                        "主循环等待时长","此次需要等：{}秒",timeout/1000);
                UserSignal signal = active.isPresent()
                        ? state.queue.poll(timeout, TimeUnit.MILLISECONDS)
                        : state.queue.poll();

                if (signal != null) {
                    // 丢弃积压信号（已入库，意图分类从 DB 拉完整上下文）
                    state.queue.clear();
                    DiscussionFlowResult result = advanceFlow(groupId, signal, state);

                    //TODO：群的状态应该维护在domain层？ 又或者stateGraph中？
                    updateRuntimeState(state, result);
                    pushTopicStatus(groupId, result);

                    if (result.concluded()) return;

                    String mode = result.discussMode();
                    state.discussMode = mode;

                    // WAIT：Agent 追问用户，阻塞等下一条消息
                    if (StateKeys.MODE_WAIT.equals(mode)) {
                        state.queue.take();
                        continue;
                    }
                    // CONCLUDE_PROPOSED：Agent 提议收束，等用户确认（5分钟超时兜底）
                    if (StateKeys.MODE_CONCLUDE_PROPOSED.equals(mode)) {
                        if (handleConcludeProposed(groupId, state, result)) return;
                        continue;
                    }
                    // CONVERGE/DIVERGE：继续循环
                    continue;
                }

                // 无信号
                if (active.isEmpty()) {
                    // 闲聊态：退出等唤醒
                    return;
                }
                // 讨论态 DIVERGE：pace 超时，自主推进一轮
                if (StateKeys.MODE_DIVERGE.equals(state.discussMode)) {
                    DiscussionFlowResult result = advanceAuto(groupId, state);
                    updateRuntimeState(state, result);
                    pushTopicStatus(groupId, result);
                    if (result.concluded()) return;
                    state.discussMode = result.discussMode();
                    continue;
                }
                // CONVERGE/WAIT：无自主推进，退出等用户
                return;
            }
        } finally {
            LogHelper.clearTrace();
        }
    }

    /* ==================== 流程调用 ==================== */

    /** 用户消息驱动流程：构建 inputs（含运行时状态）→ advance → 返回结果 */
    @Event(eventCode = "ADVANCE_FLOW", eventName = "流程推进")
    private DiscussionFlowResult advanceFlow(Long groupId, UserSignal signal, GroupState state) {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put(StateKeys.GROUP_ID, groupId);
        inputs.put(StateKeys.INPUT, signal.content());
        inputs.put(StateKeys.MENTIONED_AGENT_IDS, signal.mentionedAgentIds());
        inputs.put(StateKeys.REPLIED_TO_AGENT_ID, signal.repliedToAgentId());
        // 运行时状态透传给节点
        inputs.put(StateKeys.PASSED_AGENT_IDS, state.passedAgentIds);
        inputs.put(StateKeys.LOW_DISCUSS_STREAK, state.lowDiscussStreak);
        inputs.put(StateKeys.CHAT_BUFFER, state.chatBuffer);
        inputs.put(StateKeys.DIVERGE_ROUNDS, state.divergeRounds);
        // @提及一次性发言权：新用户消息含提及时重置（重新豁免一次），否则透传原值
        boolean hasMention = signal.mentionedAgentIds() != null && !signal.mentionedAgentIds().isEmpty();
        inputs.put(StateKeys.MENTION_HANDLED, hasMention ? Boolean.FALSE : state.mentionHandled);
        LogHelper.printLog(DiscussionEngine.class, "DiscussionEngine.advanceFlow",
                "ADVANCE_FLOW_INPUTS", "构建inputs内容", "inputs", inputs);
        return discussionFlowService.advance(rules, inputs);
    }

    /** DIVERGE 自主推进：无用户消息，设 INTENT=DISCUSS 跳过意图分类直接进入讨论 */
    private DiscussionFlowResult advanceAuto(Long groupId, GroupState state) {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put(StateKeys.GROUP_ID, groupId);
        inputs.put(StateKeys.INPUT, "");
        inputs.put(StateKeys.INTENT, "DISCUSS");
        inputs.put(StateKeys.MENTIONED_AGENT_IDS, List.of());
        inputs.put(StateKeys.PASSED_AGENT_IDS, state.passedAgentIds);
        inputs.put(StateKeys.DIVERGE_ROUNDS, state.divergeRounds);
        inputs.put(StateKeys.MENTION_HANDLED, state.mentionHandled);
        return discussionFlowService.advance(rules, inputs);
    }

    /** CONCLUDE_PROPOSED 处理：广播提议 → 等用户确认 → 确认/超时 */
    private boolean handleConcludeProposed(Long groupId, GroupState state, DiscussionFlowResult result)
            throws InterruptedException {
        Long topicId = result.stateValue(StateKeys.TOPIC_ID, null);
        String topicTitle = result.stateValue(StateKeys.TOPIC_TITLE, "");
        Long concluderAgentId = result.stateValue(StateKeys.CONCLUDER_AGENT_ID, null);

        // 广播提议收束状态
        groupBroadcastService.broadcast(groupId, WsConstants.TOPIC_STATUS_CHANGED, Map.of(
                "groupId", groupId,
                "topicId", topicId,
                "title", topicTitle,
                "discussMode", StateKeys.MODE_CONCLUDE_PROPOSED,
                "triggeredBy", "AGENT"));
        // TOPIC_STATUS：驱动前端讨论状态横幅进入 CONCLUDE_PROPOSED，展示确认/继续按钮
        groupBroadcastService.broadcast(groupId, WsConstants.TOPIC_STATUS, Map.of(
                "groupId", groupId,
                "discussMode", StateKeys.MODE_CONCLUDE_PROPOSED,
                "topicTitle", topicTitle,
                "divergeRounds", 0,
                "maxDivergeRounds", rules.maxDivergeRounds(),
                "restartHint", ""));

        // 等用户确认（5分钟超时兜底）
        UserSignal confirm = state.queue.poll(rules.concludeConfirmTimeoutMs(), TimeUnit.MILLISECONDS);
        if (confirm == null) {
            // 超时：自动收束
            LogHelper.printLog(DiscussionEngine.class, "DiscussionEngine.handleConcludeProposed",
                    "CONCLUDE_PROPOSED", "提议收束超时自动收束", "groupId={} topicId={}", groupId, topicId);
            runConcludeFlow(topicId, groupId, "TIMEOUT", concluderAgentId);
            return true;
        }
        // 用户回复：作为正常消息驱动流程（意图分类判定是确认还是继续讨论）
        state.queue.clear();
        DiscussionFlowResult confirmResult = advanceFlow(groupId, confirm, state);
        updateRuntimeState(state, confirmResult);
        pushTopicStatus(groupId, confirmResult);
        if (confirmResult.concluded()) return true;
        state.discussMode = confirmResult.discussMode();
        return false;
    }

    /** 广播讨论状态（TOPIC_STATUS）：驱动前端讨论状态横幅实时更新 */
    private void pushTopicStatus(Long groupId, DiscussionFlowResult result) {
        if (result.state() == null) return;
        String mode = result.discussMode();
        Object title = result.state().get(StateKeys.TOPIC_TITLE);
        Object rounds = result.state().get(StateKeys.DIVERGE_ROUNDS);
        Object hint = result.state().get(StateKeys.RESTART_HINT);
        groupBroadcastService.broadcast(groupId, WsConstants.TOPIC_STATUS, Map.of(
                "groupId", groupId,
                "discussMode", mode,
                "topicTitle", title == null ? "" : title,
                "divergeRounds", rounds instanceof Integer ? (Integer) rounds : 0,
                "maxDivergeRounds", rules.maxDivergeRounds(),
                "restartHint", hint == null ? "" : hint));
    }

    /* ==================== 运行时状态维护 ==================== */

    /** 从流程结果更新 GroupState 的运行时状态 */
    @SuppressWarnings("unchecked")
    private void updateRuntimeState(GroupState state, DiscussionFlowResult result) {
        if (result.state() == null) return;
        state.discussMode = result.discussMode();
        Object passed = result.state().get(StateKeys.PASSED_AGENT_IDS);
        if (passed != null) state.passedAgentIds = (List<Long>) passed;
        Object streak = result.state().get(StateKeys.LOW_DISCUSS_STREAK);
        if (streak instanceof Integer) state.lowDiscussStreak = (Integer) streak;
        Object buffer = result.state().get(StateKeys.CHAT_BUFFER);
        if (buffer instanceof Integer) state.chatBuffer = (Integer) buffer;
        Object rounds = result.state().get(StateKeys.DIVERGE_ROUNDS);
        if (rounds instanceof Integer) state.divergeRounds = (Integer) rounds;
        Object handled = result.state().get(StateKeys.MENTION_HANDLED);
        if (handled instanceof Boolean) state.mentionHandled = (Boolean) handled;
    }

    /** 根据讨论模式决定 poll 超时：DIVERGE=divergePaceMs，其他=阻塞等待 */
    private long paceForMode(GroupState state, boolean hasActiveTopic) {
        if (!hasActiveTopic) return 0;
        if (StateKeys.MODE_DIVERGE.equals(state.discussMode)) return rules.divergePaceMs();
        return Long.MAX_VALUE;
    }

    /* ==================== 基础设施 ==================== */

    private GroupState stateOf(Long groupId) {
        return states.computeIfAbsent(groupId, id -> new GroupState());
    }

    private ExecutorService executorOf(Long groupId) {
        return groupExecutors.computeIfAbsent(groupId,
                id -> Executors.newSingleThreadExecutor(Thread.ofVirtual().name("group-" + id + "-", 0).factory()));
    }

    /** 异步任务兜底：未捕获异常会让虚拟线程任务静默消失，统一捕获并记录 */
    @Event(eventCode = "SAFE_RUN", eventName = "提交结论总结异步任务")
    public void safeRun(String taskName, Long groupId, Runnable task) {
        try {
            task.run();
        } catch (Exception e) {
            LogHelper.printWarnLog(DiscussionEngine.class, "DiscussionEngine.safeRun",
                    "SAFE_RUN", "异步任务异常退出", "task={} groupId={}", taskName, groupId, e);
        }
    }
}
