package com.dingring.app.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息 DTO（REST 分页与 WS NEW_MESSAGE 共用）。
 */
@Data
@Builder
public class MessageDTO {

    private Long id;
    private Long groupId;
    private Long topicId;
    private Long senderId;
    /** 冗余字段，前端直接展示 */
    private String senderName;
    /** USER / AGENT / SYSTEM */
    private String senderType;
    private String senderAvatar;
    /** TEXT / SYSTEM_NOTICE */
    private String messageType;
    /** 支持 Markdown */
    private String content;
    private Long replyToMessageId;
    /** 冗余，前端展示引用来源 */
    private String replyToSenderName;
    /** 冗余，引用消息内容截断 */
    private String replyToContent;
    private LocalDateTime createTime;
}
