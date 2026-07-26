package com.dingring.domain.discussion;

import java.util.List;
import java.util.Optional;

/**
 * 主题仓储接口。
 */
public interface TopicRepository {

    Optional<Topic> findById(Long id);

    /** 群的活跃 Topic（status = IN_PROGRESS） */
    Optional<Topic> findActiveByGroupId(Long groupId);

    /** 群的所有主题（按创建时间倒序） */
    List<Topic> findByGroupId(Long groupId);

    /** 同群已 CLOSED 的历史主题（记忆检索用） */
    List<Topic> findClosedByGroupId(Long groupId);

    Long save(Topic topic);

    /**
     * 乐观锁更新（version 匹配才更新）。
     *
     * @return true 更新成功
     */
    boolean update(Topic topic);
}
