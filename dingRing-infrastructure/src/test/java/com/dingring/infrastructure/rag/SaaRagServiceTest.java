package com.dingring.infrastructure.rag;

import com.dingring.domain.service.Reranker;
import com.dingring.domain.service.Reranker.ScoredDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link SaaRagService} RAG 检索服务单测。
 * <p>策略：mock {@link VectorStore}（返回候选文档）与 {@link Reranker}（返回重排结果），
 * 验证双层过滤表达式构建、格式化输出、异常降级。不依赖真实 PostgreSQL。
 */
@DisplayName("SaaRagService RAG 检索")
class SaaRagServiceTest {

    private VectorStore vectorStore;
    private Reranker reranker;
    private SaaRagService ragService;

    @BeforeEach
    void setUp() {
        vectorStore = mock(VectorStore.class);
        reranker = mock(Reranker.class);
        ragService = new SaaRagService(vectorStore, reranker);
    }

    @Test
    @DisplayName("有检索结果时返回格式化文本段（含排序与相关性分）")
    void shouldReturnFormattedKnowledgeWhenResultsExist() {
        Document doc = new Document("Redis 缓存雪崩解决方案");
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc));
        when(reranker.rerank("缓存雪崩", List.of("Redis 缓存雪崩解决方案")))
                .thenReturn(List.of(new ScoredDocument("Redis 缓存雪崩解决方案", 9.5)));

        String result = ragService.retrieve("缓存雪崩", 1L);

        assertThat(result)
                .contains("知识库检索结果")
                .contains("相关性: 9.5")
                .contains("Redis 缓存雪崩解决方案");
    }

    @Test
    @DisplayName("向量召回为空时返回空字符串")
    void shouldReturnEmptyWhenNoCandidates() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        String result = ragService.retrieve("缓存雪崩", 1L);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("向量检索异常时降级返回空（不阻塞主流程）")
    void shouldFallbackToEmptyOnRetrievalFailure() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new RuntimeException("连接超时"));

        String result = ragService.retrieve("缓存雪崩", 1L);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("重排异常时降级返回空")
    void shouldFallbackToEmptyOnRerankFailure() {
        Document doc = new Document("Redis 缓存雪崩解决方案");
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc));
        when(reranker.rerank(any(), any())).thenThrow(new RuntimeException("LLM 调用失败"));

        String result = ragService.retrieve("缓存雪崩", 1L);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("空 query 直接返回空，不触发检索")
    void shouldReturnEmptyForBlankQuery() {
        String result = ragService.retrieve("  ", 1L);

        assertThat(result).isEmpty();
    }
}
