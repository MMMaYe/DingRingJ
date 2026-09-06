package com.dingring.infrastructure.persistence.mapper;

import com.dingring.domain.discussion.Topic;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 主题表 Mapper。
 * <p>SQL 与结果映射见 resources/mapper/TopicMapper.xml
 */
@Mapper
public interface TopicMapper {

    Topic findById(Long id);

    Topic findActiveByGroupId(Long groupId);

    List<Topic> findByGroupId(Long groupId);

    List<Topic> findClosedByGroupId(Long groupId);

    List<Topic> findConcludingBefore(@Param("threshold") LocalDateTime threshold);

    List<Topic> findClosedSince(@Param("since") LocalDateTime since);

    List<Topic> findAllClosed();

    /** 全部话题 id（向量对账：与 topic_id_store 比对找孤儿） */
    List<Long> findAllIds();

    int insert(Topic topic);

    /** 乐观锁更新：version 匹配才生效，成功后 version + 1 */
    int updateWithVersion(Topic topic);
}
