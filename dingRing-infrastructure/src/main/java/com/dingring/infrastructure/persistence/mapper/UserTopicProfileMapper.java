package com.dingring.infrastructure.persistence.mapper;

import com.dingring.domain.user.UserTopicProfile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 话题级用户画像表 Mapper。
 * <p>SQL 与结果映射见 resources/mapper/UserTopicProfileMapper.xml
 */
@Mapper
public interface UserTopicProfileMapper {

    /** 查询用户在指定话题下的最近一次画像 */
    UserTopicProfile findByUserIdAndTopicId(@Param("userId") Long userId, @Param("topicId") Long topicId);

    /** 按话题标题查全部历史（create_time 倒序，首条=最近一次） */
    List<UserTopicProfile> findByUserIdAndTopicTitle(@Param("userId") Long userId,
                                                     @Param("topicTitle") String topicTitle);

    /** 插入新记录 */
    int insert(UserTopicProfile profile);
}
