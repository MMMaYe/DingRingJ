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

    /** 主题内观点消息列表（feature 标签为 KEY/VIEWPOINT，取最新 N 条后时间升序返回） */
    List<GroupMessage> findViewpointsByTopicId(@Param("topicId") Long topicId, @Param("limit") int limit);

    /**
     * 回写消息 feature（标签与观点摘要统一存于 feature JSON，见 GroupMessage.FEATURE_TAG）。
     *
     * @return 实际更新条数
     */
    int updateFeature(@Param("messageId") Long messageId,
                      @Param("feature") Map<String, Object> feature);
}
