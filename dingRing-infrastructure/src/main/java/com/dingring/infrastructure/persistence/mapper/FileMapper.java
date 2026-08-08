package com.dingring.infrastructure.persistence.mapper;

import com.dingring.domain.knowledgebase.File;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 知识库文件表 Mapper（表名 kb_file，避免与 file 关键字冲突）。
 * <p>SQL 与结果映射见 resources/mapper/FileMapper.xml
 */
@Mapper
public interface FileMapper {

    int insert(File file);

    File findById(Long id);

    List<File> findByKnowledgeBaseId(Long knowledgeBaseId);

    int update(File file);

    int deleteById(Long id);
}
