package com.dingring.infrastructure.persistence;

import com.dingring.domain.knowledgebase.File;
import com.dingring.domain.knowledgebase.FileRepository;
import com.dingring.infrastructure.persistence.mapper.FileMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 知识库文件仓储实现（Phase E）。
 * <p>时间戳由仓储统一维护；摄入管道仅通过 update 回写状态/切片数/错误信息。
 */
@Repository
@RequiredArgsConstructor
public class FileRepositoryImpl implements FileRepository {

    private final FileMapper fileMapper;

    @Override
    public File save(File file) {
        LocalDateTime now = LocalDateTime.now();
        file.setCreateTime(now);
        file.setUpdateTime(now);
        fileMapper.insert(file);
        return file;
    }

    @Override
    public Optional<File> findById(Long id) {
        return Optional.ofNullable(fileMapper.findById(id));
    }

    @Override
    public List<File> findByKnowledgeBaseId(Long knowledgeBaseId) {
        return fileMapper.findByKnowledgeBaseId(knowledgeBaseId);
    }

    @Override
    public List<File> findAll() {
        return fileMapper.findAll();
    }

    @Override
    public boolean update(File file) {
        file.setUpdateTime(LocalDateTime.now());
        return fileMapper.update(file) > 0;
    }

    @Override
    public boolean deleteById(Long id) {
        return fileMapper.deleteById(id) > 0;
    }
}
