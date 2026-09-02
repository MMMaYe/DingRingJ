package com.dingring.adapter.rest;

import com.dingring.app.dto.response.QuestionnaireDTO;
import com.dingring.app.service.QuestionnaireAppService;
import com.dingring.common.exception.ParamException;
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
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link QuestionnaireController} REST API 单元测试。
 */
@DisplayName("QuestionnaireController 问卷 REST API")
@ExtendWith(MockitoExtension.class)
class QuestionnaireControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private QuestionnaireAppService questionnaireAppService;

    @InjectMocks
    private QuestionnaireController controller;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Nested
    @DisplayName("GET /api/questionnaire")
    class Get {

        @Test
        @DisplayName("返回题目 schema 与当前答案")
        void shouldReturnSchemaAndAnswers() throws Exception {
            QuestionnaireDTO dto = QuestionnaireDTO.builder()
                    .questions(List.of(QuestionnaireDTO.QuestionDTO.builder()
                            .key("career").label("你的职业身份").dimension("BACKGROUND")
                            .type("SINGLE").required(true)
                            .options(List.of(QuestionnaireDTO.OptionDTO.builder()
                                    .value("STUDENT").label("在校学生").build()))
                            .build()))
                    .answers(Map.of("career", "MID"))
                    .build();
            when(questionnaireAppService.get()).thenReturn(dto);

            mockMvc.perform(get("/api/questionnaire"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.questions[0].key").value("career"))
                    .andExpect(jsonPath("$.data.questions[0].required").value(true))
                    .andExpect(jsonPath("$.data.answers.career").value("MID"));
        }
    }

    @Nested
    @DisplayName("POST /api/questionnaire")
    class Submit {

        @Test
        @DisplayName("合法提交返回 200")
        void shouldSubmit() throws Exception {
            mockMvc.perform(post("/api/questionnaire")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("answers", Map.of("career", "MID")))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }

        @Test
        @DisplayName("answers 缺失返回 400 + PARAM_INVALID")
        void shouldRejectMissingAnswers() throws Exception {
            mockMvc.perform(post("/api/questionnaire")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("PARAM_INVALID"));
        }

        @Test
        @DisplayName("非法答案返回 400 + PARAM_INVALID")
        void shouldRejectInvalidAnswers() throws Exception {
            doThrow(new ParamException("取值不在选项内：你的职业身份"))
                    .when(questionnaireAppService).submit(any());

            mockMvc.perform(post("/api/questionnaire")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("answers", Map.of("career", "CTO")))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("PARAM_INVALID"))
                    .andExpect(jsonPath("$.message").value("取值不在选项内：你的职业身份"));
        }
    }
}
