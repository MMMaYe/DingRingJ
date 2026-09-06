package com.dingring.infrastructure.agent.hook;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.dingring.domain.discussion.Topic;
import com.dingring.domain.discussion.TopicRepository;
import com.dingring.domain.discussion.TopicStatus;
import com.dingring.domain.group.Group;
import com.dingring.domain.group.GroupRepository;
import com.dingring.domain.service.RagService;
import com.dingring.domain.service.RetrievalCandidate;
import com.dingring.domain.service.RetrievalResult;
import com.dingring.domain.service.TopicVectorService;
import com.dingring.domain.workflow.StateKeys;
import com.dingring.infrastructure.rag.config.RagProperties;
import com.dingring.infrastructure.rag.retrieval.QueryNormalizer;
import com.dingring.infrastructure.rag.splitter.TokenCounter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link InjectKbHook} 意图门控注入单测。
 * <p>覆盖：四意图矩阵、群绑定库解析与未绑定跳过、自 topicId 排除、缓存、单源异常、注入格式。
 */
@DisplayName("InjectKbHook Agent 级知识注入")
class InjectKbHookTest {

    private RagService ragService;
    private TopicVectorService topicVectorService;
    private TopicRepository topicRepository;
    private GroupRepository groupRepository;
    private QueryNormalizer queryNormalizer;
    private InjectKbHook hook;

    /** 默认群 1 绑定知识库 5、6 */
    private static final List<Long> BOUND_KB_IDS = List.of(5L, 6L);

    @BeforeEach
    void setUp() {
        ragService = mock(RagService.class);
        topicVectorService = mock(TopicVectorService.class);
        topicRepository = mock(TopicRepository.class);
        groupRepository = mock(GroupRepository.class);
        queryNormalizer = mock(QueryNormalizer.class);
        // rewrite 透传：默认不改写 query（LLM 改写逻辑由 QueryNormalizerTest 单独覆盖）
        when(queryNormalizer.rewrite(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        TokenCounter tokenCounter = mock(TokenCounter.class);
        // count 返回 0：目录条目全部在预算内（token 截断行为由 RagProperties 默认值保护）
        when(tokenCounter.count(anyString())).thenReturn(0);
        hook = new InjectKbHook(ragService, topicVectorService, topicRepository, groupRepository,
                queryNormalizer, tokenCounter, new RagProperties());
        // 默认桩：群 1 绑定 [5,6]，无相似话题（各用例按需覆盖）
        when(groupRepository.findById(1L)).thenReturn(Optional.of(groupWithBinding(BOUND_KB_IDS)));
        when(topicVectorService.findSimilarTopics(anyString(), anyInt(), anyDouble()))
                .thenReturn(List.of());
    }

    /** 构造单条候选的目录结果（rerank 成功路径：fallbackReason=null） */
    private RetrievalResult catalogOf(String fileName, String content) {
        RetrievalCandidate candidate = new RetrievalCandidate(
                "cand-1", "chunk-1", content, fileName,
                List.of("章节一"), "L10-L20", 8.0, Map.of());
        return new RetrievalResult("q", "q", List.of(candidate), false, null);
    }

    private Group groupWithBinding(List<Long> kbIds) {
        Group g = new Group();
        g.setId(1L);
        g.setKnowledgeBaseConfig(kbIds == null ? null : Map.of("kbIds", kbIds));
        return g;
    }

    private OverAllState state(Map<String, Object> data) {
        return new OverAllState(data);
    }

    private String injectedText(Map<String, Object> result) {
        SystemMessage msg = (SystemMessage) result.get("messages");
        return msg.getText();
    }

    @Test
    @DisplayName("CHAT 意图：双源全跳过，零检索调用")
    void shouldSkipAllSourcesOnChat() {
        Map<String, Object> result = hook.beforeAgent(state(Map.of(
                StateKeys.INTENT, "CHAT",
                "groupId", 1L,
                "ragQuery", "今天天气不错")), null).join();

        assertThat(result).isEmpty();
        verify(ragService, never()).retrieveStructured(anyString(), anyList(), any());
        verify(topicVectorService, never()).findSimilarTopics(anyString(), anyInt(), anyDouble());
    }

    @Test
    @DisplayName("DISCUSS 意图：kb+topic 双源注入单条 SystemMessage")
    void shouldInjectBothSourcesOnDiscuss() {
        when(ragService.retrieveStructured("如何学好 JMM", BOUND_KB_IDS, 1L))
                .thenReturn(catalogOf("JMM 手册.md", "JMM 三大特性"));
        Topic similar = new Topic();
        similar.setId(99L);
        similar.setTitle("Java内存模型");
        similar.setStatus(TopicStatus.CLOSED);
        similar.setConclusion("happens-before 规则是……");
        when(topicVectorService.findSimilarTopics(anyString(), anyInt(), anyDouble()))
                .thenReturn(List.of(new TopicVectorService.SimilarTopic(99L, "Java内存模型", 0.9)));
        when(topicRepository.findById(99L)).thenReturn(Optional.of(similar));

        Map<String, Object> result = hook.beforeAgent(state(Map.of(
                StateKeys.INTENT, "DISCUSS",
                "groupId", 1L,
                "topicId", 5L,
                StateKeys.TOPIC_TITLE, "Java内存模型",
                "ragQuery", "如何学好 JMM")), null).join();

        assertThat(result).containsKey("messages");
        assertThat(injectedText(result))
                .contains("知识库检索目录")
                .contains("JMM 三大特性")
                .contains("Java内存模型")
                .contains("happens-before");
    }

    @Test
    @DisplayName("CONCLUDE 意图：仅 topic 源，不检索 kb")
    void shouldInjectOnlyTopicSourceOnConclude() {
        Topic similar = new Topic();
        similar.setId(88L);
        similar.setTitle("JUC 并发");
        similar.setStatus(TopicStatus.CLOSED);
        similar.setConclusion("锁升级过程……");
        when(topicVectorService.findSimilarTopics(anyString(), anyInt(), anyDouble()))
                .thenReturn(List.of(new TopicVectorService.SimilarTopic(88L, "JUC 并发", 0.8)));
        when(topicRepository.findById(88L)).thenReturn(Optional.of(similar));

        Map<String, Object> result = hook.beforeAgent(state(Map.of(
                StateKeys.INTENT, "CONCLUDE",
                "groupId", 1L,
                "topicId", 5L,
                StateKeys.TOPIC_TITLE, "Java并发编程",
                "ragQuery", "Java并发编程")), null).join();

        verify(ragService, never()).retrieveStructured(anyString(), anyList(), any());
        assertThat(injectedText(result)).contains("锁升级过程");
    }

    @Test
    @DisplayName("WORK 意图：仅 kb 源，不检索 topic")
    void shouldInjectOnlyKbSourceOnWork() {
        when(ragService.retrieveStructured("帮我整理部署文档", BOUND_KB_IDS, 1L))
                .thenReturn(catalogOf("部署手册.md", "部署步骤"));

        Map<String, Object> result = hook.beforeAgent(state(Map.of(
                StateKeys.INTENT, "WORK",
                "groupId", 1L,
                "ragQuery", "帮我整理部署文档")), null).join();

        verify(topicVectorService, never()).findSimilarTopics(anyString(), anyInt(), anyDouble());
        assertThat(injectedText(result)).contains("部署手册");
    }

    @Test
    @DisplayName("群未绑定知识库：kb 源跳过，零检索调用")
    void shouldSkipKbSourceWhenGroupHasNoBinding() {
        when(groupRepository.findById(1L)).thenReturn(Optional.of(groupWithBinding(List.of())));

        Map<String, Object> result = hook.beforeAgent(state(Map.of(
                StateKeys.INTENT, "WORK",
                "groupId", 1L,
                "ragQuery", "帮我整理部署文档")), null).join();

        verify(ragService, never()).retrieveStructured(anyString(), anyList(), any());
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("群不存在：按未绑定处理，kb 源跳过")
    void shouldSkipKbSourceWhenGroupMissing() {
        when(groupRepository.findById(1L)).thenReturn(Optional.empty());

        Map<String, Object> result = hook.beforeAgent(state(Map.of(
                StateKeys.INTENT, "WORK",
                "groupId", 1L,
                "ragQuery", "q")), null).join();

        verify(ragService, never()).retrieveStructured(anyString(), anyList(), any());
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("intent 缺失但 ragQuery 存在（防御行）：仅 kb 源")
    void shouldFallBackToKbOnlyWhenIntentMissing() {
        when(ragService.retrieveStructured(anyString(), anyList(), any()))
                .thenReturn(catalogOf("x.md", "x"));

        Map<String, Object> result = hook.beforeAgent(state(Map.of(
                "groupId", 1L,
                "ragQuery", "q")), null).join();

        verify(ragService).retrieveStructured("q", BOUND_KB_IDS, 1L);
        verify(topicVectorService, never()).findSimilarTopics(anyString(), anyInt(), anyDouble());
        assertThat(result).containsKey("messages");
    }

    @Test
    @DisplayName("排除自身 topicId：命中当前话题不注入其结论")
    void shouldExcludeCurrentTopicId() {
        when(topicVectorService.findSimilarTopics(anyString(), anyInt(), anyDouble()))
                .thenReturn(List.of(new TopicVectorService.SimilarTopic(5L, "当前话题", 0.95)));

        Map<String, Object> result = hook.beforeAgent(state(Map.of(
                StateKeys.INTENT, "CONCLUDE",
                "groupId", 1L,
                "topicId", 5L,
                StateKeys.TOPIC_TITLE, "当前话题")), null).join();

        verify(topicRepository, never()).findById(anyLong());
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("topic 源结果按 topicId 缓存：同话题第二次调用不再检索")
    void shouldCacheTopicSourceByTopicId() {
        Topic similar = new Topic();
        similar.setId(77L);
        similar.setTitle("历史话题");
        similar.setStatus(TopicStatus.CLOSED);
        similar.setConclusion("结论内容");
        when(topicVectorService.findSimilarTopics(anyString(), anyInt(), anyDouble()))
                .thenReturn(List.of(new TopicVectorService.SimilarTopic(77L, "历史话题", 0.85)));
        when(topicRepository.findById(77L)).thenReturn(Optional.of(similar));
        when(ragService.retrieveStructured(anyString(), anyList(), any()))
                .thenReturn(RetrievalResult.empty("q"));

        OverAllState state = state(Map.of(
                StateKeys.INTENT, "DISCUSS",
                "groupId", 1L,
                "topicId", 5L,
                StateKeys.TOPIC_TITLE, "新话题",
                "ragQuery", "q"));
        hook.beforeAgent(state, null).join();
        hook.beforeAgent(state, null).join();

        // 话题标题不变 → 相似集与结论稳定，第二次直接走缓存
        verify(topicVectorService, times(1)).findSimilarTopics(anyString(), anyInt(), anyDouble());
        verify(topicRepository, times(1)).findById(77L);
    }

    @Test
    @DisplayName("kb 源异常时仅注入 topic 源，不外抛")
    void shouldSkipFailedKbSource() {
        when(ragService.retrieveStructured(anyString(), anyList(), any()))
                .thenThrow(new RuntimeException("kb down"));
        Topic similar = new Topic();
        similar.setId(66L);
        similar.setTitle("t");
        similar.setStatus(TopicStatus.CLOSED);
        similar.setConclusion("c");
        when(topicVectorService.findSimilarTopics(anyString(), anyInt(), anyDouble()))
                .thenReturn(List.of(new TopicVectorService.SimilarTopic(66L, "t", 0.8)));
        when(topicRepository.findById(66L)).thenReturn(Optional.of(similar));

        Map<String, Object> result = hook.beforeAgent(state(Map.of(
                StateKeys.INTENT, "DISCUSS",
                "groupId", 1L,
                "topicId", 5L,
                StateKeys.TOPIC_TITLE, "t2",
                "ragQuery", "q")), null).join();

        assertThat(injectedText(result)).contains("历史相似话题结论");
    }

    @Test
    @DisplayName("双源全空返回空 Map（不注入空消息）")
    void shouldReturnEmptyWhenBothSourcesBlank() {
        when(ragService.retrieveStructured(anyString(), anyList(), any()))
                .thenReturn(RetrievalResult.empty("q"));

        Map<String, Object> result = hook.beforeAgent(state(Map.of(
                StateKeys.INTENT, "DISCUSS",
                "groupId", 1L,
                "topicId", 5L,
                StateKeys.TOPIC_TITLE, "t",
                "ragQuery", "q")), null).join();

        assertThat(result).isEmpty();
    }
}
