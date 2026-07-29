package com.dingring.app.task;

import com.dingring.app.orchestrator.DiscussionEngine;
import com.dingring.app.service.ChatPusher;
import com.dingring.common.constant.WsConstants;
import com.dingring.domain.discussion.Topic;
import com.dingring.domain.discussion.TopicRepository;
import com.dingring.domain.discussion.TopicStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ConclusionWatchdog} 收束看门狗单元测试。
 * <p>核心：超时 CONCLUDING 主题回滚 IN_PROGRESS + 推送状态变更 + 唤醒引擎；乐观锁冲突静默跳过。
 */
@DisplayName("ConclusionWatchdog 收束看门狗")
class ConclusionWatchdogTest {

    private TopicRepository topicRepository;
    private ChatPusher chatPusher;
    private DiscussionEngine discussionEngine;
    private ConclusionWatchdog watchdog;

    @BeforeEach
    void setUp() throws Exception {
        topicRepository = mock(TopicRepository.class);
        chatPusher = mock(ChatPusher.class);
        discussionEngine = mock(DiscussionEngine.class);
        watchdog = new ConclusionWatchdog(topicRepository, chatPusher, discussionEngine);
        Field f = ConclusionWatchdog.class.getDeclaredField("concludingTimeoutMinutes");
        f.setAccessible(true);
        f.set(watchdog, 5);
    }

    private Topic concludingTopic(Long topicId, Long groupId) {
        Topic t = new Topic();
        t.setId(topicId);
        t.setChatGroupId(groupId);
        t.setTitle("卡死的主题");
        t.setStatus(TopicStatus.CONCLUDING);
        t.setUpdateTime(LocalDateTime.now().minusMinutes(10));
        return t;
    }

    @Test
    @DisplayName("卡死主题回滚 IN_PROGRESS 并推送 + 唤醒引擎")
    void stuckTopicShouldRollbackPushAndWake() {
        Topic stuck = concludingTopic(100L, 1L);
        when(topicRepository.findConcludingBefore(any(LocalDateTime.class))).thenReturn(List.of(stuck));
        when(topicRepository.update(stuck)).thenReturn(true);

        watchdog.rescueStuckTopics();

        ArgumentCaptor<Topic> captor = ArgumentCaptor.forClass(Topic.class);
        verify(topicRepository).update(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(TopicStatus.IN_PROGRESS);
        verify(chatPusher).pushToGroup(eq(1L), eq(WsConstants.TOPIC_STATUS_CHANGED), any());
        verify(discussionEngine).wake(1L);
    }

    @Test
    @DisplayName("乐观锁冲突时静默跳过（不推送不唤醒）")
    void optimisticLockConflictShouldSkipSilently() {
        Topic stuck = concludingTopic(100L, 1L);
        when(topicRepository.findConcludingBefore(any(LocalDateTime.class))).thenReturn(List.of(stuck));
        when(topicRepository.update(stuck)).thenReturn(false);

        watchdog.rescueStuckTopics();

        verify(chatPusher, never()).pushToGroup(any(), any(), any());
        verify(discussionEngine, never()).wake(any());
    }

    @Test
    @DisplayName("单个主题处理异常不影响其余主题救援")
    void oneFailureShouldNotBlockOthers() {
        Topic bad = concludingTopic(100L, 1L);
        bad.setStatus(TopicStatus.CLOSED); // rollbackToInProgress 会抛状态机异常
        Topic good = concludingTopic(101L, 2L);
        when(topicRepository.findConcludingBefore(any(LocalDateTime.class))).thenReturn(List.of(bad, good));
        when(topicRepository.update(good)).thenReturn(true);

        watchdog.rescueStuckTopics();

        verify(discussionEngine).wake(2L);
    }

    @Test
    @DisplayName("无卡死主题时不做任何事")
    void noStuckTopicShouldDoNothing() {
        when(topicRepository.findConcludingBefore(any(LocalDateTime.class))).thenReturn(List.of());

        watchdog.rescueStuckTopics();

        verify(topicRepository, never()).update(any());
        verify(discussionEngine, never()).wake(any());
    }
}
