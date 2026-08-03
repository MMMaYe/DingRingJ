package com.dingring.app.service;

import com.dingring.app.dto.response.ConclusionDTO;
import com.dingring.app.dto.response.MessageDTO;
import com.dingring.app.dto.response.TopicSummary;
import com.dingring.app.orchestrator.ChatOrchestrator;
import com.dingring.app.orchestrator.Terminator;
import com.dingring.common.exception.BizException;
import com.dingring.common.response.PageResult;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.agent.AgentRepository;
import com.dingring.domain.discussion.Topic;
import com.dingring.domain.discussion.TopicRepository;
import com.dingring.domain.discussion.TopicStatus;
import com.dingring.domain.group.GroupMessage;
import com.dingring.domain.group.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TopicAppService} 单元测试。
 */
@DisplayName("TopicAppService 主题讨论服务")
class TopicAppServiceTest {

    private TopicRepository topicRepository;
    private MessageRepository messageRepository;
    private AgentRepository agentRepository;
    private MessageAssembler messageAssembler;
    private ChatOrchestrator chatOrchestrator;
    private Terminator terminator;
    private TopicAppService service;

    @BeforeEach
    void setUp() {
        topicRepository = mock(TopicRepository.class);
        messageRepository = mock(MessageRepository.class);
        agentRepository = mock(AgentRepository.class);
        messageAssembler = mock(MessageAssembler.class);
        chatOrchestrator = mock(ChatOrchestrator.class);
        terminator = mock(Terminator.class);
        service = new TopicAppService(topicRepository, messageRepository,
                agentRepository, messageAssembler, chatOrchestrator, terminator);
    }

    @Nested
    @DisplayName("conclude 触发结束")
    class Conclude {

        @Test
        @DisplayName("委托给 ChatOrchestrator.conclude，operator=DEFAULT_USER_ID, triggeredBy=USER")
        void shouldDelegateToOrchestrator() {
            service.conclude(100L);

            verify(chatOrchestrator).conclude(100L, GroupAppService.DEFAULT_USER_ID, "USER");
        }
    }

    @Nested
    @DisplayName("messages 主题消息分页")
    class Messages {

        @Test
        @DisplayName("page 从 1 开始，offset = (page-1)*pageSize")
        void shouldCalculateOffsetCorrectly() {
            when(messageRepository.countByTopicId(100L)).thenReturn(100L);
            GroupMessage m = new GroupMessage();
            m.setId(1L);
            when(messageRepository.findByTopicId(100L, 50, 50)).thenReturn(List.of(m));
            MessageDTO dto = MessageDTO.builder().id(1L).build();
            when(messageAssembler.toBatchDtos(any())).thenReturn(List.of(dto));

            PageResult<MessageDTO> result = service.messages(100L, 2, 50);

            assertThat(result.getPage()).isEqualTo(2);
            assertThat(result.getPageSize()).isEqualTo(50);
            assertThat(result.getTotal()).isEqualTo(100L);
            assertThat(result.getItems()).hasSize(1);
            verify(messageRepository).findByTopicId(100L, 50, 50);
        }
    }

    @Nested
    @DisplayName("conclusion 获取结论")
    class Conclusion {

        @Test
        @DisplayName("主题不存在时抛 BizException")
        void topicNotFoundShouldThrow() {
            when(topicRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.conclusion(99L))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("主题不存在");
        }

        @Test
        @DisplayName("结论未生成时抛 BizException")
        void nullConclusionShouldThrow() {
            Topic t = new Topic();
            t.setId(1L);
            t.setConclusion(null);
            when(topicRepository.findById(1L)).thenReturn(Optional.of(t));

            assertThatThrownBy(() -> service.conclusion(1L))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("尚未生成结论");
        }

        @Test
        @DisplayName("返回结论 DTO 含总结 Agent 名称")
        void shouldReturnConclusionWithConcluderName() {
            Topic t = new Topic();
            t.setId(1L);
            t.setChatGroupId(10L);
            t.setTitle("Java 内存模型");
            t.setConclusion("## STAR\n...");
            // 总结人存于 feature JSON
            t.setFeature(new java.util.HashMap<>(java.util.Map.of("concludedByAgentId", 99L)));
            when(topicRepository.findById(1L)).thenReturn(Optional.of(t));

            Agent concluder = new Agent();
            concluder.setId(99L);
            concluder.setName("总结者");
            when(agentRepository.findById(99L)).thenReturn(Optional.of(concluder));
            when(messageRepository.countByTopicId(1L)).thenReturn(50L);

            ConclusionDTO result = service.conclusion(1L);

            assertThat(result.getTopicId()).isEqualTo(1L);
            assertThat(result.getConclusion()).isEqualTo("## STAR\n...");
            assertThat(result.getConcluderAgentName()).isEqualTo("总结者");
            assertThat(result.getMessageCount()).isEqualTo(50L);
        }
    }

    @Test
    @DisplayName("listByGroup 返回群的所有主题摘要")
    void listByGroupShouldReturnAllTopics() {
        Topic t1 = new Topic();
        t1.setId(1L);
        t1.setTitle("主题1");
        t1.setStatus(TopicStatus.CLOSED);
        Topic t2 = new Topic();
        t2.setId(2L);
        t2.setTitle("主题2");
        t2.setStatus(TopicStatus.IN_PROGRESS);
        when(topicRepository.findByGroupId(1L)).thenReturn(List.of(t1, t2));
        when(messageRepository.countByTopicId(anyLong())).thenReturn(0L);

        List<TopicSummary> result = service.listByGroup(1L);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(TopicSummary::getTitle)
                .containsExactly("主题1", "主题2");
    }
}
