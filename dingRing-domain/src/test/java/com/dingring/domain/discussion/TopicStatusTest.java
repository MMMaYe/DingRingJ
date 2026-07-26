package com.dingring.domain.discussion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TopicStatus} 状态机单元测试。
 * <p>状态机不变式（见技术方案 6.1）：
 * <pre>
 * IN_PROGRESS --@专家/最大轮次--> CONCLUDING --结论成功--> CLOSED --手动归档--> ARCHIVED
 *      ^                             |
 *      +--------结论生成失败(回退)-----+
 * </pre>
 */
@DisplayName("TopicStatus 状态机")
class TopicStatusTest {

    @Nested
    @DisplayName("IN_PROGRESS 流转")
    class InProgress {

        @Test
        @DisplayName("IN_PROGRESS -> CONCLUDING 允许")
        void canTransitToConcluding() {
            assertThat(TopicStatus.IN_PROGRESS.canTransitTo(TopicStatus.CONCLUDING)).isTrue();
        }

        @Test
        @DisplayName("IN_PROGRESS -> CLOSED 禁止（必须先经过 CONCLUDING）")
        void cannotTransitDirectlyToClosed() {
            assertThat(TopicStatus.IN_PROGRESS.canTransitTo(TopicStatus.CLOSED)).isFalse();
        }

        @Test
        @DisplayName("IN_PROGRESS -> ARCHIVED 禁止")
        void cannotTransitToArchived() {
            assertThat(TopicStatus.IN_PROGRESS.canTransitTo(TopicStatus.ARCHIVED)).isFalse();
        }
    }

    @Nested
    @DisplayName("CONCLUDING 流转")
    class Concluding {

        @Test
        @DisplayName("CONCLUDING -> CLOSED 允许（结论成功）")
        void canTransitToClosed() {
            assertThat(TopicStatus.CONCLUDING.canTransitTo(TopicStatus.CLOSED)).isTrue();
        }

        @Test
        @DisplayName("CONCLUDING -> IN_PROGRESS 允许（结论失败回退）")
        void canRollbackToInProgress() {
            assertThat(TopicStatus.CONCLUDING.canTransitTo(TopicStatus.IN_PROGRESS)).isTrue();
        }

        @Test
        @DisplayName("CONCLUDING -> ARCHIVED 禁止（不能跳过 CLOSED）")
        void cannotTransitToArchived() {
            assertThat(TopicStatus.CONCLUDING.canTransitTo(TopicStatus.ARCHIVED)).isFalse();
        }
    }

    @Nested
    @DisplayName("CLOSED 流转")
    class Closed {

        @Test
        @DisplayName("CLOSED -> ARCHIVED 允许（手动归档）")
        void canTransitToArchived() {
            assertThat(TopicStatus.CLOSED.canTransitTo(TopicStatus.ARCHIVED)).isTrue();
        }

        @Test
        @DisplayName("CLOSED -> IN_PROGRESS 禁止（不能复活）")
        void cannotReopen() {
            assertThat(TopicStatus.CLOSED.canTransitTo(TopicStatus.IN_PROGRESS)).isFalse();
        }

        @Test
        @DisplayName("CLOSED -> CONCLUDING 禁止（不能再次结论）")
        void cannotReConclude() {
            assertThat(TopicStatus.CLOSED.canTransitTo(TopicStatus.CONCLUDING)).isFalse();
        }
    }

    @Test
    @DisplayName("ARCHIVED 是终态，不能流转到任何状态")
    void archivedIsTerminal() {
        for (TopicStatus target : TopicStatus.values()) {
            assertThat(TopicStatus.ARCHIVED.canTransitTo(target))
                    .as("ARCHIVED -> %s 应禁止", target)
                    .isFalse();
        }
    }
}
