package com.dingring.adapter.rest;

import com.dingring.app.dto.request.SaveAgentRequest;
import com.dingring.app.dto.response.AgentDTO;
import com.dingring.app.service.AgentAppService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link AgentController} REST API 单元测试。
 */
@DisplayName("AgentController Agent 管理 REST API")
@ExtendWith(MockitoExtension.class)
class AgentControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private AgentAppService agentAppService;

    @InjectMocks
    private AgentController controller;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Nested
    @DisplayName("POST /api/agents 创建 Agent")
    class Create {

        @Test
        @DisplayName("合法请求返回 200 + AgentDTO（不含 apiKey）")
        void shouldCreateAgent() throws Exception {
            SaveAgentRequest req = new SaveAgentRequest();
            req.setName("老王");
            req.setBaseUrl("https://api.deepseek.com");
            req.setApiKey("sk-secret");
            req.setModelName("deepseek-chat");

            AgentDTO dto = AgentDTO.builder()
                    .id(1L).name("老王").baseUrl("https://api.deepseek.com").modelName("deepseek-chat")
                    .build();
            when(agentAppService.create(any())).thenReturn(dto);

            mockMvc.perform(post("/api/agents")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(1L))
                    .andExpect(jsonPath("$.data.name").value("老王"))
                    .andExpect(jsonPath("$.data.apiKey").doesNotExist());
        }

        @Test
        @DisplayName("name 为空时返回 400")
        void blankNameShouldReturn400() throws Exception {
            String body = """
                    {"name":"","baseUrl":"https://x","apiKey":"sk","modelName":"m"}
                    """;
            mockMvc.perform(post("/api/agents")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("PARAM_INVALID"));
        }

        @Test
        @DisplayName("baseUrl 为空时返回 400")
        void blankBaseUrlShouldReturn400() throws Exception {
            String body = """
                    {"name":"老王","baseUrl":"","apiKey":"sk","modelName":"m"}
                    """;
            mockMvc.perform(post("/api/agents")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("PARAM_INVALID"));
        }
    }

    @Nested
    @DisplayName("PUT /api/agents/{id} 修改 Agent")
    class Update {

        @Test
        @DisplayName("成功修改返回 200 + 更新后的 DTO")
        void shouldUpdateAgent() throws Exception {
            SaveAgentRequest req = new SaveAgentRequest();
            req.setName("新名字");
            req.setBaseUrl("https://api.x.com");
            req.setApiKey("sk-new");
            req.setModelName("m");

            AgentDTO dto = AgentDTO.builder().id(1L).name("新名字").build();
            when(agentAppService.update(eq(1L), any())).thenReturn(dto);

            mockMvc.perform(put("/api/agents/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.name").value("新名字"));
        }

        @Test
        @DisplayName("Agent 不存在时返回 404")
        void agentNotFoundShouldReturn404() throws Exception {
            when(agentAppService.update(eq(99L), any()))
                    .thenThrow(new BizException(ErrorCode.NOT_FOUND, "Agent 不存在"));

            // apiKey 现在修改时也必填，body 必须包含
            String body = """
                    {"name":"x","baseUrl":"https://x","apiKey":"sk","modelName":"m"}
                    """;
            mockMvc.perform(put("/api/agents/99")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    @DisplayName("GET /api/agents 返回全部 Agent")
    void listShouldReturnAllAgents() throws Exception {
        when(agentAppService.list()).thenReturn(List.of(
                AgentDTO.builder().id(1L).name("老王").build(),
                AgentDTO.builder().id(2L).name("小李").build()
        ));

        mockMvc.perform(get("/api/agents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].name").value("老王"));
    }
}
