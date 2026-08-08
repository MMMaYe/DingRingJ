package com.dingring.infrastructure.rag;

import com.dingring.domain.agent.Agent;
import com.dingring.domain.agent.AgentRepository;
import com.dingring.domain.service.LlmService;
import com.dingring.domain.service.Reranker.ScoredDocument;
import com.dingring.infrastructure.prompt.PromptTemplateLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link LlmReranker} LLM 文档重排单测。
 * <p>策略：mock {@link LlmService}（返回 JSON 打分）、{@link AgentRepository}（重排 Agent）、
 * {@link PromptTemplateLoader}（模板渲染），验证打分解析与降级逻辑。不依赖真实 LLM。
 * <p>注意：候选数 &le;5 时跳过 LLM 直接返回，故验证 LLM 路径的用例必须给 6+ 候选。
 */
@DisplayName("LlmReranker LLM 文档重排")
class LlmRerankerTest {

    private LlmService llmService;
    private AgentRepository agentRepository;
    private PromptTemplateLoader promptTemplateLoader;
    private LlmReranker reranker;

    @BeforeEach
    void setUp() {
        llmService = mock(LlmService.class);
        agentRepository = mock(AgentRepository.class);
        promptTemplateLoader = mock(PromptTemplateLoader.class);
        reranker = new LlmReranker(llmService, agentRepository, promptTemplateLoader);
        ReflectionTestUtils.setField(reranker, "rerankerAgentId", 6L);

        Agent agent = new Agent();
        agent.setId(6L);
        when(agentRepository.findById(6L)).thenReturn(Optional.of(agent));
        // 模板渲染降级为空字符串时，走 buildRerankPrompt 兜底拼接
        when(promptTemplateLoader.render(anyString(), any())).thenReturn("");
    }

    /** 生成 n 个候选文档：doc0, doc1, ... */
    private List<String> candidates(int n) {
        return IntStream.range(0, n).mapToObj(i -> "doc" + i).collect(Collectors.toList());
    }

    @Test
    @DisplayName("解析 LLM JSON 打分并按分数降序返回")
    void shouldParseScoresAndSortDescending() {
        List<String> docs = candidates(6);
        // 4 参 chat 是 default 方法，Mockito 5 对 default 方法直接拦截（不转发真实实现），故 stub 4 参本身
        when(llmService.chat(any(), anyString(), any(), any()))
                .thenReturn("[{\"index\":1,\"score\":9.0},{\"index\":0,\"score\":6.0},"
                        + "{\"index\":2,\"score\":7.5},{\"index\":3,\"score\":3.0},"
                        + "{\"index\":4,\"score\":8.0},{\"index\":5,\"score\":2.0}]");

        List<ScoredDocument> result = reranker.rerank("query", docs);

        assertThat(result).hasSize(6);
        assertThat(result.get(0).content()).isEqualTo("doc1");   // 9.0 最高
        assertThat(result.get(0).score()).isEqualTo(9.0);
        assertThat(result.get(1).content()).isEqualTo("doc4");   // 8.0 次高
        assertThat(result.get(5).content()).isEqualTo("doc5");   // 2.0 最低
    }

    @Test
    @DisplayName("候选数不超过 5 时跳过 LLM 调用直接返回原始顺序")
    void shouldSkipRerankWhenCandidatesFew() {
        List<String> docs = candidates(3);

        List<ScoredDocument> result = reranker.rerank("query", docs);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).content()).isEqualTo("doc0");
        verifyNoInteractions(llmService);
    }

    @Test
    @DisplayName("LLM 返回空响应时降级为原始顺序")
    void shouldFallbackToOriginalOrderWhenResponseBlank() {
        List<String> docs = candidates(6);
        when(llmService.chat(any(), anyString(), any(), any())).thenReturn("");

        List<ScoredDocument> result = reranker.rerank("query", docs);

        assertThat(result).hasSize(6);
        assertThat(result.get(0).content()).isEqualTo("doc0");  // 保持原始顺序
    }

    @Test
    @DisplayName("LLM 调用异常时降级为原始顺序")
    void shouldFallbackToOriginalOrderWhenLlmFails() {
        List<String> docs = candidates(6);
        when(llmService.chat(any(), anyString(), any(), any()))
                .thenThrow(new RuntimeException("LLM 超时"));

        List<ScoredDocument> result = reranker.rerank("query", docs);

        assertThat(result).hasSize(6);
        assertThat(result.get(0).content()).isEqualTo("doc0");
    }

    @Test
    @DisplayName("响应为非法 JSON 时降级为原始顺序")
    void shouldFallbackToOriginalOrderWhenResponseNotJson() {
        List<String> docs = candidates(6);
        when(llmService.chat(any(), anyString(), any(), any())).thenReturn("我不太确定");

        List<ScoredDocument> result = reranker.rerank("query", docs);

        assertThat(result).hasSize(6);
        assertThat(result.get(0).content()).isEqualTo("doc0");
    }
}
