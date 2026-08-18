package com.dingring.infrastructure.agent.hook;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.AgentHook;
import com.alibaba.cloud.ai.graph.state.ReplaceAllWith;
import com.dingring.common.util.LogHelper;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.agent.AgentRepository;
import com.dingring.domain.service.GroupContextMemoryService;
import com.dingring.domain.service.LlmService.ChatTurn;
import com.dingring.domain.workflow.StateKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 群上下文记忆注入 Hook（取代 MemoryInjectionHook + ContextBuilder，设计见
 * docs/group-context-memory-hook-design.md）。
 * <p>「模型所见上下文」的唯一组装点：按意图（CHAT/DISCUSS/CONCLUDE）分发
 * {@link GroupContextMemoryService} 组装人设提示词与历史轮次，一次注入贯穿整个 ReAct 运行。
 * <p>为什么是 AgentHook 而非 ModelHook：注入内容一次 Agent 运行内不变，beforeAgent 只执行一次；
 * ModelHook 会在 ReAct 工具循环内每次模型调用重复查库、重复注入（InjectKbHook javadoc 记录的坑）。
 * <p>注入方式：
 * <ul>
 *   <li>CHAT：无历史轮次，普通追加 SystemMessage（保留节点传入的当前输入兜底 USER 轮）</li>
 *   <li>DISCUSS/CONCLUDE：{@code ReplaceAllWith} 整表替换 messages（SystemMessage 置首 + 原文窗口轮次，
 *       窗口已含当前输入，替换不丢信息；AppendStrategy 对 ReplaceAllWith 值走整表替换语义）</li>
 * </ul>
 * <p>跳过场景：groupId/speakerAgentId 缺失（Supervisor Worker clearContext 后的自然跳过）、
 * intent 为 WORK/缺失、DISCUSS/CONCLUDE 缺 topicId。
 * <p>顺序硬约束：本 Hook 必须注册在 InjectKbHook（append）之前、SystemMessageMergeHook 必须最后
 * （SaaLlmFactory/SupervisorAgentFactory 现有注册顺序即满足），否则整表替换会吃掉后续 Hook 的注入。
 * <p>线程安全：单例 Bean，仅从 OverAllState（per-call）读取参数，无共享可变状态。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GroupContextMemoryHook extends AgentHook {

    private static final String MESSAGES_KEY = "messages";

    private final GroupContextMemoryService contextMemoryService;
    private final AgentRepository agentRepository;

    @Override
    public String getName() {
        return "group-context-memory";
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeAgent(OverAllState state, RunnableConfig config) {
        Long groupId = state.<Long>value(StateKeys.GROUP_ID).orElse(null);
        Long speakerAgentId = state.<Long>value(StateKeys.SPEAKER_AGENT_ID).orElse(null);
        String intent = state.value(StateKeys.INTENT, "");

        if (groupId == null || speakerAgentId == null) {
            return CompletableFuture.completedFuture(Map.of());
        }
        Agent speaker = agentRepository.findById(speakerAgentId).orElse(null);
        if (speaker == null) {
            LogHelper.printWarnLog(GroupContextMemoryHook.class, "beforeAgent", "HOOK_MEMORY",
                    "发言Agent不存在跳过注入", "groupId={} speakerAgentId={}", groupId, speakerAgentId);
            return CompletableFuture.completedFuture(Map.of());
        }

        GroupContextMemoryService.AgentPromptContext ctx = switch (intent) {
            case "CHAT" -> contextMemoryService.buildChatContext(speaker, groupId);
            case "DISCUSS" -> {
                Long topicId = state.<Long>value(StateKeys.TOPIC_ID).orElse(null);
                if (topicId == null) {
                    LogHelper.printWarnLog(GroupContextMemoryHook.class, "beforeAgent", "HOOK_MEMORY",
                            "DISCUSS缺topicId跳过注入", "groupId={} speakerAgentId={}", groupId, speakerAgentId);
                    yield null;
                }
                yield contextMemoryService.buildDiscussContext(speaker, groupId, topicId,
                        state.value(StateKeys.USER_HISTORY_HINT, ""));
            }
            case "CONCLUDE" -> {
                Long topicId = state.<Long>value(StateKeys.TOPIC_ID).orElse(null);
                if (topicId == null) {
                    LogHelper.printWarnLog(GroupContextMemoryHook.class, "beforeAgent", "HOOK_MEMORY",
                            "CONCLUDE缺topicId跳过注入", "groupId={} speakerAgentId={}", groupId, speakerAgentId);
                    yield null;
                }
                yield contextMemoryService.buildConclusionContext(speaker, topicId,
                        state.value(StateKeys.TOPIC_TITLE, ""));
            }
            // WORK（WorkNode 自建 prompt）/ 未知意图：不由本 Hook 注入
            default -> null;
        };

        if (ctx == null || ctx.systemPrompt() == null || ctx.systemPrompt().isBlank()) {
            return CompletableFuture.completedFuture(Map.of());
        }

        LogHelper.printLog(GroupContextMemoryHook.class, "beforeAgent", "HOOK_MEMORY",
                "注入群上下文记忆", "groupId={} intent={} speaker={} turns={} systemPrompt长度={}",
                groupId, intent, speaker.getName(), ctx.turns().size(), ctx.systemPrompt().length());

        if (ctx.turns().isEmpty()) {
            // 闲聊：无历史轮次，追加 SystemMessage（不动节点传入的当前输入兜底轮）
            return CompletableFuture.completedFuture(
                    Map.of(MESSAGES_KEY, new SystemMessage(ctx.systemPrompt())));
        }

        // 讨论/收束：整表替换（原文窗口轮次已含当前输入）
        List<Message> messages = new ArrayList<>(ctx.turns().size() + 1);
        messages.add(new SystemMessage(ctx.systemPrompt()));
        for (ChatTurn turn : ctx.turns()) {
            messages.add("ASSISTANT".equalsIgnoreCase(turn.role())
                    ? new AssistantMessage(turn.content())
                    : new UserMessage(turn.content()));
        }
        return CompletableFuture.completedFuture(
                Map.of(MESSAGES_KEY, ReplaceAllWith.of(messages)));
    }
}
