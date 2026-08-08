package com.dingring.app.event;

import com.dingring.common.constant.WsConstants;
import com.dingring.common.constant.PromptConstants;
import com.dingring.common.util.LogHelper;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.agent.AgentRepository;
import com.dingring.domain.discussion.CardRepository;
import com.dingring.domain.discussion.KnowledgeCard;
import com.dingring.domain.event.KnowledgeCardGenerated;
import com.dingring.domain.event.TopicClosed;
import com.dingring.domain.service.DomainEventPublisher;
import com.dingring.domain.service.GroupBroadcastService;
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

    private final AgentRepository agentRepository;
    private final CardRepository cardRepository;
    private final LlmService llmService;
    private final DomainEventPublisher eventPublisher;
    private final GroupBroadcastService groupBroadcastService;
    private final ObjectMapper objectMapper;

    @EventListener
    public void onTopicClosed(TopicClosed event) {
        // 独立虚拟线程执行，不阻塞群执行器
        Thread.ofVirtual().name("card-gen-" + event.getTopicId()).start(() -> generateCards(event));
    }

    /**
     * 生成卡片（幂等：已有卡片则跳过）；{@code CardReconciler} 定时对账补偿同一入口。
     */
    public void generateCards(TopicClosed event) {
        if (!cardRepository.findByTopicId(event.getTopicId()).isEmpty()) {
            LogHelper.printLog(CardEventHandler.class, "CardEventHandler.generateCards", "GENERATE_CARDS", "卡片生成跳过已有卡片", "topicId={}", event.getTopicId());
            return;
        }
        Agent concluder = agentRepository.findById(event.getConcluderAgentId()).orElse(null);
        if (concluder == null) {
            LogHelper.printWarnLog(CardEventHandler.class, "CardEventHandler.generateCards", "GENERATE_CARDS", "总结Agent不存在跳过", "topicId={}", event.getTopicId());
            return;
        }
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                String raw = llmService.chat(concluder, PromptConstants.KNOWLEDGE_EXTRACT,
                        List.of(LlmService.ChatTurn.user("讨论主题：" + event.getTitle()
                                + "\n\n讨论结论：\n" + event.getConclusion())),
                        new LlmService.CallOptions(0.0, 2048, null,
                                true, false));  // jsonMode=true, logReasoning=false
                List<KnowledgeCard> cards = parseCards(raw, event.getTopicId());
                if (cards.isEmpty()) {
                    throw new IllegalStateException("LLM 未提取到有效卡片");
                }
                cardRepository.saveBatch(cards);
                eventPublisher.publish(new KnowledgeCardGenerated(event.getTopicId(), event.getGroupId(), cards));
                groupBroadcastService.broadcast(event.getGroupId(), WsConstants.CARD_GENERATED, Map.of(
                        "topicId", event.getTopicId(),
                        "cardCount", cards.size(),
                        "cards", cards.stream().map(c -> Map.of(
                                "id", c.getId(),
                                "question", c.getQuestion(),
                                "category", c.getCategory() == null ? "未分类" : c.getCategory()))
                                .collect(Collectors.toList())));
                LogHelper.printLog(CardEventHandler.class, "CardEventHandler.generateCards", "GENERATE_CARDS", "卡片生成成功", "topicId={} count={}", event.getTopicId(), cards.size());
                return;
            } catch (Exception e) {
                LogHelper.printWarnLog(CardEventHandler.class, "CardEventHandler.generateCards", "GENERATE_CARDS", "卡片生成失败重试",
                        "attempt={}/{} topicId={}", attempt, MAX_RETRY, event.getTopicId(), e);
            }
        }
        // 重试 3 次仍失败：记录日志，不阻塞主流程
        LogHelper.printWarnLog(CardEventHandler.class, "CardEventHandler.generateCards", "GENERATE_CARDS", "卡片生成最终失败", "topicId={}", event.getTopicId());
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
