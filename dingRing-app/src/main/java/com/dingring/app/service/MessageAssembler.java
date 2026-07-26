package com.dingring.app.service;

import com.dingring.app.dto.response.MessageDTO;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.agent.AgentRepository;
import com.dingring.domain.group.GroupMessage;
import com.dingring.domain.group.MessageRepository;
import com.dingring.domain.group.SenderType;
import com.dingring.domain.user.User;
import com.dingring.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 消息 DTO 装配器：补齐发送者名称/头像、引用消息等冗余字段。
 */
@Component
@RequiredArgsConstructor
public class MessageAssembler {

    private static final int REPLY_PREVIEW_LEN = 50;

    private final UserRepository userRepository;
    private final AgentRepository agentRepository;
    private final MessageRepository messageRepository;

    public MessageDTO toDto(GroupMessage message) {
        MessageDTO.MessageDTOBuilder builder = MessageDTO.builder()
                .id(message.getId())
                .groupId(message.getChatGroupId())
                .topicId(message.getTopicId())
                .senderId(message.getSenderId())
                .senderType(message.getSenderType() == null ? null : message.getSenderType().name())
                .messageType(message.getMessageType() == null ? null : message.getMessageType().name())
                .content(message.getContent())
                .replyToMessageId(message.getReplyToMessageId())
                .createTime(message.getCreateTime());
        fillSender(builder, message);
        fillReply(builder, message);
        return builder.build();
    }

    public List<MessageDTO> toDtos(List<GroupMessage> messages) {
        return messages.stream().map(this::toDto).toList();
    }

    private void fillSender(MessageDTO.MessageDTOBuilder builder, GroupMessage message) {
        if (message.getSenderType() == SenderType.AGENT) {
            agentRepository.findById(message.getSenderId()).ifPresent(a -> {
                builder.senderName(a.getName());
                builder.senderAvatar(a.getProfilePicture());
            });
        } else if (message.getSenderType() == SenderType.USER) {
            userRepository.findById(message.getSenderId()).ifPresent(u -> {
                builder.senderName(u.getName());
                builder.senderAvatar(u.getProfilePicture());
            });
        } else {
            builder.senderName("系统");
        }
    }

    private void fillReply(MessageDTO.MessageDTOBuilder builder, GroupMessage message) {
        if (message.getReplyToMessageId() == null) {
            return;
        }
        messageRepository.findById(message.getReplyToMessageId()).ifPresent(replied -> {
            builder.replyToSenderName(resolveSenderName(replied));
            String content = replied.getContent();
            if (content != null && content.length() > REPLY_PREVIEW_LEN) {
                content = content.substring(0, REPLY_PREVIEW_LEN) + "…";
            }
            builder.replyToContent(content);
        });
    }

    /** 解析发送者名称（USER 查用户表，AGENT 查 Agent 表） */
    public String resolveSenderName(GroupMessage message) {
        if (message.getSenderType() == SenderType.AGENT) {
            return agentRepository.findById(message.getSenderId())
                    .map(Agent::getName).orElse("Agent#" + message.getSenderId());
        }
        if (message.getSenderType() == SenderType.USER) {
            return userRepository.findById(message.getSenderId())
                    .map(User::getName).orElse("用户#" + message.getSenderId());
        }
        return "系统";
    }
}
