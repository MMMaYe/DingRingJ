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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

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

    /**
     * 批量装配消息 DTO，消除 N+1 查询。
     * <p>一次性收集所有 senderId 和 replyToMessageId，分别做一次批量查询后在内存中组装。
     * 适用于分页查询等批量场景；单条实时推送请继续使用 {@link #toDto}。
     */
    public List<MessageDTO> toBatchDtos(List<GroupMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        // 1. 批量查询发送者
        Set<Long> agentIds = messages.stream()
                .filter(m -> m.getSenderType() == SenderType.AGENT && m.getSenderId() != null)
                .map(GroupMessage::getSenderId)
                .collect(Collectors.toSet());
        Set<Long> userIds = messages.stream()
                .filter(m -> m.getSenderType() == SenderType.USER && m.getSenderId() != null)
                .map(GroupMessage::getSenderId)
                .collect(Collectors.toSet());
        Map<Long, Agent> agentMap = agentIds.isEmpty()
                ? new java.util.HashMap<>()
                : agentRepository.findByIds(new ArrayList<>(agentIds)).stream()
                        .collect(Collectors.toMap(Agent::getId, Function.identity()));
        Map<Long, User> userMap = userIds.isEmpty()
                ? new java.util.HashMap<>()
                : userRepository.findByIds(new ArrayList<>(userIds)).stream()
                        .collect(Collectors.toMap(User::getId, Function.identity()));

        // 2. 批量查询被引用消息（同时收集被引用消息的发送者，复用上面的 Map）
        Set<Long> replyIds = messages.stream()
                .map(GroupMessage::getReplyToMessageId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, GroupMessage> repliedMap = replyIds.isEmpty()
                ? Collections.emptyMap()
                : messageRepository.findByIds(new ArrayList<>(replyIds)).stream()
                        .collect(Collectors.toMap(GroupMessage::getId, Function.identity()));

        // 补充被引用消息中可能出现的额外发送者
        Set<Long> extraAgentIds = repliedMap.values().stream()
                .filter(m -> m.getSenderType() == SenderType.AGENT && m.getSenderId() != null)
                .map(GroupMessage::getSenderId)
                .filter(id -> !agentMap.containsKey(id))
                .collect(Collectors.toSet());
        Set<Long> extraUserIds = repliedMap.values().stream()
                .filter(m -> m.getSenderType() == SenderType.USER && m.getSenderId() != null)
                .map(GroupMessage::getSenderId)
                .filter(id -> !userMap.containsKey(id))
                .collect(Collectors.toSet());
        if (!extraAgentIds.isEmpty()) {
            agentRepository.findByIds(new ArrayList<>(extraAgentIds)).forEach(a -> agentMap.put(a.getId(), a));
        }
        if (!extraUserIds.isEmpty()) {
            userRepository.findByIds(new ArrayList<>(extraUserIds)).forEach(u -> userMap.put(u.getId(), u));
        }

        // 3. 内存中组装 DTO
        return messages.stream().map(m -> buildDto(m, agentMap, userMap, repliedMap)).toList();
    }

    private MessageDTO buildDto(GroupMessage m, Map<Long, Agent> agentMap,
                                 Map<Long, User> userMap, Map<Long, GroupMessage> repliedMap) {
        MessageDTO.MessageDTOBuilder builder = MessageDTO.builder()
                .id(m.getId())
                .groupId(m.getChatGroupId())
                .topicId(m.getTopicId())
                .senderId(m.getSenderId())
                .senderType(m.getSenderType() == null ? null : m.getSenderType().name())
                .messageType(m.getMessageType() == null ? null : m.getMessageType().name())
                .content(m.getContent())
                .replyToMessageId(m.getReplyToMessageId())
                .createTime(m.getCreateTime());

        // 发送者
        if (m.getSenderType() == SenderType.AGENT && m.getSenderId() != null) {
            Agent a = agentMap.get(m.getSenderId());
            if (a != null) {
                builder.senderName(a.getName());
                builder.senderAvatar(a.getProfilePicture());
            }
        } else if (m.getSenderType() == SenderType.USER && m.getSenderId() != null) {
            User u = userMap.get(m.getSenderId());
            if (u != null) {
                builder.senderName(u.getName());
                builder.senderAvatar(u.getProfilePicture());
            }
        } else if (m.getSenderType() != SenderType.AGENT && m.getSenderType() != SenderType.USER) {
            builder.senderName("系统");
        }

        // 引用回复
        if (m.getReplyToMessageId() != null) {
            GroupMessage replied = repliedMap.get(m.getReplyToMessageId());
            if (replied != null) {
                builder.replyToSenderName(resolveSenderNameFromMap(replied, agentMap, userMap));
                String content = replied.getContent();
                if (content != null && content.length() > REPLY_PREVIEW_LEN) {
                    content = content.substring(0, REPLY_PREVIEW_LEN) + "…";
                }
                builder.replyToContent(content);
            }
        }

        return builder.build();
    }

    private String resolveSenderNameFromMap(GroupMessage m, Map<Long, Agent> agentMap, Map<Long, User> userMap) {
        if (m.getSenderType() == SenderType.AGENT) {
            Agent a = agentMap.get(m.getSenderId());
            return a != null ? a.getName() : "Agent#" + m.getSenderId();
        }
        if (m.getSenderType() == SenderType.USER) {
            User u = userMap.get(m.getSenderId());
            return u != null ? u.getName() : "用户#" + m.getSenderId();
        }
        return "系统";
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
