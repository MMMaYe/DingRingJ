package com.dingring.domain.user;

import java.util.List;
import java.util.Optional;

/**
 * 用户仓储接口（infrastructure 层实现）。
 */
public interface UserRepository {

    Optional<User> findById(Long id);

    List<User> findByIds(List<Long> ids);

    Long save(User user);
}
