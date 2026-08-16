package com.dingring.infrastructure.rag;

import com.dingring.domain.discussion.Topic;
import com.dingring.domain.service.TopicVectorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TopicVectorServiceImpl} 单测：mock topicVectorStore 验证写入内容与检索解析。
 */
@DisplayName("TopicVectorServiceImpl topic_id_store 读写")
class TopicVectorServiceImplTest {

    private VectorStore topicVectorStore;
    private JdbcTemplate vectorJdbcTemplate;
    private TopicVectorServiceImpl service;

    @BeforeEach
    void setUp() {
        topicVectorStore = mock(VectorStore.class);
        vectorJdbcTemplate = mock(JdbcTemplate.class);
        service = new TopicVectorServiceImpl(topicVectorStore, vectorJdbcTemplate);
    }

    @Test
    @DisplayName("indexTopic 写入标题文本 + topicId/groupId/title metadata")
    void shouldIndexTopicTitleWithMetadata() {
        Topic topic = new Topic();
        topic.setId(5L);
        topic.setChatGroupId(2L);
        topic.setTitle("Java内存模型");

        service.indexTopic(topic);

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(topicVectorStore).add(captor.capture());
        Document doc = captor.getValue().get(0);
        assertThat(doc.getText()).isEqualTo("Java内存模型");
        assertThat(doc.getMetadata())
                .containsEntry("topicId", 5L)
                .containsEntry("groupId", 2L)
                .containsEntry("title", "Java内存模型");
    }

    @Test
    @DisplayName("findSimilarTopics 解析 topicId/title 并换算 score=1-distance")
    void shouldParseSimilarTopicsWithScore() {
        Document hit = new Document("Redis 分布式锁", Map.of(
                "topicId", 7L,
                "title", "Redis 分布式锁",
                "distance", 0.25));
        when(topicVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(hit));

        List<TopicVectorService.SimilarTopic> result = service.findSimilarTopics("分布式锁怎么实现", 3, 0.7);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).topicId()).isEqualTo(7L);
        assertThat(result.get(0).title()).isEqualTo("Redis 分布式锁");
        assertThat(result.get(0).score()).isCloseTo(0.75, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    @DisplayName("metadata 缺 topicId 的脏数据被安全跳过")
    void shouldSkipDocWithoutTopicId() {
        Document dirty = new Document("脏数据", Map.of("distance", 0.1));
        when(topicVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(dirty));

        assertThat(service.findSimilarTopics("q", 3, 0.7)).isEmpty();
    }

    @Test
    @DisplayName("indexTopic 先删旧向量再写入（同 topicId 幂等，防事件重放累积重复）")
    void shouldDeleteBeforeIndexForIdempotency() {
        Topic topic = new Topic();
        topic.setId(5L);
        topic.setChatGroupId(2L);
        topic.setTitle("Java内存模型");

        service.indexTopic(topic);

        // 先删后写：DELETE 在 add 之前执行（inOrder 锁定顺序）
        InOrder inOrder = inOrder(vectorJdbcTemplate, topicVectorStore);
        inOrder.verify(vectorJdbcTemplate).update(
                eq("DELETE FROM topic_id_store WHERE metadata->>'topicId' = ?"),
                eq("5"));
        inOrder.verify(topicVectorStore).add(anyList());
    }

    @Test
    @DisplayName("metadata topicId 为 Integer（Jackson 反序列化真实路径）也能解析")
    void shouldParseIntegerTopicId() {
        Document hit = new Document("历史话题", Map.of("topicId", 7, "distance", 0.2));
        when(topicVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(hit));

        List<TopicVectorService.SimilarTopic> result = service.findSimilarTopics("q", 3, 0.7);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).topicId()).isEqualTo(7L);
    }
}
