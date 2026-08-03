package com.dingring.infrastructure.persistence.mapper;

import com.dingring.domain.group.GroupMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 群消息表 Mapper。
 * <p>SQL 与结果映射见 resources/mapper/MessageMapper.xml
 */
@Mapper
public interface MessageMapper {

    GroupMessage findById(Long id);

    List<GroupMessage> findByIds(@Param("ids") List<Long> ids);

    int insert(GroupMessage message);

    List<GroupMessage> findByTopicId(@Param("topicId") Long topicId,
                                     @Param("offset") int offset,
                                     @Param("limit") int limit);

    long countByTopicId(Long topicId);

    List<GroupMessage> findByGroupId(@Param("groupId") Long groupId,
                                     @Param("offset") int offset,
                                     @Param("limit") int limit);

    long countByGroupId(Long groupId);

    List<GroupMessage> findRecentByTopicId(@Param("topicId") Long topicId, @Param("limit") int limit);

    List<GroupMessage> findRecentByGroupId(@Param("groupId") Long groupId, @Param("limit") int limit);

    long countByTopicIdAndSender(@Param("topicId") Long topicId,
                                 @Param("senderId") Long senderId,
                                 @Param("senderType") String senderType);

    long countByTopicIdAndSenderType(@Param("topicId") Long topicId,
                                     @Param("senderType") String senderType);

    GroupMessage findLastByGroupId(Long groupId);

    List<GroupMessage> findRecentChatByGroupId(@Param("groupId") Long groupId, @Param("limit") int limit);

    int updateTopicId(@Param("messageIds") List<Long> messageIds, @Param("topicId") Long topicId);
}
