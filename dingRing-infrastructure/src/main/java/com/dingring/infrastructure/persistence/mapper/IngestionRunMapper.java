package com.dingring.infrastructure.persistence.mapper;

import com.dingring.domain.knowledgebase.IngestionRun;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface IngestionRunMapper {

    int insert(IngestionRun run);

    IngestionRun findByRunId(String runId);

    IngestionRun findActiveByFileId(Long fileId);

    List<IngestionRun> findRecoverable();

    List<IngestionRun> findCleaningRuns();

    int update(IngestionRun run);
}
