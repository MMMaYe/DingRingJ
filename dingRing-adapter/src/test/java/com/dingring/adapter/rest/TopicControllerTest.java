package com.dingring.adapter.rest;

import com.dingring.app.dto.request.CreateTopicRequest;
import com.dingring.app.dto.response.ConclusionDTO;
import com.dingring.app.dto.response.KnowledgeCardDTO;
import com.dingring.app.dto.response.MessageDTO;
import com.dingring.app.dto.response.TopicSummary;
import com.dingring.app.service.CardAppService;
import com.dingring.app.service.TopicAppService;
import com.dingring.common.exception.BizException;
import com.dingring.common.exception.ErrorCode;
import com.dingring.common.response.PageResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link TopicController} REST API 单元测试。
 */
@DisplayName("TopicController 主题讨论 REST API")
@ExtendWith(MockitoExtension.class)
class TopicControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private TopicAppService topicAppService;

    @Mock
    private CardAppService cardAppService;

    @InjectMocks
    private TopicController controller;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Nested
    @DisplayName("POST /api/groups/{groupId}/topics 创建主题")
    class CreateTopic {

        @Test
        @DisplayName("成功创建返回 200 + TopicSummary")
        void shouldCreateTopic() throws Exception {
            CreateTopicRequest req = new CreateTopicRequest();
            req.setTitle("Java 内存模型");
            TopicSummary summary = TopicSummary.builder().id(1L).title("Java 内存模型").status("IN_PROGRESS").build();
            when(topicAppService.createTopic(eq(1L), eq("Java 内存模型"))).thenReturn(summary);

            mockMvc.perform(post("/api/groups/1/topics")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(1L))
                    .andExpect(jsonPath("$.data.title").value("Java 内存模型"));
        }

        @Test
        @DisplayName("title 为空时返回 400")
        void blankTitleShouldReturn400() throws Exception {
            String body = """
                    {"title":""}
                    """;
            mockMvc.perform(post("/api/groups/1/topics")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("PARAM_INVALID"));
        }

        @Test
        @DisplayName("群已有进行中主题时返回 409")
        void existingTopicShouldReturn409() throws Exception {
            when(topicAppService.createTopic(eq(1L), any()))
                    .thenThrow(new BizException(ErrorCode.TOPIC_ALREADY_IN_PROGRESS));

            String body = """
                    {"title":"新主题"}
                    """;
            mockMvc.perform(post("/api/groups/1/topics")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("TOPIC_ALREADY_IN_PROGRESS"));
        }
    }

    @Nested
    @DisplayName("GET /api/groups/{groupId}/topics 主题列表")
    class ListTopics {

        @Test
        @DisplayName("返回群的所有主题")
        void shouldReturnTopicList() throws Exception {
            when(topicAppService.listByGroup(1L)).thenReturn(List.of(
                    TopicSummary.builder().id(1L).title("主题1").status("CLOSED").build(),
                    TopicSummary.builder().id(2L).title("主题2").status("IN_PROGRESS").build()
            ));

            mockMvc.perform(get("/api/groups/1/topics"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(2))
                    .andExpect(jsonPath("$.data[0].title").value("主题1"));
        }
    }

    @Nested
    @DisplayName("GET /api/groups/{groupId}/messages 群消息分页")
    class GroupMessages {

        @Test
        @DisplayName("返回分页消息（默认 page=1, pageSize=50）")
        void shouldReturnPagedMessages() throws Exception {
            PageResult<MessageDTO> page = new PageResult<>(
                    List.of(MessageDTO.builder().id(1L).content("hi").build()), 1L, 1, 50);
            when(topicAppService.groupMessages(eq(1L), eq(1), eq(50))).thenReturn(page);

            mockMvc.perform(get("/api/groups/1/messages"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.page").value(1))
                    .andExpect(jsonPath("$.data.pageSize").value(50))
                    .andExpect(jsonPath("$.data.items[0].id").value(1L));
        }

        @Test
        @DisplayName("自定义 page/pageSize 参数生效")
        void customPageParamsShouldWork() throws Exception {
            PageResult<MessageDTO> page = new PageResult<>(List.of(), 0L, 3, 20);
            when(topicAppService.groupMessages(eq(1L), eq(3), eq(20))).thenReturn(page);

            mockMvc.perform(get("/api/groups/1/messages").param("page", "3").param("pageSize", "20"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.page").value(3))
                    .andExpect(jsonPath("$.data.pageSize").value(20));
        }
    }

    @Nested
    @DisplayName("POST /api/topics/{topicId}/conclude 结束讨论")
    class Conclude {

        @Test
        @DisplayName("成功触发返回 200")
        void shouldConclude() throws Exception {
            mockMvc.perform(post("/api/topics/1/conclude"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            verify(topicAppService).conclude(1L);
        }

        @Test
        @DisplayName("主题不存在时返回 404")
        void topicNotFoundShouldReturn404() throws Exception {
            doThrow(new BizException(ErrorCode.NOT_FOUND, "主题不存在"))
                    .when(topicAppService).conclude(99L);

            mockMvc.perform(post("/api/topics/99/conclude"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("GET /api/topics/{topicId}/messages 主题消息分页")
    class Messages {

        @Test
        @DisplayName("返回主题消息分页")
        void shouldReturnTopicMessages() throws Exception {
            PageResult<MessageDTO> page = new PageResult<>(
                    List.of(MessageDTO.builder().id(1L).build()), 10L, 1, 50);
            when(topicAppService.messages(eq(1L), eq(1), eq(50))).thenReturn(page);

            mockMvc.perform(get("/api/topics/1/messages"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.total").value(10));
        }
    }

    @Nested
    @DisplayName("GET /api/topics/{topicId}/conclusion 主题结论")
    class Conclusion {

        @Test
        @DisplayName("返回结论 DTO")
        void shouldReturnConclusion() throws Exception {
            ConclusionDTO dto = ConclusionDTO.builder()
                    .topicId(1L).conclusion("## STAR").concluderAgentName("总结者").messageCount(20L).build();
            when(topicAppService.conclusion(1L)).thenReturn(dto);

            mockMvc.perform(get("/api/topics/1/conclusion"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.conclusion").value("## STAR"))
                    .andExpect(jsonPath("$.data.concluderAgentName").value("总结者"));
        }

        @Test
        @DisplayName("结论未生成时返回 409")
        void noConclusionShouldReturn409() throws Exception {
            when(topicAppService.conclusion(1L))
                    .thenThrow(new BizException(ErrorCode.TOPIC_NOT_IN_PROGRESS, "尚未生成结论"));

            mockMvc.perform(get("/api/topics/1/conclusion"))
                    .andExpect(status().isConflict());
        }
    }

    @Nested
    @DisplayName("GET /api/topics/{topicId}/cards 主题卡片")
    class TopicCards {

        @Test
        @DisplayName("返回主题生成的知识卡片")
        void shouldReturnCards() throws Exception {
            when(cardAppService.listByTopic(1L)).thenReturn(List.of(
                    KnowledgeCardDTO.builder().id(1L).question("Q1").answer("A1").build()
            ));

            mockMvc.perform(get("/api/topics/1/cards"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].question").value("Q1"));
        }
    }
}
