package com.dingring.app.event;

import com.dingring.app.service.ChatPusher;
import com.dingring.common.constant.WsConstants;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.agent.AgentRepository;
import com.dingring.domain.discussion.CardRepository;
import com.dingring.domain.discussion.KnowledgeCard;
import com.dingring.domain.event.KnowledgeCardGenerated;
import com.dingring.domain.event.TopicClosed;
import com.dingring.domain.service.DomainEventPublisher;
import com.dingring.domain.service.LlmService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 知识卡片域：监听 TopicClosed，从结论提取 Q&A 卡片（异步，重试 3 次，失败不阻塞主流程）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CardEventHandler {

    private static final int MAX_RETRY = 3;

    private static final String CARD_PROMPT = """
            你是知识卡片提取助手。请从给定的讨论结论中提取 3-5 张知识卡片（Q&A 对），\
            并为每张卡片识别分类。严格输出 JSON 数组，不要输出任何其他内容，格式：\
            [{"question": "...", "answer": "...", "category": "..."}]""";

    private final AgentRepository agentRepository;
    private final CardRepository cardRepository;
    private final LlmService llmService;
    private final DomainEventPublisher eventPublisher;
    private final ChatPusher chatPusher;
    private final ObjectMapper objectMapper;

    @EventListener
    public void onTopicClosed(TopicClosed event) {
        // 独立虚拟线程执行，不阻塞群执行器
        Thread.ofVirtual().name("card-gen-" + event.getTopicId()).start(() -> generateCards(event));
    }

    private void generateCards(TopicClosed event) {
        Agent expert = agentRepository.findById(event.getExpertAgentId()).orElse(null);
        if (expert == null) {
            log.warn("卡片生成跳过：专家 Agent 不存在, topicId={}", event.getTopicId());
            return;
        }
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                String raw = llmService.chat(expert, CARD_PROMPT,
                        List.of(LlmService.ChatTurn.user("讨论主题：" + event.getTitle()
                                + "\n\n讨论结论：\n" + event.getConclusion())));
                List<KnowledgeCard> cards = parseCards(raw, event.getTopicId());
                if (cards.isEmpty()) {
                    throw new IllegalStateException("LLM 未提取到有效卡片");
                }
                cardRepository.saveBatch(cards);
                eventPublisher.publish(new KnowledgeCardGenerated(event.getTopicId(), event.getGroupId(), cards));
                chatPusher.pushToGroup(event.getGroupId(), WsConstants.CARD_GENERATED, Map.of(
                        "topicId", event.getTopicId(),
                        "cardCount", cards.size(),
                        "cards", cards.stream().map(c -> Map.of(
                                "id", c.getId(),
                                "question", c.getQuestion(),
                                "category", c.getCategory() == null ? "未分类" : c.getCategory()))
                                .collect(Collectors.toList())));
                log.info("知识卡片生成成功, topicId={}, count={}", event.getTopicId(), cards.size());
                return;
            } catch (Exception e) {
                log.warn("知识卡片生成失败({}/{}), topicId={}", attempt, MAX_RETRY, event.getTopicId(), e);
            }
        }
        // 重试 3 次仍失败：记录日志，不阻塞主流程
        log.error("知识卡片生成最终失败, topicId={}", event.getTopicId());
    }

    /** 解析 LLM 输出（容忍 ```json 代码块包裹） */
    private List<KnowledgeCard> parseCards(String raw, Long topicId) throws Exception {
        String json = raw.trim();
        if (json.startsWith("```")) {
            json = json.replaceAll("^```(json)?\\s*", "").replaceAll("```\\s*$", "").trim();
        }
        int start = json.indexOf('[');
        int end = json.lastIndexOf(']');
        if (start >= 0 && end > start) {
            json = json.substring(start, end + 1);
        }
        List<Map<String, String>> items = objectMapper.readValue(json, new TypeReference<>() {
        });
        return items.stream()
                .filter(m -> m.get("question") != null && m.get("answer") != null)
                .map(m -> {
                    KnowledgeCard card = new KnowledgeCard();
                    card.setTopicId(topicId);
                    card.setQuestion(m.get("question"));
                    card.setAnswer(m.get("answer"));
                    card.setCategory(m.getOrDefault("category", "未分类"));
                    return card;
                })
                .toList();
    }
}
