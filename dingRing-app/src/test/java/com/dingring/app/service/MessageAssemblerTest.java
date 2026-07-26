package com.dingring.app.service;

import com.dingring.app.dto.response.MessageDTO;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.agent.AgentRepository;
import com.dingring.domain.group.GroupMessage;
import com.dingring.domain.group.MessageRepository;
import com.dingring.domain.group.MessageType;
import com.dingring.domain.group.SenderType;
import com.dingring.domain.user.User;
import com.dingring.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link MessageAssembler} 消息 DTO 装配器单元测试。
 * <p>核心：根据 senderType 补齐 senderName/senderAvatar，引用消息补齐 replyTo*。
 */
@DisplayName("MessageAssembler 消息装配")
class MessageAssemblerTest {

    private AgentRepository agentRepository;
    private UserRepository userRepository;
    private MessageRepository messageRepository;
    private MessageAssembler assembler;

    @BeforeEach
    void setUp() {
        agentRepository = mock(AgentRepository.class);
        userRepository = mock(UserRepository.class);
        messageRepository = mock(MessageRepository.class);
        assembler = new MessageAssembler(userRepository, agentRepository, messageRepository);
    }

    private GroupMessage msg(Long id, Long senderId, SenderType type, String content) {
        GroupMessage m = new GroupMessage();
        m.setId(id);
        m.setChatGroupId(1L);
        m.setSenderId(senderId);
        m.setSenderType(type);
        m.setMessageType(MessageType.TEXT);
        m.setContent(content);
        return m;
    }

    @Nested
    @DisplayName("toDto 发送者信息")
    class ToDtoSender {

        @Test
        @DisplayName("AGENT 消息从 agentRepository 补齐名称与头像")
        void agentMessageShouldLookupAgentRepo() {
            GroupMessage m = msg(1L, 10L, SenderType.AGENT, "hi");
            Agent agent = new Agent();
            agent.setId(10L);
            agent.setName("老王");
            agent.setProfilePicture("http://x/a.png");
            when(agentRepository.findById(10L)).thenReturn(Optional.of(agent));

            MessageDTO dto = assembler.toDto(m);

            assertThat(dto.getSenderName()).isEqualTo("老王");
            assertThat(dto.getSenderAvatar()).isEqualTo("http://x/a.png");
            assertThat(dto.getSenderType()).isEqualTo("AGENT");
        }

        @Test
        @DisplayName("USER 消息从 userRepository 补齐名称与头像")
        void userMessageShouldLookupUserRepo() {
            GroupMessage m = msg(1L, 1L, SenderType.USER, "hello");
            User user = new User();
            user.setId(1L);
            user.setName("张三");
            user.setProfilePicture("http://x/u.png");
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));

            MessageDTO dto = assembler.toDto(m);

            assertThat(dto.getSenderName()).isEqualTo("张三");
            assertThat(dto.getSenderAvatar()).isEqualTo("http://x/u.png");
            assertThat(dto.getSenderType()).isEqualTo("USER");
        }

        @Test
        @DisplayName("SYSTEM 消息固定名称为「系统」")
        void systemMessageShouldHaveSystemName() {
            GroupMessage m = msg(1L, 0L, SenderType.SYSTEM, "系统通知");

            MessageDTO dto = assembler.toDto(m);

            assertThat(dto.getSenderName()).isEqualTo("系统");
            assertThat(dto.getSenderType()).isEqualTo("SYSTEM");
        }

        @Test
        @DisplayName("Agent 不存在时不报错（name/avatar 保持 null）")
        void missingAgentShouldNotThrow() {
            GroupMessage m = msg(1L, 99L, SenderType.AGENT, "hi");
            when(agentRepository.findById(99L)).thenReturn(Optional.empty());

            MessageDTO dto = assembler.toDto(m);

            assertThat(dto.getSenderName()).isNull();
            assertThat(dto.getSenderAvatar()).isNull();
        }
    }

    @Nested
    @DisplayName("引用消息填充")
    class ToDtoReply {

        @Test
        @DisplayName("有 replyToMessageId 时填充被引用消息预览")
        void shouldFillReplyPreview() {
            GroupMessage m = msg(2L, 1L, SenderType.USER, "同问");
            m.setReplyToMessageId(1L);

            GroupMessage replied = msg(1L, 10L, SenderType.AGENT, "Redis 是内存数据库");
            when(messageRepository.findById(1L)).thenReturn(Optional.of(replied));
            Agent agent = new Agent();
            agent.setId(10L);
            agent.setName("老王");
            when(agentRepository.findById(10L)).thenReturn(Optional.of(agent));

            MessageDTO dto = assembler.toDto(m);

            assertThat(dto.getReplyToMessageId()).isEqualTo(1L);
            assertThat(dto.getReplyToSenderName()).isEqualTo("老王");
            assertThat(dto.getReplyToContent()).isEqualTo("Redis 是内存数据库");
        }

        @Test
        @DisplayName("被引用消息内容超过 50 字符时截断并加省略号")
        void longReplyContentShouldBeTruncated() {
            String longContent = "a".repeat(80);
            GroupMessage m = msg(2L, 1L, SenderType.USER, "回复");
            m.setReplyToMessageId(1L);

            GroupMessage replied = msg(1L, 1L, SenderType.USER, longContent);
            when(messageRepository.findById(1L)).thenReturn(Optional.of(replied));
            when(userRepository.findById(1L)).thenReturn(Optional.of(new User()));

            MessageDTO dto = assembler.toDto(m);

            assertThat(dto.getReplyToContent()).hasSize(51);  // 50 + "…"
            assertThat(dto.getReplyToContent()).endsWith("…");
        }

        @Test
        @DisplayName("被引用消息不存在时不报错")
        void missingRepliedShouldNotThrow() {
            GroupMessage m = msg(2L, 1L, SenderType.USER, "回复");
            m.setReplyToMessageId(999L);
            when(messageRepository.findById(999L)).thenReturn(Optional.empty());
            when(userRepository.findById(1L)).thenReturn(Optional.of(new User()));

            MessageDTO dto = assembler.toDto(m);

            assertThat(dto.getReplyToSenderName()).isNull();
            assertThat(dto.getReplyToContent()).isNull();
        }
    }

    @Nested
    @DisplayName("resolveSenderName")
    class ResolveSenderName {

        @Test
        @DisplayName("USER 消息返回用户名，找不到时返回「用户#id」")
        void userMessageShouldResolveUserName() {
            GroupMessage m = msg(1L, 5L, SenderType.USER, "hi");
            when(userRepository.findById(5L)).thenReturn(Optional.empty());

            assertThat(assembler.resolveSenderName(m)).isEqualTo("用户#5");
        }

        @Test
        @DisplayName("AGENT 消息返回 Agent 名，找不到时返回「Agent#id」")
        void agentMessageShouldResolveAgentName() {
            GroupMessage m = msg(1L, 99L, SenderType.AGENT, "hi");
            when(agentRepository.findById(99L)).thenReturn(Optional.empty());

            assertThat(assembler.resolveSenderName(m)).isEqualTo("Agent#99");
        }

        @Test
        @DisplayName("SYSTEM 消息返回「系统」")
        void systemMessageShouldReturnSystem() {
            GroupMessage m = msg(1L, 0L, SenderType.SYSTEM, "通知");

            assertThat(assembler.resolveSenderName(m)).isEqualTo("系统");
        }
    }
}
