package com.dingring.domain.knowledgebase;

import java.util.List;
import java.util.Optional;

public interface IngestionRunRepository {

    IngestionRun save(IngestionRun run);

    Optional<IngestionRun> findByRunId(String runId);

    Optional<IngestionRun> findActiveByFileId(Long fileId);

    List<IngestionRun> findRecoverable();

    /** 清洗中（CLEANING_WAITING/CLEANING_RUNNING）的 run 列表（对账告警用） */
    List<IngestionRun> findCleaningRuns();

    boolean update(IngestionRun run);
}
