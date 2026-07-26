package com.dingring.infrastructure.persistence.mapper;

import com.dingring.domain.group.GroupMessage;
import com.dingring.infrastructure.persistence.typehandler.JsonMapTypeHandler;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 群消息表 Mapper。
 */
@Mapper
public interface MessageMapper {

    String COLUMNS = "id, chat_group_id, topic_id, sender_id, sender_type, message_type, content, "
            + "reply_to_message_id, feature, create_time, update_time";

    @Select("SELECT " + COLUMNS + " FROM message WHERE id = #{id}")
    @Results(id = "messageMap", value = {
            @Result(column = "id", property = "id", id = true),
            @Result(column = "chat_group_id", property = "chatGroupId"),
            @Result(column = "topic_id", property = "topicId"),
            @Result(column = "sender_id", property = "senderId"),
            @Result(column = "sender_type", property = "senderType"),
            @Result(column = "message_type", property = "messageType"),
            @Result(column = "content", property = "content"),
            @Result(column = "reply_to_message_id", property = "replyToMessageId"),
            @Result(column = "feature", property = "feature", typeHandler = JsonMapTypeHandler.class),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "update_time", property = "updateTime")
    })
    GroupMessage findById(Long id);

    @Insert("INSERT INTO message (chat_group_id, topic_id, sender_id, sender_type, message_type, content, "
            + "reply_to_message_id, feature, create_time, update_time) "
            + "VALUES (#{chatGroupId}, #{topicId}, #{senderId}, #{senderType}, #{messageType}, #{content}, "
            + "#{replyToMessageId}, "
            + "#{feature,typeHandler=com.dingring.infrastructure.persistence.typehandler.JsonMapTypeHandler}, "
            + "#{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(GroupMessage message);

    @Select("SELECT " + COLUMNS + " FROM message WHERE topic_id = #{topicId} "
            + "ORDER BY create_time ASC, id ASC LIMIT #{limit} OFFSET #{offset}")
    @ResultMap("messageMap")
    List<GroupMessage> findByTopicId(@Param("topicId") Long topicId, @Param("offset") int offset, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM message WHERE topic_id = #{topicId}")
    long countByTopicId(Long topicId);

    @Select("SELECT " + COLUMNS + " FROM message WHERE chat_group_id = #{groupId} "
            + "ORDER BY create_time ASC, id ASC LIMIT #{limit} OFFSET #{offset}")
    @ResultMap("messageMap")
    List<GroupMessage> findByGroupId(@Param("groupId") Long groupId, @Param("offset") int offset, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM message WHERE chat_group_id = #{groupId}")
    long countByGroupId(Long groupId);

    @Select("SELECT * FROM (SELECT " + COLUMNS + " FROM message WHERE topic_id = #{topicId} "
            + "ORDER BY create_time DESC, id DESC LIMIT #{limit}) t ORDER BY t.create_time ASC, t.id ASC")
    @ResultMap("messageMap")
    List<GroupMessage> findRecentByTopicId(@Param("topicId") Long topicId, @Param("limit") int limit);

    @Select("SELECT * FROM (SELECT " + COLUMNS + " FROM message WHERE chat_group_id = #{groupId} "
            + "ORDER BY create_time DESC, id DESC LIMIT #{limit}) t ORDER BY t.create_time ASC, t.id ASC")
    @ResultMap("messageMap")
    List<GroupMessage> findRecentByGroupId(@Param("groupId") Long groupId, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM message WHERE topic_id = #{topicId} AND sender_id = #{senderId} AND sender_type = #{senderType}")
    long countByTopicIdAndSender(@Param("topicId") Long topicId, @Param("senderId") Long senderId,
                                 @Param("senderType") String senderType);

    @Select("SELECT COUNT(*) FROM message WHERE topic_id = #{topicId} AND sender_type = #{senderType}")
    long countByTopicIdAndSenderType(@Param("topicId") Long topicId, @Param("senderType") String senderType);

    @Select("SELECT " + COLUMNS + " FROM message WHERE chat_group_id = #{groupId} "
            + "ORDER BY create_time DESC, id DESC LIMIT 1")
    @ResultMap("messageMap")
    GroupMessage findLastByGroupId(Long groupId);
}
