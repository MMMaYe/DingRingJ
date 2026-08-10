package com.dingring.app.workflow.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.dingring.app.orchestrator.ContextBuilder;
import com.dingring.app.orchestrator.MessageContext;
import com.dingring.app.orchestrator.SpeakerScheduler;
import com.dingring.app.orchestrator.StreamMarkerGuard;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 闲聊应答节点：单 Agent 闲聊回复（无活跃话题场景）。
 * <p>迁移自 {@link com.dingring.app.orchestrator.DiscussionEngine#handleChat} + {@code speakOnce} 的闲聊分支。
 * <p>设计要点（方案 6.3.2 简化 + 6.4 决策）：
 * <ul>
 *   <li>Phase C 简化：每次用户消息只回复一次（移除 autoReplies 循环，由 DiscussionEngine 多次调用 advance 实现）</li>
 *   <li>统一走 SpeakerScheduler 评分选 Agent：@提及 +800 大权重加分通常优先，不再硬选；失败/空内容按评分顺延下一位</li>
 *   <li>chatBuffer 累积：由 DiscussionEngine 在 GroupState 中维护，每次调用 advance 时传入 inputs</li>
 *   <li>达阈值时设置 needProfileExtract=true，条件边分流到 profile-extract 节点</li>
 *   <li>流式输出（streaming.enabled=true）：逐块推送 MESSAGE_DELTA，完成推 MESSAGE_COMPLETE</li>
 *   <li>协作标记过滤：StreamMarkerGuard 防止 [[CONCLUDE]]/[[PASS]] 被拆到多个 chunk 泄漏</li>
 * </ul>
 * <p>注意：闲聊态不处理 [[CONCLUDE]]/[[PASS]] 标记（仅讨论态有意义），直接剥离后入库。
 */
@Slf4j
@Component("chatHandler")
@RequiredArgsConstructor
public class ChatNode implements NodeAction {

    private final GroupRepository groupRepository;
    private final AgentRepository agentRepository;
    private final MessageRepository messageRepository;
    private final SpeakerScheduler speakerScheduler;
    private final ContextBuilder contextBuilder;
    private final MessageAssembler messageAssembler;
    private final AgentSpeakerService agentSpeakerService;
    private final DomainEventPublisher eventPublisher;
    private final GroupBroadcastService groupBroadcastService;

    /** 流式输出开关（从配置注入，与 DiscussionEngine 保持一致） */
    @org.springframework.beans.factory.annotation.Value("${dingring.streaming.enabled:false}")
    private boolean streamingEnabled;

    /**
     * 闲聊应答：选 Agent → 构建上下文 → LLM 调用 → 入库广播 → chatBuffer 累积。
     *
     * @param state OverAllState，包含 groupId/input/mentionedAgentIds/repliedToAgentId/chatBuffer
     * @return 状态更新：chatBuffer（更新后）、needProfileExtract（是否需触发画像提炼）
     */
    @Override
    @Event(eventCode = "CHAT_NODE", eventName = "闲聊应答节点")
    public Map<String, Object> apply(OverAllState state) {
        Long groupId = state.<Long>value(StateKeys.GROUP_ID).orElse(null);
        String input = state.value(StateKeys.INPUT, "");
        List<Long> mentionedAgentIds = state.value(StateKeys.MENTIONED_AGENT_IDS, List.<Long>of());
        Long repliedToAgentId = state.<Long>value(StateKeys.REPLIED_TO_AGENT_ID).orElse(null);
        int chatBuffer = state.value(StateKeys.CHAT_BUFFER, 0);
        int profileThreshold = state.value("profileExtractThreshold", 15);

        // 用 HashMap 而非 Map.of：groupId 可能为 null（防御性日志不应在入口先抛 NPE）
        Map<String, Object> logMap = new HashMap<>();
        logMap.put("groupId", groupId);
        logMap.put("inputLen", input == null ? 0 : input.length());
        logMap.put("mentionedCount", mentionedAgentIds.size());
        logMap.put("chatBuffer", chatBuffer);
        logMap.put("profileThreshold", profileThreshold);
        LogHelper.printLog(ChatNode.class, "ChatNode.apply", "CHAT_NODE", "闲聊应答开始",
                "request={}", JsonHelper.mapToJsonStr(logMap));

        if (groupId == null) {
            throw new IllegalStateException("ChatNode 缺少必要参数 groupId");
        }

        Group group = groupRepository.findById(groupId).orElse(null);
        if (group == null) {
            throw new IllegalStateException("群不存在: " + groupId);
        }
        List<Agent> members = agentRepository.findByIds(group.memberAgentIds());
        if (members.isEmpty()) {
            LogHelper.printWarnLog(ChatNode.class, "ChatNode.apply", "CHAT_NODE", "无成员Agent跳过", "groupId={}", groupId);
            return Map.of();
        }

        // 选 Agent：统一走调度评分（@提及 +800 大权重加分通常优先，但不再硬选首个）
        List<SpeakerScheduler.ScoredAgent> ranked = rankSpeakers(members, mentionedAgentIds, groupId);
        if (ranked.isEmpty()) {
            LogHelper.printWarnLog(ChatNode.class, "ChatNode.apply", "CHAT_NODE", "无可用发言Agent跳过",
                    "groupId={} 成员数={} mentionedCount={}", groupId, members.size(), mentionedAgentIds.size());
            return Map.of();
        }
        LogHelper.printLog(ChatNode.class, "ChatNode.apply", "CHAT_NODE", "候选Agent排序完成",
                "groupId={} 排序={}", groupId, ranked.stream()
                        .map(s -> s.agent().getName() + "(" + s.score() + "," + s.reason() + ")").toList());

        // 按评分降序级联发言：被 @ 者通常优先，但失败/空内容时顺延下一位，不垄断、不整条报错
        SpeakResult speakResult = speakOnceCascading(ranked, members, groupId, input, mentionedAgentIds, repliedToAgentId);
        if (!speakResult.success) {
            // 发言失败：所有 Agent 都失败，广播错误
            groupBroadcastService.broadcast(groupId, WsConstants.ERROR, Map.of(
                    "success", false,
                    "errorCode", ErrorCode.ALL_AGENTS_FAILED.name(),
                    "message", ErrorCode.ALL_AGENTS_FAILED.getDefaultMessage()));
            return Map.of();
        }

        // chatBuffer 累积 + 画像提炼阈值检查
        Map<String, Object> result = new HashMap<>();
        int newBuffer = chatBuffer + 1;
        if (newBuffer >= profileThreshold) {
            // 达阈值：重置 buffer，标记需要画像提炼（条件边分流到 profile-extract）
            result.put(StateKeys.CHAT_BUFFER, 0);
            result.put(StateKeys.NEED_PROFILE_EXTRACT, true);
            LogHelper.printLog(ChatNode.class, "ChatNode.apply", "CHAT_NODE", "闲聊缓冲达阈值触发画像提炼",
                    "groupId={} buffer={}/{}", groupId, newBuffer, profileThreshold);
        } else {
            result.put(StateKeys.CHAT_BUFFER, newBuffer);
            result.put(StateKeys.NEED_PROFILE_EXTRACT, false);
        }

        return result;
    }

    /**
     * 候选 Agent 评分排序：统一走 {@link SpeakerScheduler}（@提及 +800 大权重加分通常优先，
     * 但不再硬选首个被 @ 者），返回按分数降序的完整候选列表供级联发言。
     */
    private List<SpeakerScheduler.ScoredAgent> rankSpeakers(List<Agent> members, List<Long> mentionedAgentIds, Long groupId) {
        MessageContext ctx = MessageContext.builder()
                .groupId(groupId)
                .topicId(null)
                .content("")
                .mentionedAgentIds(mentionedAgentIds)
                .repliedToAgentId(null)
                .speakCounts(Map.of())
                .build();
        return speakerScheduler.rank(members, ctx);
    }

    /**
     * 按评分降序级联发言：首位（通常为被 @ 者）失败/空内容时顺延下一位，
     * 被 @ 者不垄断发言；全部候选都失败才返回失败。
     */
    private SpeakResult speakOnceCascading(List<SpeakerScheduler.ScoredAgent> ranked, List<Agent> members, Long groupId,
                                           String input, List<Long> mentionedAgentIds, Long repliedToAgentId) {
        for (int i = 0; i < ranked.size(); i++) {
            SpeakerScheduler.ScoredAgent scored = ranked.get(i);
            Agent agent = scored.agent();
            LogHelper.printLog(ChatNode.class, "ChatNode.speakOnceCascading", "CHAT_NODE", "候选发言 降级链位置",
                    "index={}/{} agent={} score={} reason={}",
                    i + 1, ranked.size(), agent.getName(), scored.score(), scored.reason());
            SpeakResult result = speakOnce(agent, members, groupId, input, mentionedAgentIds, repliedToAgentId);
            if (result.success) {
                return result;
            }
        }
        return new SpeakResult(false);
    }

    /**
     * 一次发言：构建上下文 → LLM 调用 → 标记过滤 → 入库广播。
     * <p>闲聊态简化版：不处理 [[CONCLUDE]]/[[PASS]]（仅讨论态有意义），直接剥离后入库。
     *
     * @return 发言结果（success=true 表示发言成功）
     */
    private SpeakResult speakOnce(Agent agent, List<Agent> members, Long groupId,
                                   String input, List<Long> mentionedAgentIds, Long repliedToAgentId) {
//        eventPublisher.publish(new AgentSelected(groupId, null,
//                agent.getId(), agent.getName(), "CHAT", 0));
        pushTyping(groupId, agent, true);
        StreamEmitter emitter = streamingEnabled ? new StreamEmitter(groupId, agent, groupBroadcastService) : null;
        try {
            // 构建闲聊上下文（topicId=null）
            ContextBuilder.LlmContext llmCtx = contextBuilder.build(
                    agent, groupId, null, messageAssembler::resolveSenderName);

            // 构建 ReactAgent 上下文（群记忆/用户画像/知识由 Hook 动态注入）
            Map<String, Object> context = new HashMap<>();
            context.put("groupId", groupId);
            context.put("userId", 1L);  // 当前单用户系统默认 ID
            context.put("speakerAgentId", agent.getId());
            // RAG 检索词：以用户输入为查询（RagInjectionHook 读取）
            context.put("ragQuery", input);

            // Agent 发言（失败重试 1 次）；流式模式下重试前废弃旧流、换新 streamId 重开
            AgentSpeakerService.AgentResult result;
            try {
                result = streamingEnabled
                        ? agentSpeakerService.callStream(agent, llmCtx.systemPrompt(), llmCtx.turns(),
                                AgentSpeakerService.ToolSet.CHAT, context, emitter::onDelta)
                        : agentSpeakerService.call(agent, llmCtx.systemPrompt(), llmCtx.turns(),
                                AgentSpeakerService.ToolSet.CHAT, context);
            } catch (Exception first) {
                LogHelper.printWarnLog(ChatNode.class, "ChatNode.speakOnce", "CHAT_NODE", "Agent首次失败重试",
                        "agent={} 失败原因: {}", agent.getName(), first.getMessage());
                if (emitter != null) {
                    emitter.reset();
                }
                result = streamingEnabled
                        ? agentSpeakerService.callStream(agent, llmCtx.systemPrompt(), llmCtx.turns(),
                                AgentSpeakerService.ToolSet.CHAT, context, emitter::onDelta)
                        : agentSpeakerService.call(agent, llmCtx.systemPrompt(), llmCtx.turns(),
                                AgentSpeakerService.ToolSet.CHAT, context);
            }
            String content = result.content();

            // 闲聊态：空内容视为不发言
            if (content == null || content.isBlank()) {
                if (emitter != null) {
                    emitter.abort();
                }
                LogHelper.printLog(ChatNode.class, "ChatNode.speakOnce", "CHAT_NODE", "Agent返回空内容不发言",
                        "agent={} groupId={}", agent.getName(), groupId);
                return new SpeakResult(false);
            }

            // 剥离协作标记（闲聊态不应有，防御性处理）
            content = ContextBuilder.stripMarkers(content);
            if (content.isBlank()) {
                if (emitter != null) {
                    emitter.abort();
                }
                return new SpeakResult(false);
            }

            // 重复内容检查：避免连续相同发言入库
            Optional<GroupMessage> lastOpt = messageRepository.findLastByGroupId(groupId);
            if (lastOpt.isPresent()) {
                GroupMessage last = lastOpt.get();
                if (last.getSenderId() != null && last.getSenderId().equals(agent.getId())
                        && last.getSenderType() == SenderType.AGENT
                        && content.equals(last.getContent())) {
                    LogHelper.printLog(ChatNode.class, "ChatNode.speakOnce", "CHAT_NODE", "跳过重复内容不入库",
                            "agent={} groupId={}", agent.getName(), groupId);
                    if (emitter != null) {
                        emitter.abort();
                    }
                    return new SpeakResult(false);
                }
            }

            // 入库 + 广播
            GroupMessage reply = saveAgentMessage(groupId, agent, content);
            if (emitter != null && emitter.emitted) {
                groupBroadcastService.broadcast(groupId, WsConstants.MESSAGE_COMPLETE, Map.of(
                        "streamId", emitter.streamId,
                        "message", messageAssembler.toDto(reply)));
            } else {
                groupBroadcastService.broadcast(groupId, WsConstants.NEW_MESSAGE, messageAssembler.toDto(reply));
            }
            eventPublisher.publish(new MessageSent(reply.getId(), groupId, null,
                    agent.getId(), SenderType.AGENT.name(), content, null, List.of()));
            LogHelper.printLog(ChatNode.class, "ChatNode.speakOnce", "CHAT_NODE", "Agent发言已入库广播",
                    "agent={} messageId={} 长度={} 流式={}",
                    agent.getName(), reply.getId(), content.length(),
                    emitter != null && emitter.emitted);
            return new SpeakResult(true);
        } catch (Exception e) {
            if (emitter != null) {
                emitter.abort();
            }
            LogHelper.printWarnLog(ChatNode.class, "ChatNode.speakOnce", "CHAT_NODE", "Agent调用失败",
                    "agent={} groupId={}", agent.getName(), groupId, e);
            eventPublisher.publish(new AgentFailed(groupId, null,
                    agent.getId(), agent.getName(), e.getMessage(), null, null));
            return new SpeakResult(false);
        } finally {
            pushTyping(groupId, agent, false);
        }
    }

    private GroupMessage saveAgentMessage(Long groupId, Agent agent, String content) {
        GroupMessage reply = new GroupMessage();
        reply.setChatGroupId(groupId);
        reply.setTopicId(null);
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

    /** 发言结果 */
    private record SpeakResult(boolean success) {}

    /**
     * 单次流式发言会话：delta 经 {@link StreamMarkerGuard} 过滤后逐块广播；
     * emitted 标志决定失败/PASS 时是否需推 ABORT 让前端丢弃半成品气泡。
     * <p>迁移自 DiscussionEngine.StreamEmitter，ChatNode 和 DiscussNode 共用发言逻辑时复用。
     */
    private static class StreamEmitter {
        private final Long groupId;
        private final Agent agent;
        private final GroupBroadcastService broadcastService;
        private String streamId = UUID.randomUUID().toString();
        private StreamMarkerGuard guard = new StreamMarkerGuard();
        private volatile boolean emitted;

        StreamEmitter(Long groupId, Agent agent, GroupBroadcastService broadcastService) {
            this.groupId = groupId;
            this.agent = agent;
            this.broadcastService = broadcastService;
        }

        void onDelta(String chunk) {
            String safe = guard.onChunk(chunk);
            if (safe.isEmpty()) {
                return;
            }
            emitted = true;
            broadcastService.broadcast(groupId, WsConstants.MESSAGE_DELTA, Map.of(
                    "streamId", streamId,
                    "agentId", agent.getId(),
                    "agentName", agent.getName(),
                    "delta", safe));
        }

        /** 废弃当前流（前端丢弃半成品气泡）；未发过 delta 则无需通知 */
        void abort() {
            if (emitted) {
                broadcastService.broadcast(groupId, WsConstants.MESSAGE_ABORT, Map.of("streamId", streamId));
            }
        }

        /** 重试前重置：废弃旧流，换新 streamId 重新开始 */
        void reset() {
            abort();
            streamId = UUID.randomUUID().toString();
            guard = new StreamMarkerGuard();
            emitted = false;
        }
    }
}
