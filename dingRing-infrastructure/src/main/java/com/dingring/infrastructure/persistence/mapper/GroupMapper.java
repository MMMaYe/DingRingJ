package com.dingring.infrastructure.persistence.mapper;

import com.dingring.domain.group.Group;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 群表 Mapper（低配版 DDD：直接操作领域对象）。
 * <p>SQL 与结果映射见 resources/mapper/GroupMapper.xml
 */
@Mapper
public interface GroupMapper {

    Group findById(Long id);

    List<Group> findAll();

    int insert(Group group);

    int update(Group group);

    int deleteById(Long id);
}
