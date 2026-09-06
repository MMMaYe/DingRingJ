package com.dingring.infrastructure.rag.retrieval;

import com.dingring.domain.agent.Agent;
import com.dingring.domain.agent.AgentRepository;
import com.dingring.domain.service.LlmService;
import com.dingring.infrastructure.rag.config.RagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link QueryNormalizer} P3 用例（7.2）：确定性规范化、指代词触发 LLM 改写、失败回退。
 */
@DisplayName("query 规范化与指代词改写")
class QueryNormalizerTest {

    private LlmService llmService;
    private AgentRepository agentRepository;
    private QueryNormalizer normalizer;

    @BeforeEach
    void setUp() {
        llmService = mock(LlmService.class);
        agentRepository = mock(AgentRepository.class);
        normalizer = new QueryNormalizer(llmService, agentRepository, new RagProperties());
        when(agentRepository.findById(any())).thenReturn(Optional.of(new Agent()));
    }

    @Test
    @DisplayName("normalize：trim + 连续空白折叠为单空格")
    void shouldNormalizeWhitespace() {
        assertThat(QueryNormalizer.normalize("  JVM   垃圾回收\n  原理  ")).isEqualTo("JVM 垃圾回收 原理");
        assertThat(QueryNormalizer.normalize(null)).isEmpty();
        assertThat(QueryNormalizer.normalize("   ")).isEmpty();
    }

    @Test
    @DisplayName("cacheKey：同 query 稳定，不同 query 区分")
    void shouldHashStably() {
        assertThat(QueryNormalizer.cacheKey("JVM 回收"))
                .isEqualTo(QueryNormalizer.cacheKey("JVM 回收"))
                .hasSize(64)
                .isNotEqualTo(QueryNormalizer.cacheKey("JVM 回收 原理"));
    }

    @Test
    @DisplayName("无指代词：不触发 LLM 改写（省一次调用）")
    void shouldSkipRewriteWithoutReferenceWords() {
        String result = normalizer.rewrite("JVM 垃圾回收原理", "话题标题");

        assertThat(result).isEqualTo("JVM 垃圾回收原理");
        org.mockito.Mockito.verify(llmService, org.mockito.Mockito.never()).chat(any(), anyString(), anyList(), any());
    }

    @Test
    @DisplayName("含指代词 + 有话题：LLM 改写为自包含查询")
    void shouldRewriteReferenceWords() {
        when(llmService.chat(any(Agent.class), anyString(), anyList(), any()))
                .thenReturn("JVM 垃圾回收的原理");

        String result = normalizer.rewrite("它的原理是什么", "JVM 垃圾回收机制");

        assertThat(result).isEqualTo("JVM 垃圾回收的原理");
    }

    @Test
    @DisplayName("改写失败：回退原 query（改写是增强不是依赖）")
    void shouldFallbackWhenRewriteFails() {
        when(llmService.chat(any(Agent.class), anyString(), anyList(), any()))
                .thenThrow(new RuntimeException("llm down"));

        String result = normalizer.rewrite("它的原理", "话题");

        assertThat(result).isEqualTo("它的原理");
    }

    @Test
    @DisplayName("无话题标题：跳过改写（没有指代消解的锚点）")
    void shouldSkipRewriteWithoutTopicTitle() {
        String result = normalizer.rewrite("它的原理是什么", "");

        assertThat(result).isEqualTo("它的原理是什么");
    }
}
