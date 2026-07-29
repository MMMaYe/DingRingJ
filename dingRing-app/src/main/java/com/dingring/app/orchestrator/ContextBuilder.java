package com.dingring.app.orchestrator;

import com.dingring.app.service.GroupAppService;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.group.GroupMessage;
import com.dingring.domain.group.MessageRepository;
import com.dingring.domain.group.SenderType;
import com.dingring.domain.service.LlmService.ChatTurn;
import com.dingring.domain.service.MemoryService;
import com.dingring.domain.service.ProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 上下文构建：Agent 人设 + 滑动窗口最近 N 条消息 + 历史 Topic 结论记忆（见技术方案 6.3）。
 * <p>群聊语境：所有消息拼上发送者花名前缀合成 USER 轮次，Agent 自己的历史发言为 ASSISTANT 轮次。
 */
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
    private final MemoryService memoryService;
    private final ProfileService profileService;

    /** 滑动窗口大小（可配置，默认 200 条） */
    @Value("${dingring.orchestrator.context-window:200}")
    private int contextWindow;

    /** 讨论态附带的近期闲聊条数（群氛围前置段） */
    @Value("${dingring.orchestrator.chat-context-window:20}")
    private int chatContextWindow;

    /**
     * 构建 Agent 发言的完整上下文。
     *
     * @param agent           发言 Agent
     * @param groupId         群 ID
     * @param topicId         主题 ID（null = 闲聊，取群窗口）
     * @param senderNameOf    发送者名称解析函数
     * @return system prompt + 对话轮次
     */
    public LlmContext build(Agent agent, Long groupId, Long topicId,
                            Function<GroupMessage, String> senderNameOf) {
        StringBuilder systemPrompt = new StringBuilder(buildSystemPrompt(agent, groupId));
        if (topicId != null) {
            // 协作协议：自主收束 + 跳过本轮
            systemPrompt.append("\n\n协作协议：")
                    .append("\n1. 如果你认为当前主题已经讨论充分、可以收尾总结，")
                    .append("请在本次发言的末尾另起一行输出标记 ").append(CONCLUDE_MARKER)
                    .append("（仅在确实认为可以结束时输出，其他情况绝不要提及或输出该标记）。")
                    .append("\n2. 如果你对当前讨论没有新的观点或补充，请只输出 ").append(PASS_MARKER)
                    .append("（不要输出其他任何内容）；有实质内容时绝不要输出该标记。")
                    .append("不要为了发言而发言，重复已有观点不如 ").append(PASS_MARKER).append("。");
        }
        List<GroupMessage> window = topicId != null
                ? mergeChatContext(groupId, messageRepository.findRecentByTopicId(topicId, contextWindow))
                : messageRepository.findRecentByGroupId(groupId, contextWindow);
        List<ChatTurn> turns = toTurns(agent, window, senderNameOf);
        return new LlmContext(systemPrompt.toString(), turns);
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

    /** 结论生成上下文：当前 Topic 全部消息 + 历史记忆（不走滑动窗口截断的 system 部分） */
    public LlmContext buildForConclusion(Agent concluder, Long groupId, Long topicId, String topicTitle,
                                         Function<GroupMessage, String> senderNameOf) {
        StringBuilder sp = new StringBuilder();
        if (concluder.getSystemPrompt() != null && !concluder.getSystemPrompt().isBlank()) {
            sp.append(concluder.getSystemPrompt()).append("\n\n");
        }
        sp.append("你被推选为本次群讨论的总结者。请针对主题「").append(topicTitle)
                .append("」，基于完整讨论记录，用 STAR 框架（Situation/Task/Action/Result）")
                .append("输出 Markdown 格式的讨论结论，并对各成员观点做简要点评。");
        String memory = memoryService.retrieveMemory(groupId);
        if (!memory.isBlank()) {
            sp.append("\n\n").append(memory);
        }
        List<GroupMessage> all = messageRepository.findRecentByTopicId(topicId, contextWindow);
        return new LlmContext(sp.toString(), toTurns(concluder, all, senderNameOf));
    }

    private String buildSystemPrompt(Agent agent, Long groupId) {
        StringBuilder sp = new StringBuilder();
        if (agent.getSystemPrompt() != null && !agent.getSystemPrompt().isBlank()) {
            sp.append(agent.getSystemPrompt());
        }
        sp.append("\n\n你正在参与一个多人群聊讨论，你的花名是「").append(agent.getName())
                .append("」。历史消息以「花名: 内容」形式给出。请直接输出你的发言内容，")
                .append("不要重复花名前缀，保持简洁聚焦，与前面的讨论衔接。");
        String memory = memoryService.retrieveMemory(groupId);
        if (!memory.isBlank()) {
            sp.append("\n\n").append(memory);
        }
        // 跨群用户画像：让 Agent 更懂用户的表达习惯/情绪基调/思考方式
        String profile = profileService.getProfile(GroupAppService.DEFAULT_USER_ID);
        if (!profile.isBlank()) {
            sp.append("\n\n关于群里用户的画像记忆（长期观察所得，供你更懂他/她，不要直接复述）：\n")
                    .append(profile);
        }
        return sp.toString();
    }

    /**
     * 消息 → 对话轮次：Agent 自己的发言为 ASSISTANT，其余合并为带花名前缀的 USER 轮次；
     * 连续 USER 轮次合并为一条，避免部分厂商拒绝连续同角色消息。
     */
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
        // senderType 与 senderId 组合防止 USER/AGENT id 冲突
        long typeBit = m.getSenderType() == SenderType.AGENT ? 1_000_000_000L : 0L;
        return typeBit + (m.getSenderId() == null ? 0 : m.getSenderId());
    }

    /** LLM 调用上下文 */
    public record LlmContext(String systemPrompt, List<ChatTurn> turns) {
    }
}
