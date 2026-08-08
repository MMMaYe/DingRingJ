package com.dingring.infrastructure.persistence;

import com.dingring.domain.knowledgebase.KnowledgeBase;
import com.dingring.domain.knowledgebase.KnowledgeBaseRepository;
import com.dingring.infrastructure.persistence.mapper.KnowledgeBaseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 知识库仓储实现（Phase E）。
 * <p>时间戳由仓储统一维护，避免上层遗漏；update/delete 返回是否命中，便于幂等判断。
 */
@Repository
@RequiredArgsConstructor
public class KnowledgeBaseRepositoryImpl implements KnowledgeBaseRepository {

    private final KnowledgeBaseMapper knowledgeBaseMapper;

    @Override
    public KnowledgeBase save(KnowledgeBase kb) {
        LocalDateTime now = LocalDateTime.now();
        kb.setCreateTime(now);
        kb.setUpdateTime(now);
        knowledgeBaseMapper.insert(kb);
        return kb;
    }

    @Override
    public Optional<KnowledgeBase> findById(Long id) {
        return Optional.ofNullable(knowledgeBaseMapper.findById(id));
    }

    @Override
    public List<KnowledgeBase> findAll() {
        return knowledgeBaseMapper.findAll();
    }

    @Override
    public boolean update(KnowledgeBase kb) {
        kb.setUpdateTime(LocalDateTime.now());
        return knowledgeBaseMapper.update(kb) > 0;
    }

    @Override
    public boolean deleteById(Long id) {
        return knowledgeBaseMapper.deleteById(id) > 0;
    }
}
