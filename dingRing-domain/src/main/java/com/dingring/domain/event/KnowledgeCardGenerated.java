package com.dingring.domain.event;

import com.dingring.domain.discussion.KnowledgeCard;
import lombok.Getter;

import java.util.List;

/**
 * 知识卡片已生成事件（订阅方：前端通知"新卡片已生成"）。
 */
@Getter
public class KnowledgeCardGenerated extends DomainEvent {

    private final Long topicId;
    private final Long groupId;
    private final List<KnowledgeCard> cards;

    public KnowledgeCardGenerated(Long topicId, Long groupId, List<KnowledgeCard> cards) {
        this.topicId = topicId;
        this.groupId = groupId;
        this.cards = cards;
    }

    public int cardCount() {
        return cards == null ? 0 : cards.size();
    }
}
