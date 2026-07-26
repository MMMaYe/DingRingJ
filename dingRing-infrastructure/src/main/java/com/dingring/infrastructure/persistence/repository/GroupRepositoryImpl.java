package com.dingring.infrastructure.persistence.repository;

import com.dingring.domain.group.Group;
import com.dingring.domain.group.GroupRepository;
import com.dingring.infrastructure.persistence.mapper.GroupMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 群仓储实现。
 */
@Repository
@RequiredArgsConstructor
public class GroupRepositoryImpl implements GroupRepository {

    private final GroupMapper groupMapper;

    @Override
    public Optional<Group> findById(Long id) {
        return Optional.ofNullable(groupMapper.findById(id));
    }

    @Override
    public List<Group> findAll() {
        return groupMapper.findAll();
    }

    @Override
    public Long save(Group group) {
        LocalDateTime now = LocalDateTime.now();
        group.setCreateTime(now);
        group.setUpdateTime(now);
        groupMapper.insert(group);
        return group.getId();
    }

    @Override
    public void update(Group group) {
        group.setUpdateTime(LocalDateTime.now());
        groupMapper.update(group);
    }

    @Override
    public void deleteById(Long id) {
        groupMapper.deleteById(id);
    }
}
