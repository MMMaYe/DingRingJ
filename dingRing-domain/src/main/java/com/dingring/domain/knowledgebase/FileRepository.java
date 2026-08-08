package com.dingring.domain.knowledgebase;

import java.util.List;
import java.util.Optional;

/**
 * 知识库文件仓储端口（依赖倒置，infrastructure 层用 MyBatis 实现）。
 */
public interface FileRepository {

    File save(File file);

    Optional<File> findById(Long id);

    List<File> findByKnowledgeBaseId(Long knowledgeBaseId);

    boolean update(File file);

    boolean deleteById(Long id);
}
