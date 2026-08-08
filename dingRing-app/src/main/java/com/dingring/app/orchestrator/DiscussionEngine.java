package com.dingring.app.orchestrator;

import com.dingring.app.service.GroupAppService;
import com.dingring.app.service.MessageAssembler;
import com.dingring.common.constant.WsConstants;
import com.dingring.common.exception.BizException;
import com.dingring.common.exception.ErrorCode;
import com.dingring.common.util.JsonHelper;
import com.dingring.common.util.LogHelper;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.agent.AgentRepository;
import com.dingring.domain.discussion.Topic;
import com.dingring.domain.discussion.TopicRepository;
import com.dingring.domain.discussion.TopicStatus;
import com.dingring.domain.event.AgentFailed;
import com.dingring.domain.event.AgentSelected;
import com.dingring.domain.event.MessageSent;
import com.dingring.domain.event.TopicCreated;
import com.dingring.domain.group.Group;
import com.dingring.domain.group.GroupMessage;
import com.dingring.domain.group.GroupRepository;
import com.dingring.domain.group.MessageRepository;
import com.dingring.domain.group.MessageType;
import com.dingring.domain.group.SenderType;
import com.dingring.domain.service.DomainEventPublisher;
import com.dingring.domain.service.GroupBroadcastService;
import com.dingring.domain.service.LlmService;
import com.dingring.domain.service.ProfileService;
import com.dingring.infrastructure.aop.Event;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 每群对话引擎：闲聊 / 讨论 / 收束 三态主循环（替代 per-message 调度）。
 * <p>每群一个串行虚拟线程 + 一个信号队列：讨论态按 pace 随机间隔自主推进直至自然收敛；
 * 闲聊态队列空即退出（用户消息 {@link #onUserSignal} 唤醒重启，重启自愈）。
 * <p>收束触发后循环必须退出：结论生成任务排在同一串行执行器上，循环不让位会死锁。
 */
@Slf4j
@Component
public class DiscussionEngine {

    /** 用户消息信号（由 ChatOrchestrator 入库广播后投递） */
    public record UserSignal(Long userId, String content, List<Long> mentionedAgentIds, Long repliedToAgentId) {
    }

    /** 连续 LOW 置信度 DISCUSS 达到该次数即建题 */
    private static final int LOW_DISCUSS_CREATE_STREAK = 2;

    /** 每群运行时状态（除 queue/running 外仅循环线程访问） */
    private static class GroupState {
        final BlockingQueue<UserSignal> queue = new LinkedBlockingQueue<>();
        final AtomicBoolean running = new AtomicBoolean(false);
        /** 本轮讨论中已 PASS 的 Agent（有人真实发言即清空） */
        final Set<Long> passedAgents = new HashSet<>();
        /** 连续低置信度 DISCUSS 计数（追溯式建题） */
        int lowDiscussStreak;
        /** 闲聊缓冲计数（达阈值触发画像提炼） */
        int chatBuffer;
        /** 讨论中被 @ 的 Agent：下一轮优先发言一次 */
        Long pendingMentionAgentId;
        Long pendingReplyToAgentId;
    }

    private enum SpeakOutcome { SPOKE, PASSED, SILENT, CONCLUDED, FAILED }

    private record SpeakResult(SpeakOutcome outcome, Agent agent) {
    }

    private record Folded(UserSignal signal, int count) {
    }

    private final GroupRepository groupRepository;
    private final MessageRepository messageRepository;
    private final TopicRepository topicRepository;
    private final AgentRepository agentRepository;
    private final SpeakerScheduler speakerScheduler;
    private final ContextBuilder contextBuilder;
    private final Terminator terminator;
    private final MessageAssembler messageAssembler;
    private final LlmService llmService;
    private final DomainEventPublisher eventPublisher;
    private final GroupBroadcastService groupBroadcastService;
    private final MessageRouter messageRouter;
    private final ProfileService profileService;
    private final ModeratorService moderatorService;
    private final ChatOrchestrator chatOrchestrator;

    /** 讨论态自主发言间隔随机区间（毫秒，测试注 0） */
    @Value("${dingring.orchestrator.pace-min-ms:5000}")
    private long paceMinMs;
    @Value("${dingring.orchestrator.pace-max-ms:15000}")
    private long paceMaxMs;

    /** 不同 Agent 连续 PASS 达该数触发收敛收束 */
    @Value("${dingring.orchestrator.converge-pass-count:2}")
    private int convergePassCount;

    /** 闲聊积累多少条触发画像提炼 */
    @Value("${dingring.orchestrator.profile-extract-threshold:15}")
    private int profileExtractThreshold;

    /** 流式输出开关：开启后 Agent 发言逐块推送 MESSAGE_DELTA，完成推 MESSAGE_COMPLETE 替代 NEW_MESSAGE */
    @Value("${dingring.streaming.enabled:false}")
    private boolean streamingEnabled;

    /** 追溯建题最多回填闲聊条数 */
    @Value("${dingring.orchestrator.backfill-limit:15}")
    private int backfillLimit;

    private final Map<Long, GroupState> states = new ConcurrentHashMap<>();
    private final Map<Long, ExecutorService> groupExecutors = new ConcurrentHashMap<>();

    public DiscussionEngine(GroupRepository groupRepository,
                            MessageRepository messageRepository,
                            TopicRepository topicRepository,
                            AgentRepository agentRepository,
                            SpeakerScheduler speakerScheduler,
                            ContextBuilder contextBuilder,
                            Terminator terminator,
                            MessageAssembler messageAssembler,
                            LlmService llmService,
                            DomainEventPublisher eventPublisher,
                            GroupBroadcastService groupBroadcastService,
                            MessageRouter messageRouter,
                            ProfileService profileService,
                            ModeratorService moderatorService,
                            @Lazy ChatOrchestrator chatOrchestrator) {
        this.groupRepository = groupRepository;
        this.messageRepository = messageRepository;
        this.topicRepository = topicRepository;
        this.agentRepository = agentRepository;
        this.speakerScheduler = speakerScheduler;
        this.contextBuilder = contextBuilder;
        this.terminator = terminator;
        this.messageAssembler = messageAssembler;
        this.llmService = llmService;
        this.eventPublisher = eventPublisher;
        this.groupBroadcastService = groupBroadcastService;
        this.messageRouter = messageRouter;
        this.profileService = profileService;
        this.moderatorService = moderatorService;
        this.chatOrchestrator = chatOrchestrator;
    }

    /* ==================== 对外入口 ==================== */

    /** 用户消息信号入队并唤醒引擎循环 */
    public void onUserSignal(Long groupId, UserSignal signal) {
        LogHelper.printLog(DiscussionEngine.class, "DiscussionEngine.onUserSignal", "ON_USER_SIGNAL", "消息信号准备入队",
                "request={}", JsonHelper.toJsonPretty(signal));
        stateOf(groupId).queue.offer(signal);
        wake(groupId);
    }

    /** 唤醒群引擎循环（CAS 防重入；TopicAppService 手动建题后也调用） */
    public void wake(Long groupId) {
        GroupState state = stateOf(groupId);
        LogHelper.printLog(DiscussionEngine.class, "DiscussionEngine.wake",
                "WAKE",
                "唤醒群引擎循环",
                "msg={}", JsonHelper.mapToJsonStr(Map.of("groupId", groupId, "groupState", state)));
        if (state.running.compareAndSet(false, true)) {
            executorOf(groupId).execute(() -> runLoopSafely(groupId, state));
        }
    }

    /** 在群串行执行器上排队执行任务（收束域结论生成复用，保证与循环串行） */
    public void execute(Long groupId, String taskName, Runnable task) {
        executorOf(groupId).execute(() -> safeRun(taskName, groupId, task));
    }

    /* ==================== 主循环 ==================== */

    private void runLoopSafely(Long groupId, GroupState state) {
        LogHelper.printLog(DiscussionEngine.class, "DiscussionEngine.runLoopSafely",
                "RUN_LOOP_SAFELY",
                "主循环启动", "request={}", JsonHelper.mapToJsonStr(Map.of("groupId", groupId, "groupState", state)));
        try {
            runLoop(groupId, state);
        } catch (Exception e) {
            LogHelper.printWarnLog(DiscussionEngine.class, "DiscussionEngine.runLoopSafely", "RUN_LOOP_SAFELY", "主循环异常退出", "groupId={}", groupId, e);
        } finally {
            state.running.set(false);
            // 防丢唤醒：退出瞬间又有新信号到达则 CAS 抢回重启
            if (!state.queue.isEmpty()) {
                wake(groupId);
            }
        }
    }

    private void runLoop(Long groupId, GroupState state) throws InterruptedException {
        LogHelper.putTrace(groupId, null);
        try {
            LogHelper.printLog(DiscussionEngine.class,
                    "DiscussionEngine.runLoop",
                    "RUN_LOOP",
                    "循环启动",
                    "request={}", JsonHelper.mapToJsonStr(Map.of("groupId", groupId, "groupState", state)));
            while (true) {
                //业务规则：每个群，某一时刻最多只有一个话题在进行讨论
                //所以拿去活跃话题
                Optional<Topic> active = topicRepository.findActiveByGroupId(groupId)
                        .filter(Topic::isInProgress);
                //讨论态限时等待（超时即自主推进）；闲聊态不等待，队列空直接退出
                UserSignal head = active.isPresent()
                        ? state.queue.poll(pace(), TimeUnit.MILLISECONDS)
                        : state.queue.poll();
                if (head != null) {
                    Folded fold = fold(state.queue, head);
                    //处理消息
                    if (handleSignal(groupId, state, fold)) {
                        return;
                    }
                    continue;
                }
                if (active.isEmpty()) {
                    LogHelper.printLog(DiscussionEngine.class, "DiscussionEngine.runLoop", "RUN_LOOP", "闲聊态退出待唤醒", "groupId={}", groupId);
                    return;
                }
                if (advanceDiscussion(groupId, state, active.get())) {
                    return;
                }
            }
        } finally {
            LogHelper.clearTrace();
        }
    }

    /** 多条连发折叠：取最新语境，@提及取并集 */
    private Folded fold(BlockingQueue<UserSignal> queue, UserSignal head) {
        LogHelper.printLog(DiscussionEngine.class,
                "DiscussionEngine.fold",
                "FOLD",
                "折叠连发消息",
                "request={}", JsonHelper.toJsonPretty(Map.of("queue",queue, "head",head)));

        UserSignal latest = head;
        Set<Long> mentions = new LinkedHashSet<>(head.mentionedAgentIds());
        int count = 1;
        UserSignal next;
        while ((next = queue.poll()) != null) {
            latest = next;
            mentions.addAll(next.mentionedAgentIds());
            count++;
        }
        if (count > 1) {
            LogHelper.printLog(DiscussionEngine.class, "DiscussionEngine.fold", "FOLD", "折叠连发消息", "count={}", count);
        }
        return new Folded(new UserSignal(latest.userId(), latest.content(),
                List.copyOf(mentions), latest.repliedToAgentId()), count);
    }

    /* ==================== 信号处理 ==================== */

    /**
     * 路由并处理一条用户信号。
     *
     * @return true = 已触发收束，循环必须退出让位给结论生成任务
     */
    @Event(eventCode = "HANDLE_SIGNAL", eventName = "处理用户发的消息")
    public boolean handleSignal(Long groupId, GroupState state, Folded folded) {
        UserSignal signal = folded.signal();
        Group group = groupRepository.findById(groupId).orElse(null);
        if (group == null) {
            return false;
        }
        List<Agent> agents = agentRepository.findByIds(group.memberAgentIds());
        if (agents.isEmpty()) {
            return false;
        }

        //拿活跃话题
        Optional<Topic> active = topicRepository.findActiveByGroupId(groupId)
                .filter(Topic::isInProgress);

        //选举路由判定的Agent：从DB加载专职路由判定器，命中即用；未命中降级为群首成员（WARN 日志告警）
        Agent judgeRole = loadRouteJudge(agents);

        MessageRouter.Route route = messageRouter.route(judgeRole, signal.content(),
                active.map(Topic::getTitle).orElse(null));

        // 讨论中被 @ 的 Agent：下一轮优先发言一次
        if (active.isPresent() && !signal.mentionedAgentIds().isEmpty()) {
            state.pendingMentionAgentId = signal.mentionedAgentIds().get(0);
        }
        state.pendingReplyToAgentId = signal.repliedToAgentId();

        switch (route.intent()) {
            case CONCLUDE -> {
                //如果当前有活跃主题，尝试收束讨论
                if (active.isPresent()) {
                    Long concluderId = signal.mentionedAgentIds().isEmpty()
                            ? null : signal.mentionedAgentIds().get(0);
                    return tryConclude(active.get().getId(), signal.userId(), "USER", concluderId);
                }
                // 无活跃主题的收束请求按闲聊处理
                state.lowDiscussStreak = 0;
                handleChat(group, state, signal, folded.count());
            }
            case DISCUSS -> {
                if (active.isEmpty()) {
                    Topic created = ensureTopic(group, state, route);
                    if (created == null) {
                        // 未达建题门槛：先按闲聊轻量应答
                        handleChat(group, state, signal, folded.count());
                    }
                    // 建题成功：下一轮循环进入讨论态自主推进（触发消息已回填入主题）
                }
                // 已有活跃主题：消息已归属主题，讨论循环自然衔接
            }
            case CHAT -> {
                state.lowDiscussStreak = 0;
                if (active.isEmpty()) {
                    handleChat(group, state, signal, folded.count());
                }
                // 讨论中的闲聊插话不打断讨论，消息随主题窗口进入上下文
            }
        }
        return false;
    }

    /**
     * 加载路由判定器 Agent：
     * <ol>
     *   <li>优先从 DB 查 feature.routeJudge=true 的专职判定器（独立于群成员配置，不参与讨论）</li>
     *   <li>未配置则降级为群首个成员，并打 WARN 日志提醒补齐配置</li>
     * </ol>
     * 降级目的：主流程不中断，路由判定用群成员模型兜底（可能用大模型做路由浪费成本，但功能可用）。
     *
     * @param agents 群成员 Agent 列表（降级候选池）
     * @return 用于 CHAT/DISCUSS/CONCLUDE 意图分类的 Agent
     */
    private Agent loadRouteJudge(List<Agent> agents) {
        Optional<Agent> judge = agentRepository.findRouteJudge();
        if (judge.isPresent()) {
            return judge.get();
        }
        LogHelper.printWarnLog(DiscussionEngine.class, "DiscussionEngine.loadRouteJudge",
                "ROUTE_JUDGE_MISSING",
                "未配置路由判定器 Agent（feature.routeJudge=true），降级为群首个成员。请在 agent 表补齐专职判定器。",
                "fallbackAgent={}", agents.isEmpty() ? "null" : agents.get(0).getName());
        if (agents.isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND, "群内无 Agent 成员，也未配置路由判定器");
        }
        return agents.get(0);
    }

    /** 触发收束；乐观锁冲突等失败时返回 false 让循环继续（下一轮重新评估状态） */
    //TODO：尝试收束逻辑的合理性？
    @Event(eventCode = "TRY_CONCLUDE", eventName = "尝试收束")
    public boolean tryConclude(Long topicId, Long operatorId, String triggeredBy, Long concluderAgentId) {
        try {
            chatOrchestrator.conclude(topicId, operatorId, triggeredBy, concluderAgentId);
            return true;
        } catch (BizException e) {
            LogHelper.printWarnLog(DiscussionEngine.class, "DiscussionEngine.tryConclude", "TRY_CONCLUDE", "收束触发失败", "topicId={} 原因: {}", topicId, e.getMessage());
            return false;
        }
    }

    /* ==================== 闲聊态：轻量应答 + 画像提炼 ==================== */
    @Event(eventCode = "HANDLE_CHAT",eventName = "处理")
    public void handleChat(Group group, GroupState state, UserSignal signal, int messageCount) {
        state.chatBuffer += messageCount;
        if (state.chatBuffer >= profileExtractThreshold) {
            state.chatBuffer = 0;
            //TODO:这个以事件的形式（发布-订阅）去触发
            triggerProfileExtraction(group);
        }

        //如果用户没有@任何Agent，则自动接话条数为配置值
        boolean mentionRound = !signal.mentionedAgentIds().isEmpty();
        int maxReplies = mentionRound ? 1 : terminator.getAutoReplies();


        MessageContext ctx = MessageContext.builder()
                .groupId(group.getId())
                .topicId(null)
                .content(signal.content())
                .mentionedAgentIds(signal.mentionedAgentIds())
                .repliedToAgentId(signal.repliedToAgentId())
                .speakCounts(Map.of())
                .build();

        for (int i = 0; i < maxReplies; i++) {
            // 用户又说话了：让位给新信号
            if (!state.queue.isEmpty()) {
                return;
            }

            List<Agent> candidates = agentRepository.findByIds(group.memberAgentIds());

            SpeakResult result = speakOnce(candidates, ctx);
            if (result.outcome() != SpeakOutcome.SPOKE) {
                return;
            }
            ctx = MessageContext.builder()
                    .groupId(group.getId())
                    .topicId(null)
                    .content("")
                    .mentionedAgentIds(List.of())
                    .repliedToAgentId(null)
                    .speakCounts(Map.of())
                    .build();
        }
    }

    /** 独立虚拟线程提炼画像（不阻塞群循环）；单用户阶段固定 DEFAULT_USER_ID */
    private void triggerProfileExtraction(Group group) {
        List<Agent> agents = agentRepository.findByIds(group.memberAgentIds());
        if (agents.isEmpty()) {
            return;
        }
        Agent extractor = agents.get(0);
        List<GroupMessage> recent = messageRepository.findRecentChatByGroupId(
                group.getId(), profileExtractThreshold);
        String dialogue = formatDialogue(recent);
        LogHelper.printLog(DiscussionEngine.class, "DiscussionEngine.triggerProfileExtraction", "TRIGGER_PROFILE_EXTRACTION", "触发画像提炼", "groupId={} 输入消息数={}", group.getId(), recent.size());
        Thread.ofVirtual().name("profile-extract-" + group.getId()).start(() ->
                profileService.extractAndMerge(GroupAppService.DEFAULT_USER_ID,
                        extractor, group.getName(), dialogue));
    }

    private String formatDialogue(List<GroupMessage> messages) {
        return messages.stream()
                .map(m -> messageAssembler.resolveSenderName(m) + ": " + m.getContent())
                .collect(Collectors.joining("\n"));
    }

    /* ==================== 追溯式建题 ==================== */

    /**
     * HIGH 置信度立即建题；连续 LOW 达 {@value #LOW_DISCUSS_CREATE_STREAK} 次建题。
     *
     * @return 建题成功返回 Topic；未达门槛返回 null
     */
    @Event(eventCode = "ENSURE_TOPIC", eventName = "建题")
    public Topic ensureTopic(Group group, GroupState state, MessageRouter.Route route) {
        boolean create = route.confidence() == MessageRouter.Confidence.HIGH
                || ++state.lowDiscussStreak >= LOW_DISCUSS_CREATE_STREAK;
        if (!create) {
            LogHelper.printLog(DiscussionEngine.class, "DiscussionEngine.ensureTopic", "ENSURE_TOPIC", "低置信度暂不建题",
                    "streak={}/{} groupId={}", state.lowDiscussStreak, LOW_DISCUSS_CREATE_STREAK, group.getId());
            return null;
        }
        state.lowDiscussStreak = 0;
        Topic topic = new Topic();
        topic.setChatGroupId(group.getId());
        topic.setTitle(route.topicTitle());
        topic.setStatus(TopicStatus.IN_PROGRESS);
        try {
            topicRepository.save(topic);
        } catch (DuplicateKeyException e) {
            Optional<Topic> existing = topicRepository.findActiveByGroupId(group.getId());
            if (existing.isPresent()) {
                LogHelper.printWarnLog(DiscussionEngine.class, "DiscussionEngine.ensureTopic", "ENSURE_TOPIC", "建题并发冲突沿用现有", "groupId={}", group.getId());
                return existing.get();
            }
            // 与历史主题标题撞车：加时间后缀重试一次
            topic.setId(null);
            topic.setTitle(route.topicTitle() + "·"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMdd-HHmm")));
            try {
                topicRepository.save(topic);
            } catch (DuplicateKeyException e2) {
                LogHelper.printWarnLog(DiscussionEngine.class, "DiscussionEngine.ensureTopic", "ENSURE_TOPIC", "建题重试仍冲突放弃", "groupId={}", group.getId());
                return null;
            }
        }
        backfillChatMessages(group.getId(), topic.getId());
        eventPublisher.publish(new TopicCreated(topic.getId(), group.getId(), topic.getTitle()));
        groupBroadcastService.broadcast(group.getId(), WsConstants.TOPIC_CREATED, Map.of(
                "groupId", group.getId(),
                "topicId", topic.getId(),
                "title", topic.getTitle(),
                "status", topic.getStatus().name(),
                "round", 0,
                "maxRounds", terminator.getMaxRounds()));
        LogHelper.printLog(DiscussionEngine.class, "DiscussionEngine.ensureTopic", "ENSURE_TOPIC", "追溯式建题成功",
                "groupId={} topicId={} title={}", group.getId(), topic.getId(), topic.getTitle());
        return topic;
    }

    /** 把上一个主题关闭之后的近期闲聊消息回填进新主题 */
    private void backfillChatMessages(Long groupId, Long topicId) {
        List<GroupMessage> recent = messageRepository.findRecentChatByGroupId(groupId, backfillLimit);
        LocalDateTime boundary = lastClosedAt(groupId);
        List<Long> ids = recent.stream()
                .filter(m -> boundary == null || m.getCreateTime() == null
                        || m.getCreateTime().isAfter(boundary))
                .map(GroupMessage::getId)
                .toList();
        int updated = messageRepository.updateTopicId(ids, topicId);
        LogHelper.printLog(DiscussionEngine.class, "DiscussionEngine.backfillChatMessages", "BACKFILL_CHAT_MESSAGES", "回填完成", "topicId={} 回填条数={}", topicId, updated);
    }

    private LocalDateTime lastClosedAt(Long groupId) {
        List<Topic> closed = topicRepository.findClosedByGroupId(groupId);
        return closed.isEmpty() ? null : closed.get(closed.size() - 1).getClosedAt();
    }

    /* ==================== 讨论态：自主推进 ==================== */

    /**
     * 讨论态推进一轮发言。
     *
     * @return true = 循环需退出（收束已触发 / 全员失败 / 群失效）
     */
    private boolean advanceDiscussion(Long groupId, GroupState state, Topic topic) {
        // 熔断兜底：达到最大轮次自动收束
        if (terminator.reachedMaxRounds(topic.getId())) {
            LogHelper.printLog(DiscussionEngine.class, "DiscussionEngine.advanceDiscussion", "ADVANCE_DISCUSSION", "达到最大轮次自动收束", "topicId={}", topic.getId());
            return tryConclude(topic.getId(), null, "MAX_ROUNDS", null);
        }
        Group group = groupRepository.findById(groupId).orElse(null);
        if (group == null) {
            return true;
        }
        List<Agent> all = agentRepository.findByIds(group.memberAgentIds());
        if (all.isEmpty()) {
            LogHelper.printWarnLog(DiscussionEngine.class, "DiscussionEngine.advanceDiscussion", "ADVANCE_DISCUSSION", "无成员Agent循环退出", "groupId={}", groupId);
            return true;
        }
        // 被 @ 的 Agent 即使已 PASS 也要应答一次
        Long mentionId = state.pendingMentionAgentId;
        state.pendingMentionAgentId = null;
        Long replyToId = state.pendingReplyToAgentId;
        state.pendingReplyToAgentId = null;
        if (mentionId != null) {
            state.passedAgents.remove(mentionId);
        }
        List<Agent> candidates = all.stream()
                .filter(a -> !state.passedAgents.contains(a.getId()))
                .toList();
        if (candidates.isEmpty()) {
            LogHelper.printLog(DiscussionEngine.class, "DiscussionEngine.advanceDiscussion", "ADVANCE_DISCUSSION", "全员PASS讨论收敛", "topicId={}", topic.getId());
            return tryConclude(topic.getId(), null, "CONVERGED", null);
        }
        Map<Long, Long> speakCounts = loadSpeakCounts(topic.getId(), group);
        // Moderator 主持人（Phase 2）：@提及短路主持人（用户指名优先级最高）；判定失败回退评分调度
        Long preferredAgentId = null;
        String guidance = null;
        if (mentionId == null && moderatorService.isEnabled()) {
            ModeratorService.Decision decision = moderatorService.decide(topic, all, speakCounts);
            if (decision != null) {
                if (decision.wantsConclude()) {
                    LogHelper.printLog(DiscussionEngine.class, "DiscussionEngine.advanceDiscussion", "ADVANCE_DISCUSSION", "Moderator判断可收束", "topicId={}", topic.getId());
                    return tryConclude(topic.getId(), null, "MODERATOR", null);
                }
                preferredAgentId = decision.nextSpeakerId();
                guidance = decision.guidance();
            }
        }
        MessageContext ctx = MessageContext.builder()
                .groupId(groupId)
                .topicId(topic.getId())
                .content("")
                .mentionedAgentIds(mentionId == null ? List.of() : List.of(mentionId))
                .repliedToAgentId(replyToId)
                .speakCounts(speakCounts)
                .build();
        SpeakResult result = speakOnce(candidates, all, ctx, preferredAgentId, guidance);
        switch (result.outcome()) {
            case CONCLUDED, FAILED -> {
                return true;
            }
            case SPOKE -> state.passedAgents.clear();
            case PASSED -> {
                state.passedAgents.add(result.agent().getId());
                if (state.passedAgents.size() >= convergePassCount) {
                    LogHelper.printLog(DiscussionEngine.class, "DiscussionEngine.advanceDiscussion", "ADVANCE_DISCUSSION", "连续PASS讨论收敛",
                            "passCount={} topicId={}", state.passedAgents.size(), topic.getId());
                    return tryConclude(topic.getId(), null, "CONVERGED", null);
                }
            }
            case SILENT -> {
                // 讨论态不应出现（空内容视为 PASS），防御性忽略
            }
        }
        return false;
    }

    /* ==================== 生成域：一次发言 ==================== */

    /**
     * 一次发言：按评分降序为降级链依次尝试（重试 1 次后接力下一个 Agent）。
     * <p>讨论态（topicId != null）：空内容/[[PASS]] 视为跳过本轮；[[CONCLUDE]] 触发收束。
     * <p>闲聊态：空内容视为选择不发言。无 PASS 过滤，候选即群内全量成员。
     */
    private SpeakResult speakOnce(List<Agent> agents, MessageContext ctx) {
        return speakOnce(agents, agents, ctx, null, null);
    }

    /**
     * 一次发言（可选 Moderator 增强）：主持人指定的发言者提到降级链首位，
     * 引导语注入 system prompt 尾部（不入库不广播）。
     * <p>members 为群内全量成员（含已 PASS 者），用于上下文中的成员名单注入。
     */
    @Event(eventCode = "DiscussionEngine.speakOnce", eventName = "发言")
    public SpeakResult speakOnce(List<Agent> candidates, List<Agent> members, MessageContext ctx,
                                  Long preferredAgentId, String moderatorGuidance) {
        boolean topicMode = ctx.getTopicId() != null;
        if (candidates.isEmpty()) {
            LogHelper.printWarnLog(DiscussionEngine.class, "DiscussionEngine.speakOnce", "SPEAK_ONCE", "无候选Agent", "groupId={}", ctx.getGroupId());
            return new SpeakResult(SpeakOutcome.FAILED, null);
        }
        List<SpeakerScheduler.ScoredAgent> ranked = speakerScheduler.rank(candidates, ctx);
        ranked = promotePreferred(ranked, preferredAgentId);
        LogHelper.printLog(DiscussionEngine.class, "DiscussionEngine.speakOnce", "SPEAK_ONCE", "发言评分结果",
                "groupId={} topicId={} 排序={}", ctx.getGroupId(), ctx.getTopicId(),
                ranked.stream().map(s -> s.agent().getName() + "(" + s.score() + "," + s.reason() + ")").toList());
        for (int i = 0; i < ranked.size(); i++) {
            SpeakerScheduler.ScoredAgent scored = ranked.get(i);
            Agent agent = scored.agent();
            LogHelper.printLog(DiscussionEngine.class, "DiscussionEngine.speakOnce", "SPEAK_ONCE", "选中发言Agent",
                    "name={} score={} reason={} 降级链位置={}/{}",
                    agent.getName(), scored.score(), scored.reason(), i + 1, ranked.size());
            eventPublisher.publish(new AgentSelected(ctx.getGroupId(), ctx.getTopicId(),
                    agent.getId(), agent.getName(), scored.reason(), scored.score()));
            pushTyping(ctx.getGroupId(), agent, true);
            try {
                ContextBuilder.LlmContext llmCtx = contextBuilder.build(
                        agent, members, ctx.getGroupId(), ctx.getTopicId(), messageAssembler::resolveSenderName);
                if (moderatorGuidance != null && !moderatorGuidance.isBlank()) {
                    llmCtx = new ContextBuilder.LlmContext(
                            llmCtx.systemPrompt() + "\n\n主持人提示：" + moderatorGuidance,
                            llmCtx.turns());
                }
                StreamEmitter emitter = streamingEnabled ? new StreamEmitter(ctx.getGroupId(), agent) : null;
                String content;
                try {
                    LogHelper.printLog(DiscussionEngine.class, "DiscussionEngine.speakOnce", "SPEAK_ONCE", "LLM调用开始", "agent={} topicId={}", agent.getName(), ctx.getTopicId());
                    long llmStart = System.currentTimeMillis();
                    content = chatWithRetry(agent, llmCtx, emitter);
                    LogHelper.printLog(DiscussionEngine.class, "DiscussionEngine.speakOnce", "SPEAK_ONCE", "LLM调用完成", "agent={} topicId={} 耗时={}ms",
                            agent.getName(), ctx.getTopicId(), System.currentTimeMillis() - llmStart);
                } catch (Exception e) {
                    if (emitter != null) {
                        emitter.abort();
                    }
                    throw e;
                }
                boolean blank = content == null || content.isBlank();
                if (topicMode && (blank || content.contains(ContextBuilder.PASS_MARKER))) {
                    // 空内容等同 PASS：Agent 本轮无新观点（不入库不广播）
                    if (emitter != null) {
                        emitter.abort();
                    }
                    LogHelper.printLog(DiscussionEngine.class, "DiscussionEngine.speakOnce", "SPEAK_ONCE", "Agent PASS本轮",
                            "agent={} topicId={} 空内容={}", agent.getName(), ctx.getTopicId(), blank);
                    return new SpeakResult(SpeakOutcome.PASSED, agent);
                }
                if (blank) {
                    if (emitter != null) {
                        emitter.abort();
                    }
                    LogHelper.printLog(DiscussionEngine.class, "DiscussionEngine.speakOnce", "SPEAK_ONCE", "Agent返回空内容不发言",
                            "agent={} groupId={}", agent.getName(), ctx.getGroupId());
                    return new SpeakResult(SpeakOutcome.SILENT, agent);
                }
                boolean wantsConclude = topicMode && content.contains(ContextBuilder.CONCLUDE_MARKER);
                content = ContextBuilder.stripMarkers(content);
                if (!content.isBlank()) {
                    // 重复内容检查：handleChat 的 maxReplies 循环或讨论推进连续选中同一 Agent 时，
                    // LLM 在上下文几乎不变下可能返回相同内容，此处拦截避免重复入库
                    Optional<GroupMessage> lastOpt = messageRepository.findLastByGroupId(ctx.getGroupId());
                    if (lastOpt.isPresent()) {
                        GroupMessage last = lastOpt.get();
                        if (last.getSenderId() != null && last.getSenderId().equals(agent.getId())
                                && last.getSenderType() == SenderType.AGENT
                                && content.equals(last.getContent())) {
                            LogHelper.printLog(DiscussionEngine.class, "DiscussionEngine.speakOnce", "SPEAK_ONCE",
                                    "跳过重复内容不入库",
                                    "agent={} groupId={}", agent.getName(), ctx.getGroupId());
                            if (emitter != null) {
                                emitter.abort();
                            }
                            return new SpeakResult(SpeakOutcome.SILENT, agent);
                        }
                    }
                    GroupMessage reply = saveAgentMessage(ctx, agent, content);
                    LogHelper.printLog(DiscussionEngine.class, "DiscussionEngine.speakOnce", "SPEAK_ONCE", "Agent发言已入库广播",
                            "agent={} messageId={} 长度={} 流式={}",
                            agent.getName(), reply.getId(), content.length(),
                            emitter != null && emitter.emitted);
                    if (emitter != null && emitter.emitted) {
                        // 流式路径：COMPLETE 携带正式消息体替换前端半成品气泡（不再推 NEW_MESSAGE，避免重复）
                        groupBroadcastService.broadcast(ctx.getGroupId(), WsConstants.MESSAGE_COMPLETE, Map.of(
                                "streamId", emitter.streamId,
                                "message", messageAssembler.toDto(reply)));
                    } else {
                        groupBroadcastService.broadcast(ctx.getGroupId(), WsConstants.NEW_MESSAGE, messageAssembler.toDto(reply));
                    }
                    eventPublisher.publish(new MessageSent(reply.getId(), ctx.getGroupId(), ctx.getTopicId(),
                            agent.getId(), SenderType.AGENT.name(), content, null, List.of()));
                } else if (emitter != null) {
                    emitter.abort();
                }
                if (wantsConclude) {
                    LogHelper.printLog(DiscussionEngine.class, "DiscussionEngine.speakOnce", "SPEAK_ONCE", "检测到收束标记",
                            "agent={} topicId={}", agent.getName(), ctx.getTopicId());
                    boolean concluded = tryConclude(ctx.getTopicId(), null, "AGENT", agent.getId());
                    return new SpeakResult(concluded ? SpeakOutcome.CONCLUDED : SpeakOutcome.SPOKE, agent);
                }
                return new SpeakResult(SpeakOutcome.SPOKE, agent);
            } catch (Exception e) {
                // 降级路由：接力给下一个 Agent（失败 Agent 下一轮仍参与调度）
                Agent fallback = i + 1 < ranked.size() ? ranked.get(i + 1).agent() : null;
                LogHelper.printWarnLog(DiscussionEngine.class, "DiscussionEngine.speakOnce", "SPEAK_ONCE", "Agent调用失败降级",
                        "agent={} fallback={}", agent.getName(), (fallback == null ? "无" : fallback.getName()), e);
                eventPublisher.publish(new AgentFailed(ctx.getGroupId(), ctx.getTopicId(),
                        agent.getId(), agent.getName(), e.getMessage(),
                        fallback == null ? null : fallback.getId(),
                        fallback == null ? null : fallback.getName()));
            } finally {
                pushTyping(ctx.getGroupId(), agent, false);
            }
        }
        // 所有 Agent 都失败
        LogHelper.printWarnLog(DiscussionEngine.class, "DiscussionEngine.speakOnce", "SPEAK_ONCE", "所有Agent均失败无人发言",
                "groupId={} topicId={} 候选数={}", ctx.getGroupId(), ctx.getTopicId(), ranked.size());
        groupBroadcastService.broadcast(ctx.getGroupId(), WsConstants.ERROR, Map.of(
                "success", false,
                "errorCode", ErrorCode.ALL_AGENTS_FAILED.name(),
                "message", ErrorCode.ALL_AGENTS_FAILED.getDefaultMessage()));
        return new SpeakResult(SpeakOutcome.FAILED, null);
    }

    /** LLM 调用（失败重试 1 次）；流式模式下重试前废弃旧流、换新 streamId 重开 */
    @Event(eventCode = "CHAT", eventName = "调用LLM")
    public String chatWithRetry(Agent agent, ContextBuilder.LlmContext ctx, StreamEmitter emitter) {
        try {
            return emitter != null
                    ? llmService.chatStream(agent, ctx.systemPrompt(), ctx.turns(), emitter::onDelta)
                    : llmService.chat(agent, ctx.systemPrompt(), ctx.turns());
        } catch (Exception first) {
            LogHelper.printWarnLog(DiscussionEngine.class, "DiscussionEngine.chatWithRetry", "CHAT_WITH_RETRY", "LLM首次失败重试",
                    "agent={} 失败原因: {}", agent.getName(), first.getMessage());
            if (emitter != null) {
                emitter.reset();
                return llmService.chatStream(agent, ctx.systemPrompt(), ctx.turns(), emitter::onDelta);
            }
            return llmService.chat(agent, ctx.systemPrompt(), ctx.turns());
        }
    }

    /**
     * 单次流式发言会话：delta 经 {@link StreamMarkerGuard} 过滤后逐块广播；
     * emitted 标志决定失败/PASS 时是否需推 ABORT 让前端丢弃半成品气泡。
     */
    private class StreamEmitter {
        private final Long groupId;
        private final Agent agent;
        private String streamId = java.util.UUID.randomUUID().toString();
        private StreamMarkerGuard guard = new StreamMarkerGuard();
        private volatile boolean emitted;

        StreamEmitter(Long groupId, Agent agent) {
            this.groupId = groupId;
            this.agent = agent;
        }

        void onDelta(String chunk) {
            String safe = guard.onChunk(chunk);
            if (safe.isEmpty()) {
                return;
            }
            emitted = true;
            groupBroadcastService.broadcast(groupId, WsConstants.MESSAGE_DELTA, Map.of(
                    "streamId", streamId,
                    "agentId", agent.getId(),
                    "agentName", agent.getName(),
                    "delta", safe));
        }

        /** 废弃当前流（前端丢弃半成品气泡）；未发过 delta 则无需通知 */
        void abort() {
            if (emitted) {
                groupBroadcastService.broadcast(groupId, WsConstants.MESSAGE_ABORT, Map.of("streamId", streamId));
            }
        }

        /** 重试前重置：废弃旧流，换新 streamId 重新开始 */
        void reset() {
            abort();
            streamId = java.util.UUID.randomUUID().toString();
            guard = new StreamMarkerGuard();
            emitted = false;
        }
    }

    /** Moderator 指定的发言者提到降级链首位（不在候选内则忽略，其余顺序不变） */
    @Event(eventCode = "PROMOTE_PREFERRED", eventName = "Moderator指定发言者提前")
    public List<SpeakerScheduler.ScoredAgent> promotePreferred(
            List<SpeakerScheduler.ScoredAgent> ranked, Long preferredAgentId) {
        if (preferredAgentId == null) {
            return ranked;
        }
        SpeakerScheduler.ScoredAgent preferred = ranked.stream()
                .filter(s -> preferredAgentId.equals(s.agent().getId()))
                .findFirst().orElse(null);
        if (preferred == null || ranked.get(0) == preferred) {
            return ranked;
        }
        List<SpeakerScheduler.ScoredAgent> reordered = new java.util.ArrayList<>(ranked.size());
        reordered.add(preferred);
        ranked.stream().filter(s -> s != preferred).forEach(reordered::add);
        LogHelper.printLog(DiscussionEngine.class, "DiscussionEngine.promotePreferred", "PROMOTE_PREFERRED", "Moderator指定发言者提前", "agent={}", preferred.agent().getName());
        return reordered;
    }

    /* ==================== 私有辅助 ==================== */

    private GroupMessage saveAgentMessage(MessageContext ctx, Agent agent, String content) {
        GroupMessage reply = new GroupMessage();
        reply.setChatGroupId(ctx.getGroupId());
        reply.setTopicId(ctx.getTopicId());
        reply.setSenderId(agent.getId());
        reply.setSenderType(SenderType.AGENT);
        reply.setMessageType(MessageType.TEXT);
        reply.setContent(content);
        messageRepository.save(reply);
        return reply;
    }

    private void pushTyping(Long groupId, Agent agent, boolean typing) {
        groupBroadcastService.broadcast(groupId, WsConstants.AGENT_TYPING, Map.of(
                "groupId", groupId,
                "agentId", agent.getId(),
                "agentName", agent.getName(),
                "isTyping", typing));
    }

    /** Topic 内各成员 Agent 已发言次数（重启后从库恢复） */
    private Map<Long, Long> loadSpeakCounts(Long topicId, Group group) {
        Map<Long, Long> counts = new HashMap<>();
        for (Long agentId : group.memberAgentIds()) {
            counts.put(agentId, messageRepository.countByTopicIdAndSender(topicId, agentId, SenderType.AGENT));
        }
        return counts;
    }

    /** 讨论态发言间隔（毫秒，随机拟人节奏）；配置为 0 时无等待（测试用） */
    private long pace() {
        long lo = Math.max(0, Math.min(paceMinMs, paceMaxMs));
        long hi = Math.max(0, Math.max(paceMinMs, paceMaxMs));
        if (hi <= 0) {
            return 0;
        }

        long delayMs = ThreadLocalRandom.current().nextLong(lo, hi + 1);
        LogHelper.printLog(DiscussionEngine.class,
                "DiscussionEngine.pace",
                "PACE", "随机等待",
                "lo={} hi={}, 延长时间（单位:m）:{}", lo, hi, delayMs / 1000);
        return delayMs;
    }

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
            LogHelper.printWarnLog(DiscussionEngine.class, "DiscussionEngine.safeRun", "SAFE_RUN", "异步任务异常退出", "task={} groupId={}", taskName, groupId, e);
        }
    }
}
