package com.dingring.app.task;

import com.dingring.app.event.CardEventHandler;
import com.dingring.domain.discussion.CardRepository;
import com.dingring.domain.discussion.KnowledgeCard;
import com.dingring.domain.discussion.Topic;
import com.dingring.domain.discussion.TopicRepository;
import com.dingring.domain.discussion.TopicStatus;
import com.dingring.domain.event.TopicClosed;
import com.dingring.domain.group.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CardReconciler} 卡片对账单元测试。
 * <p>核心：近期 CLOSED 且无卡片的主题重建 TopicClosed 走幂等补生成；
 * 无结论 / 已有卡片 / 无总结 Agent 记录的主题跳过。
 */
@DisplayName("CardReconciler 卡片对账")
class CardReconcilerTest {

    private TopicRepository topicRepository;
    private CardRepository cardRepository;
    private MessageRepository messageRepository;
    private CardEventHandler cardEventHandler;
    private CardReconciler reconciler;

    @BeforeEach
    void setUp() throws Exception {
        topicRepository = mock(TopicRepository.class);
        cardRepository = mock(CardRepository.class);
        messageRepository = mock(MessageRepository.class);
        cardEventHandler = mock(CardEventHandler.class);
        reconciler = new CardReconciler(topicRepository, cardRepository, messageRepository, cardEventHandler);
        Field f = CardReconciler.class.getDeclaredField("reconcileWindowDays");
        f.setAccessible(true);
        f.set(reconciler, 7);
    }

    private Topic closedTopic(Long topicId, String conclusion, Long concluderAgentId) {
        Topic t = new Topic();
        t.setId(topicId);
        t.setChatGroupId(1L);
        t.setTitle("已关闭主题");
        t.setStatus(TopicStatus.CLOSED);
        t.setConclusion(conclusion);
        t.setClosedAt(LocalDateTime.now().minusDays(1));
        if (concluderAgentId != null) {
            Map<String, Object> feature = new HashMap<>();
            feature.put("concludedByAgentId", concluderAgentId);
            t.setFeature(feature);
        }
        return t;
    }

    @Test
    @DisplayName("无卡片的已关闭主题：重建 TopicClosed 补生成（triggeredBy=RECONCILE）")
    void topicWithoutCardsShouldRegenerate() {
        Topic topic = closedTopic(100L, "# 结论", 10L);
        when(topicRepository.findClosedSince(any(LocalDateTime.class))).thenReturn(List.of(topic));
        when(cardRepository.findByTopicId(100L)).thenReturn(List.of());
        when(messageRepository.countByTopicId(100L)).thenReturn(8L);

        reconciler.reconcile();

        ArgumentCaptor<TopicClosed> captor = ArgumentCaptor.forClass(TopicClosed.class);
        verify(cardEventHandler).generateCards(captor.capture());
        TopicClosed event = captor.getValue();
        assertThat(event.getTopicId()).isEqualTo(100L);
        assertThat(event.getConclusion()).isEqualTo("# 结论");
        assertThat(event.getTriggeredBy()).isEqualTo("RECONCILE");
        assertThat(event.getConcluderAgentId()).isEqualTo(10L);
        assertThat(event.getMessageCount()).isEqualTo(8L);
    }

    @Test
    @DisplayName("已有卡片的主题跳过")
    void topicWithCardsShouldBeSkipped() {
        Topic topic = closedTopic(100L, "# 结论", 10L);
        when(topicRepository.findClosedSince(any(LocalDateTime.class))).thenReturn(List.of(topic));
        when(cardRepository.findByTopicId(100L)).thenReturn(List.of(new KnowledgeCard()));

        reconciler.reconcile();

        verify(cardEventHandler, never()).generateCards(any());
    }

    @Test
    @DisplayName("无结论的主题跳过")
    void topicWithoutConclusionShouldBeSkipped() {
        Topic topic = closedTopic(100L, "", 10L);
        when(topicRepository.findClosedSince(any(LocalDateTime.class))).thenReturn(List.of(topic));

        reconciler.reconcile();

        verify(cardEventHandler, never()).generateCards(any());
    }

    @Test
    @DisplayName("无总结 Agent 记录的历史主题跳过")
    void topicWithoutConcluderShouldBeSkipped() {
        Topic topic = closedTopic(100L, "# 结论", null);
        when(topicRepository.findClosedSince(any(LocalDateTime.class))).thenReturn(List.of(topic));
        when(cardRepository.findByTopicId(100L)).thenReturn(List.of());

        reconciler.reconcile();

        verify(cardEventHandler, never()).generateCards(any());
    }

    @Test
    @DisplayName("单个主题补生成异常不影响其余主题对账")
    void oneFailureShouldNotBlockOthers() {
        Topic bad = closedTopic(100L, "# 结论A", 10L);
        Topic good = closedTopic(101L, "# 结论B", 11L);
        when(topicRepository.findClosedSince(any(LocalDateTime.class))).thenReturn(List.of(bad, good));
        when(cardRepository.findByTopicId(any())).thenReturn(List.of());
        doThrow(new RuntimeException("boom")).when(cardEventHandler)
                .generateCards(any(TopicClosed.class));

        reconciler.reconcile();

        ArgumentCaptor<TopicClosed> captor = ArgumentCaptor.forClass(TopicClosed.class);
        verify(cardEventHandler, org.mockito.Mockito.times(2)).generateCards(captor.capture());
        assertThat(captor.getAllValues()).extracting(TopicClosed::getTopicId)
                .containsExactly(100L, 101L);
    }
}
