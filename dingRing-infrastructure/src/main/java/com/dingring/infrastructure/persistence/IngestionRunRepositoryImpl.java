package com.dingring.infrastructure.persistence;

import com.dingring.domain.knowledgebase.IngestionRun;
import com.dingring.domain.knowledgebase.IngestionRunRepository;
import com.dingring.infrastructure.persistence.mapper.IngestionRunMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class IngestionRunRepositoryImpl implements IngestionRunRepository {

    private final IngestionRunMapper mapper;

    @Override
    public IngestionRun save(IngestionRun run) {
        LocalDateTime now = LocalDateTime.now();
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        mapper.insert(run);
        return run;
    }

    @Override
    public Optional<IngestionRun> findByRunId(String runId) {
        return Optional.ofNullable(mapper.findByRunId(runId));
    }

    @Override
    public Optional<IngestionRun> findActiveByFileId(Long fileId) {
        return Optional.ofNullable(mapper.findActiveByFileId(fileId));
    }

    @Override
    public List<IngestionRun> findRecoverable() {
        return mapper.findRecoverable();
    }

    @Override
    public List<IngestionRun> findCleaningRuns() {
        return mapper.findCleaningRuns();
    }

    @Override
    public boolean update(IngestionRun run) {
        run.setUpdatedAt(LocalDateTime.now());
        return mapper.update(run) > 0;
    }
}
