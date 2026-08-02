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

    /**
     * 查找路由判定器 Agent（feature.routeJudge=true）。
     * <p>路由判定器不参与群讨论，仅专职做意图分类，独立于群成员配置。
     *
     * @return 若有多个则按 id 取第一个；未配置返回 {@link Optional#empty()}
     */
    Optional<Agent> findRouteJudge();

    Long save(Agent agent);

    void update(Agent agent);

    void deleteById(Long id);
}
