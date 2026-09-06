package com.dingring.infrastructure.persistence.mapper;

import com.dingring.domain.knowledgebase.DocumentVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DocumentVersionMapper {

    int insert(DocumentVersion version);

    DocumentVersion findByFileAndVersion(@Param("fileId") Long fileId,
                                         @Param("documentVersion") Integer documentVersion);

    List<DocumentVersion> findByFileId(Long fileId);

    int update(DocumentVersion version);

    int deactivateAll(Long fileId);

    int activate(@Param("fileId") Long fileId,
                 @Param("documentVersion") Integer documentVersion);
}
