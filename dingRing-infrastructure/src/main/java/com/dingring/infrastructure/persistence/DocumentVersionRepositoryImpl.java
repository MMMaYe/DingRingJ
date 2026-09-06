package com.dingring.infrastructure.persistence;

import com.dingring.domain.knowledgebase.DocumentVersion;
import com.dingring.domain.knowledgebase.DocumentVersionRepository;
import com.dingring.infrastructure.persistence.mapper.DocumentVersionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class DocumentVersionRepositoryImpl implements DocumentVersionRepository {

    private final DocumentVersionMapper mapper;

    @Override
    public DocumentVersion save(DocumentVersion version) {
        LocalDateTime now = LocalDateTime.now();
        version.setCreatedAt(now);
        version.setUpdatedAt(now);
        mapper.insert(version);
        return version;
    }

    @Override
    public Optional<DocumentVersion> findByFileAndVersion(Long fileId, Integer documentVersion) {
        return Optional.ofNullable(mapper.findByFileAndVersion(fileId, documentVersion));
    }

    @Override
    public List<DocumentVersion> findByFileId(Long fileId) {
        return mapper.findByFileId(fileId);
    }

    @Override
    public boolean update(DocumentVersion version) {
        version.setUpdatedAt(LocalDateTime.now());
        return mapper.update(version) > 0;
    }

    @Override
    @Transactional
    public boolean activate(Long fileId, Integer documentVersion) {
        mapper.deactivateAll(fileId);
        return mapper.activate(fileId, documentVersion) > 0;
    }
}
