package com.dingring.domain.agent;

import java.util.List;
import java.util.Optional;

/**
 * Agent 仓储接口。
 */
public interface AgentRepository {

    Optional<Agent> findById(Long id);

    List<Agent> findByIds(List<Long> ids);

    List<Agent> findAll();

    Long save(Agent agent);

    void update(Agent agent);

    void deleteById(Long id);
}
