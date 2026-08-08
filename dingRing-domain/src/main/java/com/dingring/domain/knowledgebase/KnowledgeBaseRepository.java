package com.dingring.domain.knowledgebase;

import java.util.List;
import java.util.Optional;

/**
 * 知识库仓储端口（依赖倒置，infrastructure 层用 MyBatis 实现）。
 */
public interface KnowledgeBaseRepository {

    KnowledgeBase save(KnowledgeBase kb);

    Optional<KnowledgeBase> findById(Long id);

    List<KnowledgeBase> findAll();

    boolean update(KnowledgeBase kb);

    boolean deleteById(Long id);
}
