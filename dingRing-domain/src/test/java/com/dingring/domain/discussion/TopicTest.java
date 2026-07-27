package com.dingring.domain.discussion;

import com.dingring.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link Topic} 聚合根单元测试：覆盖状态流转方法与非法流转异常。
 */
@DisplayName("Topic 聚合根")
class TopicTest {

    private Topic newTopic(TopicStatus status) {
        Topic t = new Topic();
        t.setId(1L);
        t.setChatGroupId(10L);
        t.setTitle("Java 内存模型");
        t.setStatus(status);
        return t;
    }

    @Nested
    @DisplayName("startConcluding 触发结束")
    class StartConcluding {

        @Test
        @DisplayName("IN_PROGRESS -> CONCLUDING")
        void shouldTransitToConcluding() {
            Topic t = newTopic(TopicStatus.IN_PROGRESS);

            t.startConcluding();

            assertThat(t.getStatus()).isEqualTo(TopicStatus.CONCLUDING);
        }

        @Test
        @DisplayName("非 IN_PROGRESS 状态触发结束抛 BizException")
        void shouldThrowWhenNotInProgress() {
            Topic t = newTopic(TopicStatus.CLOSED);

            assertThatThrownBy(t::startConcluding)
                    .isInstanceOf(BizException.class);
        }
    }

    @Nested
    @DisplayName("close 结论生成成功")
    class Close {

        @Test
        @DisplayName("CONCLUDING -> CLOSED 并填充结论、关闭时间与总结人")
        void shouldCloseWithConclusion() {
            Topic t = newTopic(TopicStatus.CONCLUDING);

            t.close("## STAR 结论\n...", 99L);

            assertThat(t.getStatus()).isEqualTo(TopicStatus.CLOSED);
            assertThat(t.getConclusion()).isEqualTo("## STAR 结论\n...");
            assertThat(t.getClosedAt()).isNotNull();
            assertThat(t.concludedByAgentId()).hasValue(99L);
        }

        @Test
        @DisplayName("总结人为 null 时 concludedByAgentId 返回 empty")
        void nullConcluderShouldReturnEmpty() {
            Topic t = newTopic(TopicStatus.CONCLUDING);

            t.close("## STAR 结论", null);

            assertThat(t.getStatus()).isEqualTo(TopicStatus.CLOSED);
            assertThat(t.concludedByAgentId()).isEmpty();
        }

        @Test
        @DisplayName("IN_PROGRESS 不能直接 close")
        void cannotCloseFromInProgress() {
            Topic t = newTopic(TopicStatus.IN_PROGRESS);

            assertThatThrownBy(() -> t.close("x", 99L))
                    .isInstanceOf(BizException.class);
        }
    }

    @Test
    @DisplayName("rollbackToInProgress 回退状态到 IN_PROGRESS")
    void rollbackShouldReturnToInProgress() {
        Topic t = newTopic(TopicStatus.CONCLUDING);

        t.rollbackToInProgress();

        assertThat(t.getStatus()).isEqualTo(TopicStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("archive 将 CLOSED 转为 ARCHIVED")
    void archiveShouldTransitClosedToArchived() {
        Topic t = newTopic(TopicStatus.CLOSED);
        t.setConclusion("ok");
        t.setClosedAt(LocalDateTime.now());

        t.archive();

        assertThat(t.getStatus()).isEqualTo(TopicStatus.ARCHIVED);
        // 归档不应清空已有数据
        assertThat(t.getConclusion()).isEqualTo("ok");
    }

    @Test
    @DisplayName("isInProgress 仅在 IN_PROGRESS 时为 true")
    void isInProgressShouldReflectStatus() {
        assertThat(newTopic(TopicStatus.IN_PROGRESS).isInProgress()).isTrue();
        assertThat(newTopic(TopicStatus.CONCLUDING).isInProgress()).isFalse();
        assertThat(newTopic(TopicStatus.CLOSED).isInProgress()).isFalse();
        assertThat(newTopic(TopicStatus.ARCHIVED).isInProgress()).isFalse();
    }

    @Test
    @DisplayName("status 为 null 时任何流转都抛异常（防御未初始化）")
    void nullStatusShouldRejectAnyTransition() {
        Topic t = new Topic();
        t.setStatus(null);

        assertThatThrownBy(t::startConcluding)
                .isInstanceOf(BizException.class);
    }
}
