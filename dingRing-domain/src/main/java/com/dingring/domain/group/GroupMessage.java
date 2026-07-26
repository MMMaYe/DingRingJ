package com.dingring.domain.group;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 群消息实体（表 message）。
 * <p>topic_id 由后端入库时自动填充：查群的活跃 Topic，有则归属，无则为 null（闲聊）。
 */
@Data
public class GroupMessage {

    private Long id;
    /** 所属群 */
    private Long chatGroupId;
    /** 所属主题（可空，null=闲聊） */
    private Long topicId;
    /** 发送者 ID */
    private Long senderId;
    /** USER / AGENT / SYSTEM */
    private SenderType senderType;
    /** TEXT / IMAGE / FILE / SYSTEM_NOTICE */
    private MessageType messageType;
    /** 消息内容（支持 Markdown） */
    private String content;
    /** 回复的消息 ID（可空） */
    private Long replyToMessageId;
    /** 扩展字段 */
    private Map<String, Object> feature;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
