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

    /** 全量文件（对账任务用：孤儿向量检测需跨知识库比对） */
    List<File> findAll();

    boolean update(File file);

    boolean deleteById(Long id);
}
