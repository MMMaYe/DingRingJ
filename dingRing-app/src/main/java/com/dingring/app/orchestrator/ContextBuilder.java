package com.dingring.app.orchestrator;

import com.dingring.app.service.GroupAppService;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.group.GroupMessage;
import com.dingring.domain.group.MessageRepository;
import com.dingring.domain.group.SenderType;
import com.dingring.domain.service.LlmService.ChatTurn;
import com.dingring.common.constant.PromptConstants;
import com.dingring.common.util.LogHelper;
import com.dingring.infrastructure.aop.Event;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 上下文构建：Agent 人设 + 滑动窗口最近 N 条消息（见技术方案 6.3）。
 * <p>Phase D 改造：群记忆/用户画像/群成员名单的动态拼接迁移到 Hook（MemoryInjectionHook /
 * ProfileInjectionHook / GroupRosterHook），本类只负责"静态系统提示词 + 消息历史"。
 * <p>群聊语境：所有消息拼上发送者花名前缀合成 USER 轮次，Agent 自己的历史发言为 ASSISTANT 轮次。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContextBuilder {

    /** Agent 自主收束标记：Agent 认为讨论可总结时在回复末尾输出，编排器检测到后触发结束流程 */
    public static final String CONCLUDE_MARKER = "[[CONCLUDE]]";

    /** Agent 跳过本轮标记：无新观点时只输出该标记，不入库不广播；连续 PASS 触发收敛收束 */
    public static final String PASS_MARKER = "[[PASS]]";

    /** 剥离收束标记（消息入库/结论落库前调用） */
    public static String stripConcludeMarker(String content) {
        return content == null ? null : content.replace(CONCLUDE_MARKER, "").trim();
    }

    /** 剥离全部协作标记（CONCLUDE + PASS） */
    public static String stripMarkers(String content) {
        return content == null ? null
                : content.replace(CONCLUDE_MARKER, "").replace(PASS_MARKER, "").trim();
    }

    private final MessageRepository messageRepository;

    /** 滑动窗口大小（可配置，默认 200 条） */
    @Value("${dingring.orchestrator.context-window:200}")
    private int contextWindow;

    /** 讨论态附带的近期闲聊条数（群氛围前置段） */
    @Value("${dingring.orchestrator.chat-context-window:20}")
    private int chatContextWindow;

    /** 讨论态观点摘要列表条数上限（方案 6.3.6，取最新 N 条） */
    @Value("${dingring.orchestrator.viewpoint-limit:20}")
    private int viewpointLimit;

    /** 讨论态近期原文窗口条数（方案 6.3.6，语气衔接用） */
    @Value("${dingring.orchestrator.discuss-recent-window:8}")
    private int discussRecentWindow;

    /**
     * 构建 Agent 发言的完整上下文（Phase D：只含静态系统提示词 + 消息历史）。
     * <p>动态部分（群记忆/用户画像/群成员名单）由 Hook 在 ReactAgent 调用 LLM 前注入。
     *
     * @param agent           发言 Agent
     * @param groupId         群 ID
     * @param topicId         主题 ID（null = 闲聊，取群窗口）
     * @param senderNameOf    发送者名称解析函数
     * @return 基础 system prompt + 对话轮次
     */
    @Event(eventCode = "BUILD_ALL_CONTEXT", eventName = "构建 Agent 发言的完整上下文")
    public LlmContext build(Agent agent, Long groupId, Long topicId,
                            Function<GroupMessage, String> senderNameOf) {
        String systemPrompt = buildBaseSystemPrompt(agent, topicId != null);

        // 滑动窗口最近 N 条消息
        List<GroupMessage> window = topicId != null
                ? mergeChatContext(groupId, messageRepository.findRecentByTopicId(topicId, contextWindow))
                : messageRepository.findRecentByGroupId(groupId, contextWindow);

        List<ChatTurn> turns = toTurns(agent, window, senderNameOf);
        LogHelper.printLog(ContextBuilder.class, "ContextBuilder.build", "BUILD", "构建完成",
                "groupId={} topicId={} 消息条数={} systemPrompt长度={}",
                groupId, topicId, turns.size(), systemPrompt.length());
        return new LlmContext(systemPrompt, turns);
    }

    /**
     * 构建讨论态发言上下文（方案 6.3.6）：观点摘要列表 + 近期原文窗口，替代全量 200 条窗口。
     * <p>token 收益：50 轮讨论约 2450 token（对比全量窗口 ~10 万 token）。
     * <ul>
     *   <li>层1 观点摘要列表：tag=KEY/VIEWPOINT 的消息（VIEWPOINT 用 LLM 摘要，KEY 用原文），
     *       拼入 system prompt 让 Agent 掌握讨论全貌而不必读全部原文</li>
     *   <li>层2 近期窗口：最近 {@code discussRecentWindow} 条原文转对话轮次，保持语气衔接</li>
     *   <li>userHistoryHint：话题重启时注入的用户历史表现提示（EnsureTopicNode 产出）</li>
     * </ul>
     *
     * @param userHistoryHint 话题重启时的用户历史表现提示（无则传 null/空）
     */
    @Event(eventCode = "BUILD_DISCUSS_CONTEXT", eventName = "构建讨论态发言上下文")
    public LlmContext buildForDiscuss(Agent agent, Long groupId, Long topicId,
                                      Function<GroupMessage, String> senderNameOf, String userHistoryHint) {
        StringBuilder sp = new StringBuilder(buildBaseSystemPrompt(agent, true));

        // 层1：观点摘要列表（有摘要用摘要，否则回退原文；用户 KEY 消息天然是原文观点）
        List<GroupMessage> viewpoints = messageRepository.findViewpointsByTopicId(topicId, viewpointLimit);
        if (!viewpoints.isEmpty()) {
            sp.append("\n\n讨论观点:\n");
            for (GroupMessage m : viewpoints) {
                String text = (m.getViewpoint() != null && !m.getViewpoint().isBlank())
                        ? m.getViewpoint() : m.getContent();
                sp.append(senderNameOf.apply(m)).append(": ").append(text).append('\n');
            }
        }

        // 用户历史表现提示（话题重启）：Agent 据此针对性引导用户提升
        if (userHistoryHint != null && !userHistoryHint.isBlank()) {
            sp.append("\n\n").append(userHistoryHint);
        }

        // 层2：近期窗口（最近 N 条原文，转对话轮次保持语气衔接）
        List<GroupMessage> recent = messageRepository.findRecentByTopicId(topicId, discussRecentWindow);
        List<ChatTurn> turns = toTurns(agent, recent, senderNameOf);
        String systemPrompt = sp.toString();
        LogHelper.printLog(ContextBuilder.class, "ContextBuilder.buildForDiscuss", "BUILD_DISCUSS", "讨论上下文构建完成",
                "groupId={} topicId={} 观点条数={} 近期窗口条数={} systemPrompt长度={}",
                groupId, topicId, viewpoints.size(), recent.size(), systemPrompt.length());
        return new LlmContext(systemPrompt, turns);
    }

    /** 讨论态附带少量闲聊：主题窗口前合并最近 N 条未归属主题的消息（近期群氛围） */
    private List<GroupMessage> mergeChatContext(Long groupId, List<GroupMessage> topicWindow) {
        if (chatContextWindow <= 0) {
            return topicWindow;
        }
        List<GroupMessage> chat = messageRepository.findRecentChatByGroupId(groupId, chatContextWindow);
        if (chat.isEmpty()) {
            return topicWindow;
        }
        List<GroupMessage> merged = new ArrayList<>(chat.size() + topicWindow.size());
        merged.addAll(chat);
        merged.addAll(topicWindow);
        merged.sort(Comparator.comparing(GroupMessage::getCreateTime,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(GroupMessage::getId,
                        Comparator.nullsFirst(Comparator.naturalOrder())));
        return merged;
    }

    /**
     * 结论生成上下文（Phase D：只含静态系统提示词 + 消息历史，群记忆由 Hook 注入）。
     */
    public LlmContext buildForConclusion(Agent concluder, Long groupId, Long topicId, String topicTitle,
                                         Function<GroupMessage, String> senderNameOf) {
        StringBuilder sp = new StringBuilder();
        if (concluder.getSystemPrompt() != null && !concluder.getSystemPrompt().isBlank()) {
            sp.append(concluder.getSystemPrompt()).append("\n\n");
        }
        sp.append(String.format(PromptConstants.CONCLUSION_STAR, topicTitle));

        List<GroupMessage> all = messageRepository.findRecentByTopicId(topicId, contextWindow);
        String spFinal = sp.toString();
        LogHelper.printLog(ContextBuilder.class, "ContextBuilder.buildForConclusion", "BUILD_FOR_CONCLUSION", "结论上下文",
                "topicId={} 消息条数={} systemPrompt长度={}", topicId, all.size(), spFinal.length());
        return new LlmContext(spFinal, toTurns(concluder, all, senderNameOf));
    }

    /**
     * 构建基础系统提示词（Phase D：只含 Agent 人设 + CHAT_BASE + 协作协议）。
     * <p>群记忆/用户画像/群成员名单由 Hook 动态注入，不再在此拼接。
     */
    @Event(eventCode = "BUILD_SYSTEM_PROMPT", eventName = "构建基础系统提示词")
    private String buildBaseSystemPrompt(Agent agent, boolean withCollaboration) {
        StringBuilder sp = new StringBuilder();
        if (agent.getSystemPrompt() != null && !agent.getSystemPrompt().isBlank()) {
            sp.append(agent.getSystemPrompt());
        }
        sp.append("\n\n").append(String.format(PromptConstants.CHAT_BASE, agent.getName()));
        if (withCollaboration) {
            // 协作协议：自主收束 + 跳过本轮
            sp.append("\n\n").append(PromptConstants.COLLABORATION_PROTOCOL);
        }
        return sp.toString();
    }

    /**
     * 消息 → 对话轮次：Agent 自己的发言为 ASSISTANT，其余合并为带花名前缀的 USER 轮次；
     * 连续 USER 轮次合并为一条，避免部分厂商拒绝连续同角色消息。
     */
    @Event(eventCode = "BUILD_CHAT_TURNS", eventName = "构建对话轮次")
    private List<ChatTurn> toTurns(Agent self, List<GroupMessage> messages,
                                   Function<GroupMessage, String> senderNameOf) {
        List<ChatTurn> turns = new ArrayList<>();
        StringBuilder userBuffer = new StringBuilder();
        Map<Long, String> nameCache = new HashMap<>();
        for (GroupMessage m : messages) {
            boolean isSelf = m.getSenderType() == SenderType.AGENT && self.getId().equals(m.getSenderId());
            if (isSelf) {
                if (!userBuffer.isEmpty()) {
                    turns.add(ChatTurn.user(userBuffer.toString()));
                    userBuffer.setLength(0);
                }
                turns.add(ChatTurn.assistant(m.getContent()));
            } else {
                String name = nameCache.computeIfAbsent(
                        cacheKey(m), k -> senderNameOf.apply(m));
                if (!userBuffer.isEmpty()) {
                    userBuffer.append('\n');
                }
                userBuffer.append(name).append(": ").append(m.getContent());
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

    private Long cacheKey(GroupMessage m) {
        long typeBit = m.getSenderType() == SenderType.AGENT ? 1_000_000_000L : 0L;
        return typeBit + (m.getSenderId() == null ? 0 : m.getSenderId());
    }

    /** LLM 调用上下文 */
    public record LlmContext(String systemPrompt, List<ChatTurn> turns) {
    }
}
