package com.dingring.app.service;

import com.dingring.app.dto.response.KnowledgeCardDTO;
import com.dingring.app.dto.response.ReviewCardDTO;
import com.dingring.common.exception.BizException;
import com.dingring.common.exception.ErrorCode;
import com.dingring.domain.discussion.CardRepository;
import com.dingring.domain.discussion.KnowledgeCard;
import com.dingring.domain.discussion.Topic;
import com.dingring.domain.discussion.TopicRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识卡片应用服务（列表、按主题、复习）。
 */
@Service
@RequiredArgsConstructor
public class CardAppService {

    private final CardRepository cardRepository;
    private final TopicRepository topicRepository;

    /** 卡片列表（category 为空查全部） */
    public List<KnowledgeCardDTO> list(String category) {
        return toDtos(cardRepository.findByCategory(category));
    }

    /** 主题的知识卡片 */
    public List<KnowledgeCardDTO> listByTopic(Long topicId) {
        return toDtos(cardRepository.findByTopicId(topicId));
    }

    /** 全部分类（筛选面板用） */
    public List<String> categories() {
        return cardRepository.findAllCategories();
    }

    /** 删除卡片（物理删除） */
    public void delete(Long cardId) {
        if (cardRepository.deleteById(cardId) == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "卡片不存在: " + cardId);
        }
    }

    /**
     * 复习卡片。
     *
     * @param order sequential / random
     */
    public ReviewCardDTO review(String category, String order) {
        List<KnowledgeCardDTO> cards = new ArrayList<>(toDtos(cardRepository.findByCategory(category)));
        if ("random".equalsIgnoreCase(order)) {
            Collections.shuffle(cards);
        }
        return new ReviewCardDTO(cards, cards.size());
    }

    private List<KnowledgeCardDTO> toDtos(List<KnowledgeCard> cards) {
        Map<Long, String> titleCache = new HashMap<>();
        return cards.stream().map(card -> KnowledgeCardDTO.builder()
                .id(card.getId())
                .topicId(card.getTopicId())
                .topicTitle(titleCache.computeIfAbsent(card.getTopicId(),
                        id -> topicRepository.findById(id).map(Topic::getTitle).orElse(null)))
                .question(card.getQuestion())
                .answer(card.getAnswer())
                .category(card.getCategory())
                .createTime(card.getCreateTime())
                .build()).toList();
    }
}
