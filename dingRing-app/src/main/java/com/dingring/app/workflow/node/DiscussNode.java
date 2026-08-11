package com.dingring.app.workflow.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.dingring.app.orchestrator.ContextBuilder;
import com.dingring.app.orchestrator.MessageContext;
import com.dingring.app.orchestrator.SpeakerScheduler;
import com.dingring.app.orchestrator.StreamMarkerGuard;
import com.dingring.app.orchestrator.Terminator;
import com.dingring.app.service.MessageAssembler;
import com.dingring.common.constant.WsConstants;
import com.dingring.common.exception.ErrorCode;
import com.dingring.common.util.JsonHelper;
import com.dingring.common.util.LogHelper;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.agent.AgentRepository;
import com.dingring.domain.event.AgentFailed;
import com.dingring.domain.event.AgentSelected;
import com.dingring.domain.event.MessageSent;
import com.dingring.domain.group.Group;
import com.dingring.domain.group.GroupMessage;
import com.dingring.domain.group.GroupRepository;
import com.dingring.domain.group.MessageRepository;
import com.dingring.domain.group.MessageType;
import com.dingring.domain.group.SenderType;
import com.dingring.domain.service.AgentSpeakerService;
import com.dingring.domain.service.DomainEventPublisher;
import com.dingring.domain.service.GroupBroadcastService;
import com.dingring.domain.workflow.StateKeys;
import com.dingring.infrastructure.aop.Event;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 讨论态推进节点：多 Agent 讨论中推进一轮发言。
 * <p>迁移自 {@link com.dingring.app.orchestrator.DiscussionEngine#advanceDiscussion} + {@code speakOnce} 的讨论分支。
 * <p>Phase C 简化（方案 6.4 决策 2）：每次 advance 只推进一轮发言，由 DiscussionEngine 主循环决定
 * 是否继续推进（CONVERGE 立即推进 / DIVERGE pace 后推进 / WAIT 等用户 / CONCLUDE_PROPOSED 等确认 / CONCLUDE 退出）。
 * <p>发言结果 → 讨论模式映射：
 * <ul>
 *   <li>SPOKE（真实发言）→ CONVERGE：清空 passedAgents，重置 divergeRounds</li>
 *   <li>PASSED（空内容/[[PASS]]/重复内容）→ DIVERGE：累积 passedAgents，divergeRounds+1；
 *       达 maxDivergeRounds 触发收束（CONVERGED）</li>
 *   <li>[[CONCLUDE]] 标记 → CONCLUDE_PROPOSED：等用户确认（triggeredBy=AGENT）</li>
 *   <li>所有候选失败 → CONCLUDE（triggeredBy=FAILED）</li>
 *   <li>熔断/候选空 → CONCLUDE（triggeredBy=MAX_ROUNDS/CONVERGED）</li>
 * </ul>
 * <p>降级链：单个 Agent LLM 调用失败时接力下一个候选，所有候选失败才标记 FAILED。
 */
@Slf4j
@Component("discussHandler")
@RequiredArgsConstructor
public class DiscussNode implements NodeAction {

    private final GroupRepository groupRepository;
    private final AgentRepository agentRepository;
    private final MessageRepository messageRepository;
    private final SpeakerScheduler speakerScheduler;
    private final ContextBuilder contextBuilder;
    private final MessageAssembler messageAssembler;
    private final AgentSpeakerService agentSpeakerService;
    private final DomainEventPublisher eventPublisher;
    private final GroupBroadcastService groupBroadcastService;
    private final Terminator terminator;

    /** 流式输出开关（与 ChatNode / DiscussionEngine 保持一致） */
    @org.springframework.beans.factory.annotation.Value("${dingring.streaming.enabled:false}")
    private boolean streamingEnabled;

    /** 发言结果枚举（内部用，决定 discussMode 写入） */
    private enum SpeakOutcome { SPOKE, PASSED, CONCLUDE_PROPOSED, FAILED }

    /**
     * 推进一轮讨论发言。
     *
     * @param state OverAllState，包含 topicId/groupId/passedAgentIds/divergeRounds/maxDivergeRounds/mentionedAgentIds
     * @return 状态更新：discussMode + triggeredBy + passedAgentIds/divergeRounds/speakerAgentId/concluderAgentId/concluded
     */
    @Override
    @Event(eventCode = "DISCUSS_NODE", eventName = "讨论推进节点")
    public Map<String, Object> apply(OverAllState state) {
        Long groupId = state.<Long>value(StateKeys.GROUP_ID).orElse(null);
        Long topicId = state.<Long>value(StateKeys.TOPIC_ID).orElse(null);
        List<Long> passedAgentIds = state.value(StateKeys.PASSED_AGENT_IDS, List.<Long>of());
        int divergeRounds = state.value(StateKeys.DIVERGE_ROUNDS, 0);
        int maxDivergeRounds = state.value("maxDivergeRounds", 3);
        List<Long> mentionedAgentIds = state.value(StateKeys.MENTIONED_AGENT_IDS, List.<Long>of());
        boolean mentionHandled = state.value(StateKeys.MENTION_HANDLED, false);
        Long repliedToAgentId = state.<Long>value(StateKeys.REPLIED_TO_AGENT_ID).orElse(null);
        // 话题重启时 EnsureTopicNode 注入的用户历史表现提示（无则为空）
        String userHistoryHint = state.value(StateKeys.USER_HISTORY_HINT, "");

        // 用 HashMap 而非 Map.of：topicId/groupId 可能为 null（如未建题被误路由时），
        // Map.of 遇到 null 值会抛 NPE，反而遮蔽下方 groupId/topicId 的防御校验
        Map<String, Object> logMap = new HashMap<>();
        logMap.put("groupId", groupId);
        logMap.put("topicId", topicId);
        logMap.put("passedAgentIds", passedAgentIds);
        logMap.put("divergeRounds", divergeRounds);
        logMap.put("maxDivergeRounds", maxDivergeRounds);
        logMap.put("mentionedAgentIds", mentionedAgentIds);
        logMap.put("mentionHandled", mentionHandled);
        LogHelper.printLog(DiscussNode.class, "DiscussNode.apply", "DISCUSS_NODE", "讨论推进开始",
                "request={}", JsonHelper.mapToJsonStr(logMap));

        if (groupId == null || topicId == null) {
            throw new IllegalStateException("DiscussNode 缺少必要参数 groupId/topicId");
        }

        // 熔断兜底：达最大轮次自动收束
        if (terminator.reachedMaxRounds(topicId)) {
            LogHelper.printLog(DiscussNode.class, "DiscussNode.apply", "DISCUSS_NODE", "达到最大轮次自动收束",
                    "topicId={}", topicId);
            return concludeResult(topicId, "MAX_ROUNDS", null);
        }

        Group group = groupRepository.findById(groupId).orElse(null);
        if (group == null) {
            LogHelper.printWarnLog(DiscussNode.class, "DiscussNode.apply", "DISCUSS_NODE", "群不存在触发收束",
                    "groupId={} topicId={}", groupId, topicId);
            return concludeResult(topicId, "FAILED", null);
        }
        List<Agent> all = agentRepository.findByIds(group.memberAgentIds());
        if (all.isEmpty()) {
            LogHelper.printWarnLog(DiscussNode.class, "DiscussNode.apply", "DISCUSS_NODE", "无成员Agent收束",
                    "groupId={}", groupId);
            return concludeResult(topicId, "FAILED", null);
        }

        // @提及的 Agent 保证一次发言权：仅在豁免未消费（mentionHandled=false）时从 passedAgentIds 移除一次，
        // 本轮结束即消费豁免，后续轮次按正常 PASS/轮转逻辑处理
        boolean mentionExempted = false;
        Set<Long> passedSet = new HashSet<>(passedAgentIds);
        Long mentionId = (mentionedAgentIds != null && !mentionedAgentIds.isEmpty())
                ? mentionedAgentIds.get(0) : null;
        if (mentionId != null && !mentionHandled) {
            passedSet.remove(mentionId);
            mentionExempted = true;
            LogHelper.printLog(DiscussNode.class, "DiscussNode.apply", "DISCUSS_NODE", "@提及豁免一次PASS",
                    "topicId={} mentionId={}", topicId, mentionId);
        }

        // 过滤候选：排除已 PASS 的
        List<Agent> candidates = all.stream()
                .filter(a -> !passedSet.contains(a.getId()))
                .toList();
        if (candidates.isEmpty()) {
            LogHelper.printLog(DiscussNode.class, "DiscussNode.apply", "DISCUSS_NODE", "全员PASS讨论收敛",
                    "topicId={}", topicId);
            return concludeResult(topicId, "CONVERGED", null);
        }

        // 发言（降级链：失败接力下一个）
        Map<Long, Long> speakCounts = loadSpeakCounts(topicId, group);
        MessageContext ctx = MessageContext.builder()
                .groupId(groupId)
                .topicId(topicId)
                .content("")
                .mentionedAgentIds(mentionId == null ? List.of() : List.of(mentionId))
                .repliedToAgentId(repliedToAgentId)
                .speakCounts(speakCounts)
                .build();

        SpeakResult speakResult = speakOnce(candidates, all, ctx, userHistoryHint);

        // 根据发言结果写 discussMode
        Map<String, Object> result = new HashMap<>();
        switch (speakResult.outcome) {
            case SPOKE -> {
                // 真实发言：清空 passedAgents，重置 divergeRounds，收敛模式
                result.put(StateKeys.DISCUSS_MODE, StateKeys.MODE_CONVERGE);
                result.put(StateKeys.PASSED_AGENT_IDS, List.of());
                result.put(StateKeys.DIVERGE_ROUNDS, 0);
                result.put(StateKeys.SPEAKER_AGENT_ID, speakResult.agent.getId());
            }
            case PASSED -> {
                // PASS：累积 passedAgents，发散模式
                Set<Long> newPassed = new HashSet<>(passedSet);
                newPassed.add(speakResult.agent.getId());
                int newDivergeRounds = divergeRounds + 1;
                if (newDivergeRounds >= maxDivergeRounds) {
                    // 发散达上限：自动收束
                    LogHelper.printLog(DiscussNode.class, "DiscussNode.apply", "DISCUSS_NODE", "发散达上限自动收束",
                            "topicId={} divergeRounds={}/{}", topicId, newDivergeRounds, maxDivergeRounds);
                    return concludeResult(topicId, "CONVERGED", null);
                }
                result.put(StateKeys.DISCUSS_MODE, StateKeys.MODE_DIVERGE);
                result.put(StateKeys.PASSED_AGENT_IDS, new ArrayList<>(newPassed));
                result.put(StateKeys.DIVERGE_ROUNDS, newDivergeRounds);
                result.put(StateKeys.SPEAKER_AGENT_ID, speakResult.agent.getId());
            }
            case CONCLUDE_PROPOSED -> {
                // Agent 提议收束：等用户确认
                result.put(StateKeys.DISCUSS_MODE, StateKeys.MODE_CONCLUDE_PROPOSED);
                result.put(StateKeys.TRIGGERED_BY, "AGENT");
                result.put(StateKeys.CONCLUDER_AGENT_ID, speakResult.agent.getId());
                result.put(StateKeys.SPEAKER_AGENT_ID, speakResult.agent.getId());
            }
            case FAILED -> {
                // 所有候选失败：收束
                return concludeResult(topicId, "FAILED", null);
            }
        }
        // 记录 @提及一次性发言权是否已消费（本轮被豁免即视为消费；未被豁免则透传原值）
        result.put(StateKeys.MENTION_HANDLED, mentionExempted ? Boolean.TRUE : mentionHandled);
        return result;
    }

    /**
     * 一次发言（降级链）：按评分降序依次尝试，失败接力下一个，全部失败才返回 FAILED。
     * <p>讨论态判定：空内容/[[PASS]]/重复内容 → PASSED；[[CONCLUDE]] → CONCLUDE_PROPOSED；正常 → SPOKE。
     *
     * @param userHistoryHint 话题重启的用户历史表现提示（无则为空）
     */
    private SpeakResult speakOnce(List<Agent> candidates, List<Agent> members, MessageContext ctx,
                                  String userHistoryHint) {
        List<SpeakerScheduler.ScoredAgent> ranked = speakerScheduler.rank(candidates, ctx);
        LogHelper.printLog(DiscussNode.class, "DiscussNode.speakOnce", "DISCUSS_NODE", "发言评分结果",
                "groupId={} topicId={} 排序={}", ctx.getGroupId(), ctx.getTopicId(),
                ranked.stream().map(s -> s.agent().getName() + "(" + s.score() + "," + s.reason() + ")").toList());

        for (int i = 0; i < ranked.size(); i++) {
            SpeakerScheduler.ScoredAgent scored = ranked.get(i);
            Agent agent = scored.agent();
            LogHelper.printLog(DiscussNode.class, "DiscussNode.speakOnce", "DISCUSS_NODE", "选中发言Agent",
                    "name={} score={} reason={} 降级链位置={}/{}",
                    agent.getName(), scored.score(), scored.reason(), i + 1, ranked.size());
            eventPublisher.publish(new AgentSelected(ctx.getGroupId(), ctx.getTopicId(),
                    agent.getId(), agent.getName(), scored.reason(), scored.score()));
            pushTyping(ctx.getGroupId(), agent, true);
            StreamEmitter emitter = streamingEnabled ? new StreamEmitter(ctx.getGroupId(), agent) : null;
            try {
                // 讨论态上下文（方案 6.3.6）：观点摘要列表 + 近期窗口，替代全量 200 条窗口
                ContextBuilder.LlmContext llmCtx = contextBuilder.buildForDiscuss(
                        agent, ctx.getGroupId(), ctx.getTopicId(),
                        messageAssembler::resolveSenderName, userHistoryHint);

                // 构建 ReactAgent 上下文（群记忆/用户画像/知识由 Hook 动态注入）
                Map<String, Object> context = new HashMap<>();
                context.put("groupId", ctx.getGroupId());
                context.put("topicId", ctx.getTopicId());
                context.put("userId", 1L);  // 当前单用户系统默认 ID
                context.put("speakerAgentId", agent.getId());
                // RAG 检索词：以触发调度的消息为查询（RagInjectionHook 读取）
                context.put("ragQuery", ctx.getContent());

                // Agent 发言（失败重试 1 次）；流式模式下重试前废弃旧流、换新 streamId 重开
                AgentSpeakerService.AgentResult result;
                long llmStart = System.currentTimeMillis();
                try {
                    result = streamingEnabled
                            ? agentSpeakerService.callStream(agent, llmCtx.systemPrompt(), llmCtx.turns(),
                                    AgentSpeakerService.ToolSet.DISCUSS, context, emitter::onDelta)
                            : agentSpeakerService.call(agent, llmCtx.systemPrompt(), llmCtx.turns(),
                                    AgentSpeakerService.ToolSet.DISCUSS, context);
                } catch (Exception first) {
                    LogHelper.printWarnLog(DiscussNode.class, "DiscussNode.speakOnce", "DISCUSS_NODE", "Agent首次失败重试",
                            "agent={} 失败原因: {}", agent.getName(), first.getMessage());
                    if (emitter != null) {
                        emitter.reset();
                    }
                    result = streamingEnabled
                            ? agentSpeakerService.callStream(agent, llmCtx.systemPrompt(), llmCtx.turns(),
                                    AgentSpeakerService.ToolSet.DISCUSS, context, emitter::onDelta)
                            : agentSpeakerService.call(agent, llmCtx.systemPrompt(), llmCtx.turns(),
                                    AgentSpeakerService.ToolSet.DISCUSS, context);
                }
                String content = result.content();
                LogHelper.printLog(DiscussNode.class, "DiscussNode.speakOnce", "DISCUSS_NODE", "LLM调用完成",
                        "agent={} topicId={} 耗时={}ms",
                        agent.getName(), ctx.getTopicId(), System.currentTimeMillis() - llmStart);

                boolean blank = content == null || content.isBlank();
                // 空内容或 [[PASS]]：视为跳过本轮
                if (blank || content.contains(ContextBuilder.PASS_MARKER)) {
                    if (emitter != null) {
                        emitter.abort();
                    }
                    LogHelper.printLog(DiscussNode.class, "DiscussNode.speakOnce", "DISCUSS_NODE", "Agent PASS本轮",
                            "agent={} topicId={} 空内容={}", agent.getName(), ctx.getTopicId(), blank);
                    return new SpeakResult(SpeakOutcome.PASSED, agent);
                }

                boolean wantsConclude = content.contains(ContextBuilder.CONCLUDE_MARKER);
                content = ContextBuilder.stripMarkers(content);
                if (content.isBlank()) {
                    // 剥离标记后为空，视为 PASS
                    if (emitter != null) {
                        emitter.abort();
                    }
                    return new SpeakResult(SpeakOutcome.PASSED, agent);
                }

                // 重复内容检查：与该 Agent最近一条消息相同则跳过
                Optional<GroupMessage> lastOpt = messageRepository.findLastByGroupId(ctx.getGroupId());
                if (lastOpt.isPresent() && isDuplicate(lastOpt.get(), agent, content)) {
                    LogHelper.printLog(DiscussNode.class, "DiscussNode.speakOnce", "DISCUSS_NODE", "跳过重复内容",
                            "agent={} groupId={}", agent.getName(), ctx.getGroupId());
                    if (emitter != null) {
                        emitter.abort();
                    }
                    return new SpeakResult(SpeakOutcome.PASSED, agent);
                }

                // 入库 + 广播
                GroupMessage reply = saveAgentMessage(ctx.getGroupId(), ctx.getTopicId(), agent, content);
                if (emitter != null && emitter.emitted) {
                    groupBroadcastService.broadcast(ctx.getGroupId(), WsConstants.MESSAGE_COMPLETE, Map.of(
                            "streamId", emitter.streamId,
                            "message", messageAssembler.toDto(reply)));
                } else {
                    groupBroadcastService.broadcast(ctx.getGroupId(), WsConstants.NEW_MESSAGE, messageAssembler.toDto(reply));
                }
                eventPublisher.publish(new MessageSent(reply.getId(), ctx.getGroupId(), ctx.getTopicId(),
                        agent.getId(), SenderType.AGENT.name(), content, null, List.of()));
                LogHelper.printLog(DiscussNode.class, "DiscussNode.speakOnce", "DISCUSS_NODE", "Agent发言已入库广播",
                        "agent={} messageId={} 长度={} 流式={} wantsConclude={}",
                        agent.getName(), reply.getId(), content.length(),
                        emitter != null && emitter.emitted, wantsConclude);

                if (wantsConclude) {
                    return new SpeakResult(SpeakOutcome.CONCLUDE_PROPOSED, agent);
                }
                return new SpeakResult(SpeakOutcome.SPOKE, agent);
            } catch (Exception e) {
                if (emitter != null) {
                    emitter.abort();
                }
                Agent fallback = i + 1 < ranked.size() ? ranked.get(i + 1).agent() : null;
                LogHelper.printWarnLog(DiscussNode.class, "DiscussNode.speakOnce", "DISCUSS_NODE", "Agent调用失败降级",
                        "agent={} fallback={}", agent.getName(),
                        fallback == null ? "无" : fallback.getName(), e);
                eventPublisher.publish(new AgentFailed(ctx.getGroupId(), ctx.getTopicId(),
                        agent.getId(), agent.getName(), e.getMessage(),
                        fallback == null ? null : fallback.getId(),
                        fallback == null ? null : fallback.getName()));
            } finally {
                pushTyping(ctx.getGroupId(), agent, false);
            }
        }

        // 所有候选都失败
        LogHelper.printWarnLog(DiscussNode.class, "DiscussNode.speakOnce", "DISCUSS_NODE", "所有Agent均失败无人发言",
                "groupId={} topicId={} 候选数={}", ctx.getGroupId(), ctx.getTopicId(), ranked.size());
        groupBroadcastService.broadcast(ctx.getGroupId(), WsConstants.ERROR, Map.of(
                "success", false,
                "errorCode", ErrorCode.ALL_AGENTS_FAILED.name(),
                "message", ErrorCode.ALL_AGENTS_FAILED.getDefaultMessage()));
        return new SpeakResult(SpeakOutcome.FAILED, null);
    }

    /** 构造收束结果（discussMode=CONCLUDE, concluded=true） */
    private Map<String, Object> concludeResult(Long topicId, String triggeredBy, Long concluderAgentId) {
        LogHelper.printLog(DiscussNode.class, "DiscussNode.concludeResult", "DISCUSS_NODE",
                "讨论收敛触发收束", "topicId={} triggeredBy={} concluderAgentId={}",
                topicId, triggeredBy, concluderAgentId);
        Map<String, Object> result = new HashMap<>();
        result.put(StateKeys.DISCUSS_MODE, StateKeys.MODE_CONCLUDE);
        result.put(StateKeys.TRIGGERED_BY, triggeredBy);
        result.put(StateKeys.CONCLUDED, true);
        if (concluderAgentId != null) {
            result.put(StateKeys.CONCLUDER_AGENT_ID, concluderAgentId);
        }
        return result;
    }

    private boolean isDuplicate(GroupMessage last, Agent agent, String content) {
        return last.getSenderId() != null
                && last.getSenderId().equals(agent.getId())
                && last.getSenderType() == SenderType.AGENT
                && content.equals(last.getContent());
    }

    private GroupMessage saveAgentMessage(Long groupId, Long topicId, Agent agent, String content) {
        GroupMessage reply = new GroupMessage();
        reply.setChatGroupId(groupId);
        reply.setTopicId(topicId);
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

    /** 发言结果 */
    private record SpeakResult(SpeakOutcome outcome, Agent agent) {}

    /**
     * 单次流式发言会话：delta 经 {@link StreamMarkerGuard} 过滤后逐块广播；
     * emitted 标志决定失败/PASS 时是否需推 ABORT 让前端丢弃半成品气泡。
     * <p>与 ChatNode.StreamEmitter 逻辑一致，Phase C 完成后可提取为公共类复用。
     */
    private class StreamEmitter {
        private final Long groupId;
        private final Agent agent;
        private String streamId = UUID.randomUUID().toString();
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

        void abort() {
            if (emitted) {
                groupBroadcastService.broadcast(groupId, WsConstants.MESSAGE_ABORT, Map.of("streamId", streamId));
            }
        }

        void reset() {
            abort();
            streamId = UUID.randomUUID().toString();
            guard = new StreamMarkerGuard();
            emitted = false;
        }
    }
}
