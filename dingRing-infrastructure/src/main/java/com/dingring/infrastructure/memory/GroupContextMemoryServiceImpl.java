package com.dingring.infrastructure.memory;

import com.dingring.common.util.LogHelper;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.agent.AgentRepository;
import com.dingring.domain.group.GroupMessage;
import com.dingring.domain.group.MessageRepository;
import com.dingring.domain.group.SenderType;
import com.dingring.domain.service.GroupContextMemoryService;
import com.dingring.domain.service.LlmService.ChatTurn;
import com.dingring.domain.user.UserRepository;
import com.dingring.infrastructure.aop.Event;
import com.dingring.infrastructure.prompt.PromptTemplateLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 群上下文记忆服务实现：承接原 ContextBuilder（Phase D 前的静态拼接）+ SimpleMemoryService 全部逻辑，
 * 按 CHAT/DISCUSS/CONCLUDE 三意图组装（设计见 docs/group-context-memory-hook-design.md）。
 * <p>行为规则：
 * <ul>
 *   <li>闲聊：最近 {@code chat-recent-limit} 条闲聊消息记忆段，排除最后一条（当前输入，已由节点作为兜底 USER 轮传入）</li>
 *   <li>讨论：观点摘要段只取 viewpoint 非空的消息（null 丢弃不回退原文）；进度引导计数用打标消息总数，不依赖摘要完成度</li>
 *   <li>收束：观点清单 KEY/VIEWPOINT 均可，无摘要时回退原文（话题最终产出完整性优先）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupContextMemoryServiceImpl implements GroupContextMemoryService {

    private static final String CHAT_MEMORY_HEADER = "群最近聊天记录（供参考）：";
    private static final String DISCUSS_VIEWPOINT_HEADER = "讨论观点:";
    private static final String CONCLUDE_VIEWPOINT_HEADER = "讨论观点（含发言人归属，为主要输入）：";

    private final MessageRepository messageRepository;
    private final AgentRepository agentRepository;
    private final UserRepository userRepository;
    private final PromptTemplateLoader promptLoader;

    /** 闲聊记忆条数（建议 5~10） */
    @Value("${dingring.memory.chat-recent-limit:10}")
    private int chatRecentLimit;

    /** 收束全量原文窗口条数 */
    @Value("${dingring.orchestrator.context-window:200}")
    private int contextWindow;

    /** 观点摘要/清单条数上限（取最新 N 条） */
    @Value("${dingring.orchestrator.viewpoint-limit:20}")
    private int viewpointLimit;

    /** 讨论近期原文窗口条数（语气衔接用） */
    @Value("${dingring.orchestrator.discuss-recent-window:8}")
    private int discussRecentWindow;

    @Override
    @Event(eventCode = "BUILD_CHAT_CONTEXT", eventName = "构建闲聊上下文")
    public AgentPromptContext buildChatContext(Agent speaker, Long groupId) {
        StringBuilder sp = new StringBuilder(buildBaseSystemPrompt(speaker, false));

        String memory = buildChatMemory(groupId);
        if (!memory.isEmpty()) {
            sp.append("\n\n").append(memory);
        }

        String systemPrompt = sp.toString();
        LogHelper.printLog(GroupContextMemoryServiceImpl.class, "buildChatContext", "BUILD_CHAT_CONTEXT",
                "闲聊上下文构建完成", "groupId={} 记忆条数上限={} systemPrompt长度={}",
                groupId, chatRecentLimit, systemPrompt.length());
        // turns 恒为空：当前输入由节点作为兜底 USER 轮传入，Hook 以追加方式注入 SystemMessage
        return new AgentPromptContext(systemPrompt, List.of());
    }

    @Override
    @Event(eventCode = "BUILD_DISCUSS_CONTEXT", eventName = "构建讨论态上下文")
    public AgentPromptContext buildDiscussContext(Agent speaker, Long groupId, Long topicId,
                                                  String userHistoryHint) {
        Function<GroupMessage, String> nameOf = memoizedNameResolver();
        StringBuilder sp = new StringBuilder(buildBaseSystemPrompt(speaker, true));

        // 层1：观点摘要列表（仅 viewpoint 非空；KEY/未生成摘要的消息丢弃，不回退原文）
        List<GroupMessage> viewpoints = messageRepository.findViewpointsByTopicId(topicId, viewpointLimit);
        List<String> lines = new ArrayList<>();
        for (GroupMessage m : viewpoints) {
            if (m.getViewpoint() != null && !m.getViewpoint().isBlank()) {
                lines.add(nameOf.apply(m) + ": " + m.getViewpoint());
            }
        }
        if (!lines.isEmpty()) {
            sp.append("\n\n").append(DISCUSS_VIEWPOINT_HEADER).append('\n')
                    .append(String.join("\n", lines));
        }

        // 讨论进度引导：计数用打标消息总数（未过滤），随讨论进展动态变化
        sp.append(buildDiscussionProgressGuide(topicId, viewpoints.size()));

        // 用户历史表现提示（话题重启）
        if (userHistoryHint != null && !userHistoryHint.isBlank()) {
            sp.append("\n\n").append(userHistoryHint);
        }

        // 层2：近期窗口（最近 N 条原文转对话轮次，保持语气衔接）
        List<GroupMessage> recent = messageRepository.findRecentByTopicId(topicId, discussRecentWindow);
        List<ChatTurn> turns = toTurns(speaker, recent, nameOf);
        String systemPrompt = sp.toString();
        LogHelper.printLog(GroupContextMemoryServiceImpl.class, "buildDiscussContext", "BUILD_DISCUSS_CONTEXT",
                "讨论上下文构建完成", "groupId={} topicId={} 打标观点数={} 采用摘要数={} 近期窗口条数={} systemPrompt长度={}",
                groupId, topicId, viewpoints.size(), lines.size(), recent.size(), systemPrompt.length());
        return new AgentPromptContext(systemPrompt, turns);
    }

    @Override
    @Event(eventCode = "BUILD_CONCLUSION_CONTEXT", eventName = "构建收束上下文")
    public AgentPromptContext buildConclusionContext(Agent concluder, Long topicId, String topicTitle) {
        Function<GroupMessage, String> nameOf = memoizedNameResolver();
        StringBuilder sp = new StringBuilder();
        if (concluder.getSystemPrompt() != null && !concluder.getSystemPrompt().isBlank()) {
            sp.append(concluder.getSystemPrompt()).append("\n\n");
        }
        sp.append(promptLoader.render("conclude",
                Map.of("topicTitle", topicTitle == null ? "" : topicTitle)));

        // 层1：观点摘要清单（带归属，为主要输入；无摘要回退原文--收束完整性优先于降噪，
        // 用户消息恒为 KEY 无摘要，回退保证其发言不被清出主要输入）
        List<GroupMessage> viewpoints = messageRepository.findViewpointsByTopicId(topicId, viewpointLimit);
        List<String> lines = new ArrayList<>();
        for (GroupMessage m : viewpoints) {
            String text = (m.getViewpoint() != null && !m.getViewpoint().isBlank())
                    ? m.getViewpoint() : m.getContent();
            lines.add(nameOf.apply(m) + ": " + text);
        }
        if (!lines.isEmpty()) {
            sp.append("\n\n").append(CONCLUDE_VIEWPOINT_HEADER).append('\n')
                    .append(String.join("\n", lines));
        }

        // 原文窗口保留用于核对细节与出处（如发言中的 mermaid/svg 图表），不作为主要总结输入
        List<GroupMessage> all = messageRepository.findRecentByTopicId(topicId, contextWindow);
        List<ChatTurn> turns = toTurns(concluder, all, nameOf);
        String systemPrompt = sp.toString();
        LogHelper.printLog(GroupContextMemoryServiceImpl.class, "buildConclusionContext", "BUILD_CONCLUSION_CONTEXT",
                "收束上下文构建完成", "topicId={} 观点条数={} 原文窗口条数={} systemPrompt长度={}",
                topicId, viewpoints.size(), all.size(), systemPrompt.length());
        return new AgentPromptContext(systemPrompt, turns);
    }

    /**
     * 闲聊记忆段：最近 N 条闲聊消息（topic_id IS NULL），排除时间最新的一条（即当前输入）。
     * <p>多查 1 条再截断，保证排除当前输入后仍有 N 条。
     */
    private String buildChatMemory(Long groupId) {
        if (groupId == null || chatRecentLimit <= 0) {
            return "";
        }
        List<GroupMessage> recent = messageRepository.findRecentChatByGroupId(groupId, chatRecentLimit + 1);
        if (recent == null || recent.size() <= 1) {
            return "";
        }
        List<GroupMessage> window = recent.subList(0, recent.size() - 1);
        Function<GroupMessage, String> nameOf = memoizedNameResolver();
        StringBuilder sb = new StringBuilder(CHAT_MEMORY_HEADER);
        for (GroupMessage m : window) {
            sb.append('\n').append(nameOf.apply(m)).append(": ").append(m.getContent());
        }
        return sb.toString();
    }

    /**
     * 构建基础系统提示词：Agent 人设 + chat-base + 协作协议（讨论态）。
     */
    @Event(eventCode = "BUILD_SYSTEM_PROMPT", eventName = "构建基础系统提示词")
    private String buildBaseSystemPrompt(Agent agent, boolean withCollaboration) {
        StringBuilder sp = new StringBuilder();
        if (agent.getSystemPrompt() != null && !agent.getSystemPrompt().isBlank()) {
            sp.append(agent.getSystemPrompt());
        }
        sp.append("\n\n").append(promptLoader.render("chat-base", Map.of("agentName", agent.getName())));
        if (withCollaboration) {
            sp.append("\n\n").append(promptLoader.render("collaboration-protocol", Map.of()));
        }
        return sp.toString();
    }

    /**
     * 构建讨论进度引导：根据已有观点数和 Agent 发言轮次，输出数据驱动的深度推进指令
     * （逻辑自原 ContextBuilder 原样迁入）。
     */
    private String buildDiscussionProgressGuide(Long topicId, int viewpointCount) {
        long agentMessages = messageRepository.countByTopicIdAndSenderType(topicId, SenderType.AGENT);
        String phase;
        String guide;
        if (viewpointCount <= 2 || agentMessages <= 3) {
            phase = "早期";
            guide = "讨论刚开始，请围绕主题积极发表你的独立观点，避免与已有观点重复。";
        } else if (viewpointCount <= 6 || agentMessages <= 8) {
            phase = "中期";
            guide = "讨论已展开，请尝试以下方式推进深度：提出与已有观点不同的新视角、"
                    + "反驳或质疑某个观点并给出理由、为某个观点补充更具体的论据或示例。";
        } else {
            phase = "后期";
            guide = "讨论已深入，请尝试：综合不同观点的共性与分歧、指出核心争议点、"
                    + "提出能整合多方视角的方案或结论。如果你认为讨论已充分，可以输出 [[CONCLUDE]] 提议收束。";
        }
        return String.format("\n\n讨论进度: %s（已发言%d轮，已有%d个观点）\n%s",
                phase, agentMessages, viewpointCount, guide);
    }

    /**
     * 消息 -> 对话轮次：Agent 自己的发言为 ASSISTANT，其余合并为带花名前缀的 USER 轮次；
     * 连续 USER 轮次合并为一条（逻辑自原 ContextBuilder 原样迁入）。
     */
    @Event(eventCode = "BUILD_CHAT_TURNS", eventName = "构建对话轮次")
    private List<ChatTurn> toTurns(Agent self, List<GroupMessage> messages,
                                   Function<GroupMessage, String> nameOf) {
        List<ChatTurn> turns = new ArrayList<>();
        StringBuilder userBuffer = new StringBuilder();
        for (GroupMessage m : messages) {
            boolean isSelf = m.getSenderType() == SenderType.AGENT && self.getId().equals(m.getSenderId());
            if (isSelf) {
                if (!userBuffer.isEmpty()) {
                    turns.add(ChatTurn.user(userBuffer.toString()));
                    userBuffer.setLength(0);
                }
                turns.add(ChatTurn.assistant(m.getContent()));
            } else {
                if (!userBuffer.isEmpty()) {
                    userBuffer.append('\n');
                }
                userBuffer.append(nameOf.apply(m)).append(": ").append(m.getContent());
            }
        }
        if (!userBuffer.isEmpty()) {
            turns.add(ChatTurn.user(userBuffer.toString()));
        }
        if (turns.isEmpty()) {
            turns.add(ChatTurn.user("（讨论刚开始，请围绕主题发表你的看法）"));
        }
        return turns;
    }

    /**
     * 花名解析（带本次调用的记忆化缓存）：AGENT 查 Agent 表 / USER 查用户表 / SYSTEM 显示「系统」，
     * 规则与 app 层 MessageAssembler.resolveSenderName 同源同步。
     */
    private Function<GroupMessage, String> memoizedNameResolver() {
        Map<Long, String> cache = new HashMap<>();
        return m -> {
            if (m.getSenderId() == null) {
                return "未知";
            }
            if (m.getSenderType() == SenderType.USER) {
                return cache.computeIfAbsent(m.getSenderId(),
                        id -> userRepository.findById(id).map(u -> u.getName()).orElse("用户#" + id));
            }
            if (m.getSenderType() == SenderType.AGENT) {
                // AGENT 与 USER 的 ID 空间可能重叠，缓存键加类型位隔离
                return cache.computeIfAbsent(1_000_000_000L + m.getSenderId(),
                        id -> agentRepository.findById(id - 1_000_000_000L).map(a -> a.getName())
                                .orElse("Agent#" + (id - 1_000_000_000L)));
            }
            return "系统";
        };
    }
}
