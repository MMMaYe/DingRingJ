package com.dingring.adapter.rest;

import com.dingring.app.dto.request.CreateGroupRequest;
import com.dingring.app.dto.response.GroupDetail;
import com.dingring.app.dto.response.GroupSummary;
import com.dingring.app.service.GroupAppService;
import com.dingring.common.exception.BizException;
import com.dingring.common.exception.ErrorCode;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link GroupController} REST API 单元测试。
 * <p>使用 MockMvc standalone 模式，不加载 Spring 上下文，直接验证路由 + 序列化 + 异常处理。
 */
@DisplayName("GroupController 群管理 REST API")
@ExtendWith(MockitoExtension.class)
class GroupControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private GroupAppService groupAppService;

    @InjectMocks
    private GroupController controller;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Nested
    @DisplayName("POST /api/groups 创建群")
    class Create {

        @Test
        @DisplayName("合法请求返回 200 + ApiResponse.ok(data)")
        void shouldReturnOkWithCreatedGroup() throws Exception {
            CreateGroupRequest req = new CreateGroupRequest();
            req.setName("Java 学习群");
            req.setAgentIds(List.of(10L));
            req.setExpertAgentId(99L);

            GroupDetail detail = GroupDetail.builder().id(1L).name("Java 学习群").build();
            when(groupAppService.create(any())).thenReturn(detail);

            mockMvc.perform(post("/api/groups")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value(1L))
                    .andExpect(jsonPath("$.data.name").value("Java 学习群"));
        }

        @Test
        @DisplayName("name 为空时返回 400 + PARAM_INVALID")
        void blankNameShouldReturn400() throws Exception {
            String body = """
                    {"name":"","agentIds":[10],"expertAgentId":99}
                    """;
            mockMvc.perform(post("/api/groups")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.errorCode").value("PARAM_INVALID"));
        }

        @Test
        @DisplayName("agentIds 为空时返回 400")
        void emptyAgentIdsShouldReturn400() throws Exception {
            String body = """
                    {"name":"群","agentIds":[],"expertAgentId":99}
                    """;
            mockMvc.perform(post("/api/groups")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("PARAM_INVALID"));
        }

        @Test
        @DisplayName("expertAgentId 为 null 时返回 400")
        void nullExpertShouldReturn400() throws Exception {
            String body = """
                    {"name":"群","agentIds":[10]}
                    """;
            mockMvc.perform(post("/api/groups")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("PARAM_INVALID"));
        }

        @Test
        @DisplayName("服务抛 BizException 时按 ErrorCode 返回对应 HTTP 状态码")
        void bizExceptionShouldReturnMatchingStatus() throws Exception {
            when(groupAppService.create(any()))
                    .thenThrow(new BizException(ErrorCode.NOT_FOUND, "Agent 不存在"));

            String body = """
                    {"name":"群","agentIds":[10],"expertAgentId":99}
                    """;
            mockMvc.perform(post("/api/groups")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"))
                    .andExpect(jsonPath("$.message").value("Agent 不存在"));
        }
    }

    @Nested
    @DisplayName("GET /api/groups 群列表")
    class ListGroups {

        @Test
        @DisplayName("返回用户的群列表")
        void shouldReturnGroupList() throws Exception {
            when(groupAppService.list()).thenReturn(List.of(
                    GroupSummary.builder().id(1L).name("群1").memberCount(3).build(),
                    GroupSummary.builder().id(2L).name("群2").memberCount(5).build()
            ));

            mockMvc.perform(get("/api/groups"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data.length()").value(2))
                    .andExpect(jsonPath("$.data[0].id").value(1L))
                    .andExpect(jsonPath("$.data[1].name").value("群2"));
        }

        @Test
        @DisplayName("空列表返回 200 + 空数组")
        void emptyListShouldReturnEmptyArray() throws Exception {
            when(groupAppService.list()).thenReturn(List.of());

            mockMvc.perform(get("/api/groups"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data.length()").value(0));
        }
    }

    @Nested
    @DisplayName("GET /api/groups/{id} 群详情")
    class Detail {

        @Test
        @DisplayName("群存在时返回详情")
        void shouldReturnDetail() throws Exception {
            GroupDetail detail = GroupDetail.builder().id(1L).name("群").build();
            when(groupAppService.detail(1L)).thenReturn(detail);

            mockMvc.perform(get("/api/groups/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(1L));
        }

        @Test
        @DisplayName("群不存在时返回 404")
        void groupNotFoundShouldReturn404() throws Exception {
            when(groupAppService.detail(99L))
                    .thenThrow(new BizException(ErrorCode.NOT_FOUND, "群不存在"));

            mockMvc.perform(get("/api/groups/99"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
        }
    }

    @Nested
    @DisplayName("DELETE /api/groups/{id} 删除群")
    class Delete {

        @Test
        @DisplayName("成功删除返回 200 + success=true")
        void shouldDeleteSuccessfully() throws Exception {
            mockMvc.perform(delete("/api/groups/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            verify(groupAppService).delete(1L);
        }

        @Test
        @DisplayName("群不存在时返回 404")
        void deleteNonExistentShouldReturn404() throws Exception {
            doThrow(new BizException(ErrorCode.NOT_FOUND, "群不存在"))
                    .when(groupAppService).delete(99L);

            mockMvc.perform(delete("/api/groups/99"))
                    .andExpect(status().isNotFound());
        }
    }
}
