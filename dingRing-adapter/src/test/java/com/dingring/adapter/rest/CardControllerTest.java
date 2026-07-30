package com.dingring.adapter.rest;

import com.dingring.app.dto.response.KnowledgeCardDTO;
import com.dingring.app.dto.response.ReviewCardDTO;
import com.dingring.app.service.CardAppService;
import com.dingring.common.exception.BizException;
import com.dingring.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link CardController} REST API 单元测试。
 */
@DisplayName("CardController 知识卡片 REST API")
@ExtendWith(MockitoExtension.class)
class CardControllerTest {

    private MockMvc mockMvc;

    @Mock
    private CardAppService cardAppService;

    @InjectMocks
    private CardController controller;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Nested
    @DisplayName("GET /api/cards 卡片列表")
    class ListCards {

        @Test
        @DisplayName("无 category 参数时查全部")
        void shouldListAllCards() throws Exception {
            when(cardAppService.list(null)).thenReturn(List.of(
                    KnowledgeCardDTO.builder().id(1L).question("Q1").category("Java").build()
            ));

            mockMvc.perform(get("/api/cards"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].question").value("Q1"));
        }

        @Test
        @DisplayName("指定 category 时按分类筛选")
        void shouldFilterByCategory() throws Exception {
            when(cardAppService.list("Java")).thenReturn(List.of(
                    KnowledgeCardDTO.builder().id(1L).question("Q1").category("Java").build()
            ));

            mockMvc.perform(get("/api/cards").param("category", "Java"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].category").value("Java"));
        }
    }

    @Nested
    @DisplayName("GET /api/cards/review 复习")
    class Review {

        @Test
        @DisplayName("默认顺序 sequential")
        void shouldReviewSequentially() throws Exception {
            ReviewCardDTO dto = new ReviewCardDTO(
                    List.of(KnowledgeCardDTO.builder().id(1L).question("Q1").build()), 1L);
            when(cardAppService.review(eq(null), eq("sequential"))).thenReturn(dto);

            mockMvc.perform(get("/api/cards/review"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.total").value(1))
                    .andExpect(jsonPath("$.data.cards[0].question").value("Q1"));
        }

        @Test
        @DisplayName("随机顺序 random")
        void shouldReviewRandomly() throws Exception {
            ReviewCardDTO dto = new ReviewCardDTO(
                    List.of(KnowledgeCardDTO.builder().id(1L).build()), 1L);
            when(cardAppService.review(eq("Java"), eq("random"))).thenReturn(dto);

            mockMvc.perform(get("/api/cards/review")
                            .param("category", "Java")
                            .param("order", "random"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.cards.length()").value(1));
        }
    }

    @Nested
    @DisplayName("GET /api/cards/categories 全部分类")
    class Categories {

        @Test
        @DisplayName("返回所有分类")
        void shouldReturnAllCategories() throws Exception {
            when(cardAppService.categories()).thenReturn(List.of("Java", "JVM", "Redis"));

            mockMvc.perform(get("/api/cards/categories"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(3))
                    .andExpect(jsonPath("$.data[0]").value("Java"));
        }

        @Test
        @DisplayName("无分类时返回空数组")
        void emptyCategoriesShouldReturnEmptyArray() throws Exception {
            when(cardAppService.categories()).thenReturn(List.of());

            mockMvc.perform(get("/api/cards/categories"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(0));
        }
    }

    @Nested
    @DisplayName("DELETE /api/cards/{id} 删除卡片")
    class Delete {

        @Test
        @DisplayName("删除成功返回 success")
        void shouldDeleteCard() throws Exception {
            mockMvc.perform(delete("/api/cards/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            verify(cardAppService).delete(1L);
        }

        @Test
        @DisplayName("卡片不存在时返回 NOT_FOUND")
        void shouldReturnNotFoundWhenCardMissing() throws Exception {
            doThrow(new BizException(ErrorCode.NOT_FOUND, "卡片不存在: 999"))
                    .when(cardAppService).delete(999L);

            mockMvc.perform(delete("/api/cards/999"))
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
        }
    }
}
