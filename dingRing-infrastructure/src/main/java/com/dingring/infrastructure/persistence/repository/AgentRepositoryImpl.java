package com.dingring.infrastructure.persistence.repository;

import com.dingring.domain.agent.Agent;
import com.dingring.domain.agent.AgentRepository;
import com.dingring.infrastructure.persistence.mapper.AgentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Agent 仓储实现。
 */
@Repository
@RequiredArgsConstructor
public class AgentRepositoryImpl implements AgentRepository {

    private final AgentMapper agentMapper;

    @Override
    public Optional<Agent> findById(Long id) {
        return Optional.ofNullable(agentMapper.findById(id));
    }

    @Override
    public List<Agent> findByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return agentMapper.findByIds(ids);
    }

    @Override
    public List<Agent> findAll() {
        return agentMapper.findAll();
    }

    @Override
    public Optional<Agent> findRouteJudge() {
        return Optional.ofNullable(agentMapper.findRouteJudge());
    }

    @Override
    public Long save(Agent agent) {
        LocalDateTime now = LocalDateTime.now();
        agent.setCreateTime(now);
        agent.setUpdateTime(now);
        agentMapper.insert(agent);
        return agent.getId();
    }

    @Override
    public void update(Agent agent) {
        agent.setUpdateTime(LocalDateTime.now());
        agentMapper.update(agent);
    }

    @Override
    public void deleteById(Long id) {
        agentMapper.deleteById(id);
    }
}
