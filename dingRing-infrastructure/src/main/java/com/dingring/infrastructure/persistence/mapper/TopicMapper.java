package com.dingring.infrastructure.persistence.mapper;

import com.dingring.domain.discussion.Topic;
import org.apache.ibatis.annotations.Mapper;

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

    int insert(Topic topic);

    /** 乐观锁更新：version 匹配才生效，成功后 version + 1 */
    int updateWithVersion(Topic topic);
}
