package com.dingring.domain.discussion;

import java.util.List;

/**
 * 知识卡片仓储接口。
 */
public interface CardRepository {

    List<KnowledgeCard> findByTopicId(Long topicId);

    /** category 为空则查全部 */
    List<KnowledgeCard> findByCategory(String category);

    List<String> findAllCategories();

    void saveBatch(List<KnowledgeCard> cards);
}
