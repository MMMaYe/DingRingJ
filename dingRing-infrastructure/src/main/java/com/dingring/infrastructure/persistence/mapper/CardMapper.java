package com.dingring.infrastructure.persistence.mapper;

import com.dingring.domain.discussion.KnowledgeCard;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 知识卡片表 Mapper。
 * <p>SQL 与结果映射见 resources/mapper/CardMapper.xml
 */
@Mapper
public interface CardMapper {

    List<KnowledgeCard> findByTopicId(Long topicId);

    List<KnowledgeCard> findByCategory(String category);

    List<KnowledgeCard> findAllCards();

    List<String> findAllCategories();

    int insert(KnowledgeCard card);

    int deleteById(Long id);
}
