package com.dingring.infrastructure.persistence.repository;

import com.dingring.domain.discussion.CardRepository;
import com.dingring.domain.discussion.KnowledgeCard;
import com.dingring.infrastructure.persistence.mapper.CardMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 知识卡片仓储实现。
 */
@Repository
@RequiredArgsConstructor
public class CardRepositoryImpl implements CardRepository {

    private final CardMapper cardMapper;

    @Override
    public List<KnowledgeCard> findByTopicId(Long topicId) {
        return cardMapper.findByTopicId(topicId);
    }

    @Override
    public List<KnowledgeCard> findByCategory(String category) {
        if (category == null || category.isBlank()) {
            return cardMapper.findAllCards();
        }
        return cardMapper.findByCategory(category);
    }

    @Override
    public List<String> findAllCategories() {
        return cardMapper.findAllCategories();
    }

    @Override
    public void saveBatch(List<KnowledgeCard> cards) {
        LocalDateTime now = LocalDateTime.now();
        for (KnowledgeCard card : cards) {
            card.setCreateTime(now);
            card.setUpdateTime(now);
            cardMapper.insert(card);
        }
    }
}
