package com.dingring.infrastructure.memory;

import com.dingring.domain.discussion.Topic;
import com.dingring.domain.discussion.TopicRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * {@link SimpleMemoryService} 单元测试。
 * <p>记忆服务 v1.0：拼接同群已 CLOSED 的 Topic 结论文本，限制最多 5 条。
 */
@DisplayName("SimpleMemoryService 记忆检索")
@ExtendWith(MockitoExtension.class)
class SimpleMemoryServiceTest {

    @Mock
    private TopicRepository topicRepository;

    @InjectMocks
    private SimpleMemoryService service;

    private Topic closedTopic(Long id, String title, String conclusion) {
        Topic t = new Topic();
        t.setId(id);
        t.setTitle(title);
        t.setConclusion(conclusion);
        t.setCreateTime(LocalDateTime.now());
        return t;
    }

    @Test
    @DisplayName("无历史 Topic 时返回空字符串")
    void noClosedTopicsShouldReturnEmpty() {
        when(topicRepository.findClosedByGroupId(1L)).thenReturn(List.of());

        String memory = service.retrieveMemory(1L);

        assertThat(memory).isEmpty();
    }

    @Test
    @DisplayName("返回 null 时也返回空字符串（防御性）")
    void nullClosedTopicsShouldReturnEmpty() {
        when(topicRepository.findClosedByGroupId(1L)).thenReturn(null);

        String memory = service.retrieveMemory(1L);

        assertThat(memory).isEmpty();
    }

    @Test
    @DisplayName("拼接历史 Topic 的标题和结论")
    void shouldConcatenateTitleAndConclusion() {
        when(topicRepository.findClosedByGroupId(1L)).thenReturn(List.of(
                closedTopic(10L, "JVM 调优", "## 结论\n使用 G1 GC"),
                closedTopic(11L, "并发编程", "## 结论\n用虚拟线程")
        ));

        String memory = service.retrieveMemory(1L);

        assertThat(memory)
                .contains("## 本群历史讨论结论")
                .contains("JVM 调优")
                .contains("使用 G1 GC")
                .contains("并发编程")
                .contains("用虚拟线程");
    }

    @Test
    @DisplayName("conclusion 为 null/blank 的 Topic 被过滤掉")
    void blankConclusionShouldBeFiltered() {
        when(topicRepository.findClosedByGroupId(1L)).thenReturn(List.of(
                closedTopic(10L, "无结论主题", null),
                closedTopic(11L, "空结论主题", "   "),
                closedTopic(12L, "有效主题", "## 结论\n有效内容")
        ));

        String memory = service.retrieveMemory(1L);

        assertThat(memory)
                .contains("有效主题")
                .contains("有效内容")
                .doesNotContain("无结论主题")
                .doesNotContain("空结论主题");
    }

    @Test
    @DisplayName("超过 5 条历史时只取前 5 条（防止 Prompt 过长）")
    void shouldLimitToFiveTopics() {
        // 构造 8 条历史
        List<Topic> topics = java.util.stream.LongStream.rangeClosed(1, 8)
                .mapToObj(i -> closedTopic(i, "主题" + i, "结论" + i))
                .toList();
        when(topicRepository.findClosedByGroupId(1L)).thenReturn(topics);

        String memory = service.retrieveMemory(1L);

        // 前 5 条出现，后 3 条不出现
        assertThat(memory)
                .contains("主题1", "主题5")
                .doesNotContain("主题6", "主题7", "主题8");
    }
}
