package com.dingring.app.event;

import com.dingring.domain.service.GroupBroadcastService;
import com.dingring.common.constant.WsConstants;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.agent.AgentRepository;
import com.dingring.domain.discussion.CardRepository;
import com.dingring.domain.discussion.KnowledgeCard;
import com.dingring.domain.event.KnowledgeCardGenerated;
import com.dingring.domain.event.TopicClosed;
import com.dingring.domain.service.DomainEventPublisher;
import com.dingring.domain.service.LlmService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CardEventHandler} 知识卡片生成单元测试。
 * <p>核心逻辑：监听 TopicClosed → LLM 提取 Q&A 卡片 → 入库 + 广播 + 发布事件；失败重试 3 次。
 */
@DisplayName("CardEventHandler 知识卡片生成")
class CardEventHandlerTest {

    private AgentRepository agentRepository;
    private CardRepository cardRepository;
    private LlmService llmService;
    private DomainEventPublisher eventPublisher;
    private GroupBroadcastService groupBroadcastService;
    private ObjectMapper objectMapper;
    private CardEventHandler handler;

    @BeforeEach
    void setUp() {
        agentRepository = mock(AgentRepository.class);
        cardRepository = mock(CardRepository.class);
        llmService = mock(LlmService.class);
        eventPublisher = mock(DomainEventPublisher.class);
        groupBroadcastService = mock(GroupBroadcastService.class);
        objectMapper = new ObjectMapper();
        handler = new CardEventHandler(agentRepository, cardRepository, llmService,
                eventPublisher, groupBroadcastService, objectMapper);
    }

    private Agent agent(Long id, String name) {
        Agent a = new Agent();
        a.setId(id);
        a.setName(name);
        return a;
    }

    private TopicClosed topicClosedEvent(Long topicId, Long groupId, Long concluderAgentId) {
        return new TopicClosed(topicId, groupId, "Java 内存模型",
                "## STAR 结论", 10L, "USER", concluderAgentId);
    }

    /**
     * 模拟 saveBatch 回填卡片 ID（生产环境由 MyBatis 主键回填完成）。
     * 否则 CardEventHandler 中 Map.of("id", c.getId(), ...) 会因 null 抛 NPE。
     */
    private void mockSaveBatchWithIdBackfill() {
        org.mockito.Mockito.doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<KnowledgeCard> cards = inv.getArgument(0);
            long id = 1L;
            for (KnowledgeCard c : cards) {
                c.setId(id++);
            }
            return null;
        }).when(cardRepository).saveBatch(any());
    }

    @Nested
    @DisplayName("onTopicClosed 触发卡片生成")
    class OnTopicClosed {

        @Test
        @DisplayName("总结 Agent 不存在时跳过生成（不调用 LLM）")
        void concluderNotExistShouldSkip() throws InterruptedException {
            when(agentRepository.findById(99L)).thenReturn(Optional.empty());

            handler.onTopicClosed(topicClosedEvent(1L, 10L, 99L));

            // 等待虚拟线程执行完跳过逻辑
            Thread.sleep(300);
            verify(llmService, org.mockito.Mockito.never()).chat(any(), anyString(), any(), any());
            verify(cardRepository, org.mockito.Mockito.never()).saveBatch(any());
        }

        @Test
        @DisplayName("LLM 返回标准 JSON 数组时正常生成卡片")
        void shouldGenerateCardsFromStandardJson() {
            Agent concluder = agent(99L, "总结者");
            when(agentRepository.findById(99L)).thenReturn(Optional.of(concluder));
            String json = """
                    [
                      {"question":"什么是 JVM?","answer":"Java 虚拟机","category":"Java"},
                      {"question":"什么是 GC?","answer":"垃圾回收","category":"JVM"}
                    ]
                    """;
            when(llmService.chat(any(), anyString(), any(), any())).thenReturn(json);
            mockSaveBatchWithIdBackfill();

            handler.onTopicClosed(topicClosedEvent(1L, 10L, 99L));

            // 异步等待卡片保存与广播
            verify(cardRepository, timeout(2000)).saveBatch(any());
            verify(groupBroadcastService, timeout(2000))
                    .broadcast(eq(10L), eq(WsConstants.CARD_GENERATED), any());
            verify(eventPublisher, timeout(2000)).publish(any(KnowledgeCardGenerated.class));
        }

        @Test
        @DisplayName("LLM 返回 ```json 代码块包裹时也能正确解析")
        void shouldParseMarkdownCodeBlockWrappedJson() {
            Agent concluder = agent(99L, "总结者");
            when(agentRepository.findById(99L)).thenReturn(Optional.of(concluder));
            String raw = """
                    ```json
                    [{"question":"Q1","answer":"A1","category":"c1"}]
                    ```
                    """;
            when(llmService.chat(any(), anyString(), any(), any())).thenReturn(raw);
            mockSaveBatchWithIdBackfill();

            handler.onTopicClosed(topicClosedEvent(1L, 10L, 99L));

            verify(cardRepository, timeout(2000)).saveBatch(any());
            verify(eventPublisher, timeout(2000)).publish(any(KnowledgeCardGenerated.class));
        }

        @Test
        @DisplayName("LLM 输出前后包含其他文本时仍能提取 JSON 数组")
        void shouldExtractJsonArrayFromNoisyOutput() {
            Agent concluder = agent(99L, "总结者");
            when(agentRepository.findById(99L)).thenReturn(Optional.of(concluder));
            String noisy = """
                    好的，已为你提取卡片：
                    [{"question":"Q1","answer":"A1"}]
                    希望对你有帮助。
                    """;
            when(llmService.chat(any(), anyString(), any(), any())).thenReturn(noisy);
            mockSaveBatchWithIdBackfill();

            handler.onTopicClosed(topicClosedEvent(1L, 10L, 99L));

            verify(cardRepository, timeout(2000)).saveBatch(any());
        }

        @Test
        @DisplayName("LLM 返回空数组（未提取到卡片）触发重试")
        void emptyArrayShouldTriggerRetry() {
            Agent concluder = agent(99L, "总结者");
            when(agentRepository.findById(99L)).thenReturn(Optional.of(concluder));
            when(llmService.chat(any(), anyString(), any(), any())).thenReturn("[]");

            handler.onTopicClosed(topicClosedEvent(1L, 10L, 99L));

            // 重试 3 次（首次 + 2 次重试）
            verify(llmService, timeout(2000).times(3)).chat(any(), anyString(), any(), any());
            // 失败时不应入库、不应发布事件
            verify(cardRepository, org.mockito.Mockito.never()).saveBatch(any());
            verify(eventPublisher, org.mockito.Mockito.never()).publish(any(KnowledgeCardGenerated.class));
        }

        @Test
        @DisplayName("LLM 调用抛异常时重试 3 次，最终失败不阻塞主流程")
        void exceptionShouldRetryThreeTimesAndNotThrow() {
            Agent concluder = agent(99L, "总结者");
            when(agentRepository.findById(99L)).thenReturn(Optional.of(concluder));
            when(llmService.chat(any(), anyString(), any(), any()))
                    .thenThrow(new RuntimeException("LLM 服务不可用"));

            // 不应抛出异常（不阻塞主流程）
            handler.onTopicClosed(topicClosedEvent(1L, 10L, 99L));

            verify(llmService, timeout(2000).times(3)).chat(any(), anyString(), any(), any());
            verify(cardRepository, org.mockito.Mockito.never()).saveBatch(any());
        }

        @Test
        @DisplayName("首次失败第二次成功时正常生成卡片（重试机制有效）")
        void shouldSucceedOnRetryAfterFirstFailure() {
            Agent concluder = agent(99L, "总结者");
            when(agentRepository.findById(99L)).thenReturn(Optional.of(concluder));
            String validJson = """
                    [{"question":"Q1","answer":"A1","category":"c1"}]
                    """;
            when(llmService.chat(any(), anyString(), any(), any()))
                    .thenThrow(new RuntimeException("网络抖动"))
                    .thenReturn(validJson);
            mockSaveBatchWithIdBackfill();

            handler.onTopicClosed(topicClosedEvent(1L, 10L, 99L));

            verify(cardRepository, timeout(2000)).saveBatch(any());
            verify(eventPublisher, timeout(2000)).publish(any(KnowledgeCardGenerated.class));
            verify(llmService, timeout(2000).atLeast(2)).chat(any(), anyString(), any(), any());
        }

        @Test
        @DisplayName("卡片缺少 category 字段时回退为「未分类」")
        void missingCategoryShouldFallbackToUnclassified() {
            Agent concluder = agent(99L, "总结者");
            when(agentRepository.findById(99L)).thenReturn(Optional.of(concluder));
            String json = """
                    [{"question":"Q1","answer":"A1"}]
                    """;
            when(llmService.chat(any(), anyString(), any(), any())).thenReturn(json);
            mockSaveBatchWithIdBackfill();

            handler.onTopicClosed(topicClosedEvent(1L, 10L, 99L));

            org.mockito.ArgumentCaptor<List<KnowledgeCard>> captor =
                    org.mockito.ArgumentCaptor.forClass(List.class);
            verify(cardRepository, timeout(2000)).saveBatch(captor.capture());
            assertThat(captor.getValue()).hasSize(1);
            assertThat(captor.getValue().get(0).getCategory()).isEqualTo("未分类");
        }

        @Test
        @DisplayName("卡片缺少 question 或 answer 时被过滤")
        void cardsMissingQuestionOrAnswerShouldBeFiltered() {
            Agent concluder = agent(99L, "总结者");
            when(agentRepository.findById(99L)).thenReturn(Optional.of(concluder));
            // 一张有效卡片 + 一张缺 answer + 一张缺 question
            String json = """
                    [
                      {"question":"Q1","answer":"A1","category":"c1"},
                      {"question":"Q2"},
                      {"answer":"A3"}
                    ]
                    """;
            when(llmService.chat(any(), anyString(), any(), any())).thenReturn(json);
            mockSaveBatchWithIdBackfill();

            handler.onTopicClosed(topicClosedEvent(1L, 10L, 99L));

            org.mockito.ArgumentCaptor<List<KnowledgeCard>> captor =
                    org.mockito.ArgumentCaptor.forClass(List.class);
            verify(cardRepository, timeout(2000)).saveBatch(captor.capture());
            assertThat(captor.getValue()).hasSize(1);
            assertThat(captor.getValue().get(0).getQuestion()).isEqualTo("Q1");
        }

        @Test
        @DisplayName("生成的卡片携带 topicId 与 LLM 输出字段")
        void generatedCardsShouldCarryTopicIdAndFields() {
            Agent concluder = agent(99L, "总结者");
            when(agentRepository.findById(99L)).thenReturn(Optional.of(concluder));
            String json = """
                    [{"question":"什么是 GC?","answer":"垃圾回收","category":"JVM"}]
                    """;
            when(llmService.chat(any(), anyString(), any(), any())).thenReturn(json);
            mockSaveBatchWithIdBackfill();

            handler.onTopicClosed(topicClosedEvent(42L, 10L, 99L));

            org.mockito.ArgumentCaptor<List<KnowledgeCard>> captor =
                    org.mockito.ArgumentCaptor.forClass(List.class);
            verify(cardRepository, timeout(2000)).saveBatch(captor.capture());
            KnowledgeCard card = captor.getValue().get(0);
            assertThat(card.getTopicId()).isEqualTo(42L);
            assertThat(card.getQuestion()).isEqualTo("什么是 GC?");
            assertThat(card.getAnswer()).isEqualTo("垃圾回收");
            assertThat(card.getCategory()).isEqualTo("JVM");
        }
    }
}
