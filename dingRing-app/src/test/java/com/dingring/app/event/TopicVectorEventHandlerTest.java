package com.dingring.app.event;

import com.dingring.domain.discussion.Topic;
import com.dingring.domain.event.TopicClosed;
import com.dingring.domain.service.TopicVectorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@link TopicVectorEventHandler} 单测：TopicClosed 异步写入话题向量，失败仅日志。
 */
@DisplayName("TopicVectorEventHandler 话题向量写入")
class TopicVectorEventHandlerTest {

    private TopicVectorService topicVectorService;
    private TopicVectorEventHandler handler;

    @BeforeEach
    void setUp() {
        topicVectorService = mock(TopicVectorService.class);
        handler = new TopicVectorEventHandler(topicVectorService);
    }

    private TopicClosed event() {
        return new TopicClosed(10L, 1L, "Java内存模型", "# 结论", 6, "USER", 3L);
    }

    @Test
    @DisplayName("TopicClosed 触发 indexTopic 且携带正确 topicId/groupId/title")
    void shouldIndexTopicOnTopicClosed() {
        handler.onTopicClosed(event());

        ArgumentCaptor<Topic> captor = ArgumentCaptor.forClass(Topic.class);
        verify(topicVectorService).indexTopic(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(10L);
        assertThat(captor.getValue().getChatGroupId()).isEqualTo(1L);
        assertThat(captor.getValue().getTitle()).isEqualTo("Java内存模型");
    }

    @Test
    @DisplayName("向量写入异常不外抛（不阻塞收束主流程的后续事件消费者）")
    void shouldSwallowIndexFailure() {
        // indexTopic 返回 void：void 方法桩必须用 doThrow 语法
        doThrow(new RuntimeException("pg down")).when(topicVectorService).indexTopic(any());

        assertThatCode(() -> handler.onTopicClosed(event())).doesNotThrowAnyException();
    }
}
