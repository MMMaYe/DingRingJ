package com.dingring.app.event;

import com.dingring.common.constant.WsConstants;
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
import com.dingring.domain.skill.SkillLoaderService;
import com.dingring.domain.skill.SkillScene;
import com.dingring.infrastructure.prompt.PromptTemplateLoader;
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
    /** 提示词模板加载器：Nacos 优先，失效兜底 prompt-config.json（提示词单一来源） */
    private final PromptTemplateLoader promptLoader;
    /** 沉淀场景技能加载：card 技能是运营增量规范，追加在出厂模板之后（依赖倒置，infrastructure 实现） */
    private final SkillLoaderService skillLoader;

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
                // 模板承载出厂基线规范，card 场景技能是运营增量约束——渲染完成后追加在其后，再交给 LLM
                String systemPrompt = skillLoader.applySceneSkills(SkillScene.CARD,
                        promptLoader.render("sediment", Map.of()));
                String raw = llmService.chat(concluder, systemPrompt,
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

    /** 解析 LLM 输出（容忍 ```json 代码块包裹、单对象代替数组两种情况） */
    private List<KnowledgeCard> parseCards(String raw, Long topicId) throws Exception {
        String json = raw.trim();
        if (json.startsWith("```")) {
            json = json.replaceAll("^```(json)?\\s*", "").replaceAll("```\\s*$", "").trim();
        }
        List<Map<String, String>> items;
        int start = json.indexOf('[');
        int end = json.lastIndexOf(']');
        if (start >= 0 && end > start) {
            json = json.substring(start, end + 1);
            items = objectMapper.readValue(json, new TypeReference<>() {
            });
        } else {
            // 容错：LLM 偶尔只输出单个对象而非数组，包装成单元素列表避免整批重试
            Map<String, String> single = objectMapper.readValue(json, new TypeReference<>() {
            });
            items = single.isEmpty() ? List.of() : List.of(single);
        }
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
