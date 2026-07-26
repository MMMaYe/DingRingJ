package com.dingring.infrastructure.persistence.mapper;

import com.dingring.domain.discussion.KnowledgeCard;
import com.dingring.infrastructure.persistence.typehandler.JsonMapTypeHandler;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 知识卡片表 Mapper。
 */
@Mapper
public interface CardMapper {

    String COLUMNS = "id, topic_id, question, answer, category, feature, create_time, update_time";

    @Select("SELECT " + COLUMNS + " FROM knowledge_card WHERE topic_id = #{topicId} ORDER BY id ASC")
    @Results(id = "cardMap", value = {
            @Result(column = "id", property = "id", id = true),
            @Result(column = "topic_id", property = "topicId"),
            @Result(column = "question", property = "question"),
            @Result(column = "answer", property = "answer"),
            @Result(column = "category", property = "category"),
            @Result(column = "feature", property = "feature", typeHandler = JsonMapTypeHandler.class),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "update_time", property = "updateTime")
    })
    List<KnowledgeCard> findByTopicId(Long topicId);

    @Select("SELECT " + COLUMNS + " FROM knowledge_card WHERE category = #{category} ORDER BY id ASC")
    @ResultMap("cardMap")
    List<KnowledgeCard> findByCategory(String category);

    @Select("SELECT " + COLUMNS + " FROM knowledge_card ORDER BY id ASC")
    @ResultMap("cardMap")
    List<KnowledgeCard> findAllCards();

    @Select("SELECT DISTINCT category FROM knowledge_card WHERE category IS NOT NULL ORDER BY category ASC")
    List<String> findAllCategories();

    @Insert("INSERT INTO knowledge_card (topic_id, question, answer, category, feature, create_time, update_time) "
            + "VALUES (#{topicId}, #{question}, #{answer}, #{category}, "
            + "#{feature,typeHandler=com.dingring.infrastructure.persistence.typehandler.JsonMapTypeHandler}, "
            + "#{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(KnowledgeCard card);
}
