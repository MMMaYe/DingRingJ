package com.dingring.domain.knowledgebase;

import java.util.List;
import java.util.Optional;

public interface DocumentVersionRepository {

    DocumentVersion save(DocumentVersion version);

    Optional<DocumentVersion> findByFileAndVersion(Long fileId, Integer documentVersion);

    List<DocumentVersion> findByFileId(Long fileId);

    boolean update(DocumentVersion version);

    boolean activate(Long fileId, Integer documentVersion);
}
