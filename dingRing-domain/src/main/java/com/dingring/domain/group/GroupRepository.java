package com.dingring.domain.group;

import java.util.List;
import java.util.Optional;

/**
 * 群仓储接口。
 */
public interface GroupRepository {

    Optional<Group> findById(Long id);

    List<Group> findAll();

    Long save(Group group);

    void update(Group group);

    void deleteById(Long id);
}
