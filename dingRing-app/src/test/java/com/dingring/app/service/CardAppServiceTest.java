package com.dingring.app.service;

import com.dingring.app.dto.response.KnowledgeCardDTO;
import com.dingring.app.dto.response.ReviewCardDTO;
import com.dingring.domain.discussion.CardRepository;
import com.dingring.domain.discussion.KnowledgeCard;
import com.dingring.domain.discussion.Topic;
import com.dingring.domain.discussion.TopicRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link CardAppService} 单元测试。
 */
@DisplayName("CardAppService 知识卡片服务")
class CardAppServiceTest {

    private CardRepository cardRepository;
    private TopicRepository topicRepository;
    private CardAppService service;

    @BeforeEach
    void setUp() {
        cardRepository = mock(CardRepository.class);
        topicRepository = mock(TopicRepository.class);
        service = new CardAppService(cardRepository, topicRepository);
    }

    private KnowledgeCard card(Long id, Long topicId, String q, String a, String category) {
        KnowledgeCard c = new KnowledgeCard();
        c.setId(id);
        c.setTopicId(topicId);
        c.setQuestion(q);
        c.setAnswer(a);
        c.setCategory(category);
        return c;
    }

    @Nested
    @DisplayName("list 卡片列表")
    class ListCards {

        @Test
        @DisplayName("按分类查询返回该分类卡片")
        void shouldFilterByCategory() {
            when(cardRepository.findByCategory("Redis")).thenReturn(List.of(
                    card(1L, 100L, "什么是 Redis？", "内存数据库", "Redis")
            ));

            List<KnowledgeCardDTO> result = service.list("Redis");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getCategory()).isEqualTo("Redis");
        }

        @Test
        @DisplayName("category 为 null 时查询全部")
        void nullCategoryShouldReturnAll() {
            when(cardRepository.findByCategory(null)).thenReturn(List.of(
                    card(1L, 100L, "Q1", "A1", "Redis"),
                    card(2L, 100L, "Q2", "A2", "JVM")
            ));

            List<KnowledgeCardDTO> result = service.list(null);

            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("卡片 topicTitle 从 topicRepository 查询并缓存")
        void topicTitleShouldBeResolved() {
            when(cardRepository.findByCategory(null)).thenReturn(List.of(
                    card(1L, 100L, "Q1", "A1", "Redis")
            ));
            Topic topic = new Topic();
            topic.setId(100L);
            topic.setTitle("Redis 讨论会");
            when(topicRepository.findById(100L)).thenReturn(Optional.of(topic));

            List<KnowledgeCardDTO> result = service.list(null);

            assertThat(result.get(0).getTopicTitle()).isEqualTo("Redis 讨论会");
        }

        @Test
        @DisplayName("主题不存在时 topicTitle 为 null")
        void missingTopicShouldHaveNullTitle() {
            when(cardRepository.findByCategory(null)).thenReturn(List.of(
                    card(1L, 999L, "Q1", "A1", "x")
            ));
            when(topicRepository.findById(999L)).thenReturn(Optional.empty());

            List<KnowledgeCardDTO> result = service.list(null);

            assertThat(result.get(0).getTopicTitle()).isNull();
        }
    }

    @Nested
    @DisplayName("review 复习卡片")
    class Review {

        @Test
        @DisplayName("sequential 顺序保留原顺序")
        void sequentialOrderShouldKeepOriginalOrder() {
            when(cardRepository.findByCategory(null)).thenReturn(List.of(
                    card(1L, 100L, "Q1", "A1", "x"),
                    card(2L, 100L, "Q2", "A2", "x"),
                    card(3L, 100L, "Q3", "A3", "x")
            ));

            ReviewCardDTO review = service.review(null, "sequential");

            assertThat(review.getCards()).extracting(KnowledgeCardDTO::getId)
                    .containsExactly(1L, 2L, 3L);
            assertThat(review.getTotal()).isEqualTo(3);
        }

        @Test
        @DisplayName("random 顺序被打乱但总数不变")
        void randomOrderShouldShuffleButKeepTotal() {
            when(cardRepository.findByCategory(null)).thenReturn(List.of(
                    card(1L, 100L, "Q1", "A1", "x"),
                    card(2L, 100L, "Q2", "A2", "x"),
                    card(3L, 100L, "Q3", "A3", "x")
            ));

            ReviewCardDTO review = service.review(null, "random");

            assertThat(review.getCards()).hasSize(3);
            assertThat(review.getTotal()).isEqualTo(3);
            // 集合内容不变（仅顺序可能变化）
            assertThat(review.getCards()).extracting(KnowledgeCardDTO::getId)
                    .containsExactlyInAnyOrder(1L, 2L, 3L);
        }

        @Test
        @DisplayName("空卡片列表返回空复习集")
        void emptyCardsShouldReturnEmptyReview() {
            when(cardRepository.findByCategory("Empty")).thenReturn(List.of());

            ReviewCardDTO review = service.review("Empty", "sequential");

            assertThat(review.getCards()).isEmpty();
            assertThat(review.getTotal()).isZero();
        }
    }

    @Test
    @DisplayName("categories 返回全部分类")
    void categoriesShouldReturnAll() {
        when(cardRepository.findAllCategories()).thenReturn(List.of("Redis", "JVM", "并发"));

        assertThat(service.categories()).containsExactly("Redis", "JVM", "并发");
    }
}
