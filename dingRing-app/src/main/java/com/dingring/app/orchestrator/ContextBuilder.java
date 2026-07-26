package com.dingring.app.orchestrator;

import com.dingring.domain.agent.Agent;
import com.dingring.domain.group.GroupMessage;
import com.dingring.domain.group.MessageRepository;
import com.dingring.domain.group.SenderType;
import com.dingring.domain.service.LlmService.ChatTurn;
import com.dingring.domain.service.MemoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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

    private final MessageRepository messageRepository;
    private final MemoryService memoryService;

    /** 滑动窗口大小（可配置，默认 200 条） */
    @Value("${dingring.orchestrator.context-window:200}")
    private int contextWindow;

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
        String systemPrompt = buildSystemPrompt(agent, groupId);
        List<GroupMessage> window = topicId != null
                ? messageRepository.findRecentByTopicId(topicId, contextWindow)
                : messageRepository.findRecentByGroupId(groupId, contextWindow);
        List<ChatTurn> turns = toTurns(agent, window, senderNameOf);
        return new LlmContext(systemPrompt, turns);
    }

    /** 结论生成上下文：当前 Topic 全部消息 + 历史记忆（不走滑动窗口截断的 system 部分） */
    public LlmContext buildForConclusion(Agent expert, Long groupId, Long topicId, String topicTitle,
                                         Function<GroupMessage, String> senderNameOf) {
        StringBuilder sp = new StringBuilder();
        if (expert.getSystemPrompt() != null && !expert.getSystemPrompt().isBlank()) {
            sp.append(expert.getSystemPrompt()).append("\n\n");
        }
        sp.append("你是本次群讨论的专家总结者。请针对主题「").append(topicTitle)
                .append("」，基于完整讨论记录，用 STAR 框架（Situation/Task/Action/Result）")
                .append("输出 Markdown 格式的讨论结论，并对各成员观点做简要点评。");
        String memory = memoryService.retrieveMemory(groupId);
        if (!memory.isBlank()) {
            sp.append("\n\n").append(memory);
        }
        List<GroupMessage> all = messageRepository.findRecentByTopicId(topicId, contextWindow);
        return new LlmContext(sp.toString(), toTurns(expert, all, senderNameOf));
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
