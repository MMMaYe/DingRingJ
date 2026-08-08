package com.dingring.infrastructure.persistence.mapper;

import com.dingring.domain.knowledgebase.KnowledgeBase;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 知识库表 Mapper（低配版 DDD：直接操作领域对象）。
 * <p>SQL 与结果映射见 resources/mapper/KnowledgeBaseMapper.xml
 */
@Mapper
public interface KnowledgeBaseMapper {

    int insert(KnowledgeBase kb);

    KnowledgeBase findById(Long id);

    List<KnowledgeBase> findAll();

    int update(KnowledgeBase kb);

    int deleteById(Long id);
}
