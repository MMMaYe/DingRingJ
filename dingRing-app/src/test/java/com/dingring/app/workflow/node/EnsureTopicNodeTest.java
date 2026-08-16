package com.dingring.app.workflow.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.dingring.app.service.GroupAppService;
import com.dingring.domain.discussion.Topic;
import com.dingring.domain.discussion.TopicRepository;
import com.dingring.domain.group.GroupRepository;
import com.dingring.domain.group.MessageRepository;
import com.dingring.domain.service.DomainEventPublisher;
import com.dingring.domain.service.GroupBroadcastService;
import com.dingring.domain.service.TopicVectorService;
import com.dingring.domain.user.UserTopicProfile;
import com.dingring.domain.user.UserTopicProfileRepository;
import com.dingring.domain.workflow.StateKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link EnsureTopicNode} 建题节点单测（P2 语义回溯后）。
 * <p>验证：
 * <ul>
 *   <li>建题主流程：HIGH 立即建 / LOW 未达门槛不建 / 已有活跃话题跳过</li>
 *   <li>语义回溯命中：相似话题结论注入 userHistoryHint，restartHint 提示参考既往结论</li>
 *   <li>容错链：向量服务异常回退标题精确匹配，任何情况不阻塞建题</li>
 *   <li>自排除：向量库召回自身时过滤后回退精确匹配</li>
 * </ul>
 */
@DisplayName("EnsureTopicNode 建题节点（语义回溯）")
class EnsureTopicNodeTest {

    private static final String TITLE = "Java 并发编程";

    private GroupRepository groupRepository;
    private TopicRepository topicRepository;
    private MessageRepository messageRepository;
    private DomainEventPublisher eventPublisher;
    private GroupBroadcastService groupBroadcastService;
    private UserTopicProfileRepository topicProfileRepository;
    private TopicVectorService topicVectorService;
    private EnsureTopicNode node;

    @BeforeEach
    void setUp() {
        groupRepository = mock(GroupRepository.class);
        topicRepository = mock(TopicRepository.class);
        messageRepository = mock(MessageRepository.class);
        eventPublisher = mock(DomainEventPublisher.class);
        groupBroadcastService = mock(GroupBroadcastService.class);
        topicProfileRepository = mock(UserTopicProfileRepository.class);
        topicVectorService = mock(TopicVectorService.class);
        node = new EnsureTopicNode(groupRepository, topicRepository, messageRepository,
                eventPublisher, groupBroadcastService, topicProfileRepository, topicVectorService);
    }

    /** 建题成功的公共前置：save 回填 ID=100、无回填消息、无已关闭主题、语义/精确回溯均无历史 */
    private void mockCreateSuccess() {
        when(topicRepository.save(any(Topic.class))).thenAnswer(inv -> {
            Topic t = inv.getArgument(0);
            t.setId(100L);
            return 100L;
        });
        when(messageRepository.findRecentChatByGroupId(1L, 15)).thenReturn(List.of());
        when(topicRepository.findClosedByGroupId(1L)).thenReturn(List.of());
        when(topicVectorService.findSimilarTopics(anyString(), anyInt(), anyDouble()))
                .thenReturn(List.of());
        when(topicProfileRepository.findByUserIdAndTopicTitleOrderByCreatedAtDesc(anyLong(), anyString()))
                .thenReturn(List.of());
    }

    private OverAllState state(String confidence) {
        Map<String, Object> values = new HashMap<>();
        values.put(StateKeys.GROUP_ID, 1L);
        values.put(StateKeys.TOPIC_TITLE, TITLE);
        values.put(StateKeys.CONFIDENCE, confidence);
        return new OverAllState(values);
    }

    private UserTopicProfile profile(String topicTitle, String weakPoints) {
        UserTopicProfile p = new UserTopicProfile();
        p.setUserId(GroupAppService.DEFAULT_USER_ID);
        p.setTopicTitle(topicTitle);
        p.setUnderstandingLevel("INTERMEDIATE");
        p.setWeakPoints(weakPoints);
        p.setSuggestedFocus("结合实例练习");
        return p;
    }

    @Test
    @DisplayName("HIGH 置信度建题成功且无历史：默认 restartHint 文案、userHistoryHint 为空")
    void shouldCreateTopicWithDefaultHintWhenNoHistory() {
        mockCreateSuccess();

        Map<String, Object> result = node.apply(state("HIGH"));

        assertThat(result).containsEntry(StateKeys.ENSURE_SUCCESS, true);
        assertThat(result).containsEntry(StateKeys.TOPIC_ID, 100L);
        assertThat(result).containsEntry(StateKeys.TOPIC_TITLE, TITLE);
        assertThat(result.get(StateKeys.RESTART_HINT))
                .isEqualTo("新话题「" + TITLE + "」已开始，可以开始讨论");
        assertThat(result.get(StateKeys.USER_HISTORY_HINT)).isEqualTo("");
    }

    @Test
    @DisplayName("LOW 置信度未达门槛：不建题，streak 累加")
    void shouldNotCreateWhenLowStreakBelowThreshold() {
        Map<String, Object> values = new HashMap<>();
        values.put(StateKeys.GROUP_ID, 1L);
        values.put(StateKeys.TOPIC_TITLE, TITLE);
        values.put(StateKeys.CONFIDENCE, "LOW");
        values.put(StateKeys.LOW_DISCUSS_STREAK, 0);

        Map<String, Object> result = node.apply(new OverAllState(values));

        assertThat(result).containsEntry(StateKeys.ENSURE_SUCCESS, false);
        assertThat(result).containsEntry(StateKeys.LOW_DISCUSS_STREAK, 1);
        verify(topicRepository, never()).save(any(Topic.class));
    }

    @Test
    @DisplayName("已有活跃话题：跳过建题直接放行")
    void shouldSkipWhenActiveTopicExists() {
        Map<String, Object> values = new HashMap<>();
        values.put(StateKeys.GROUP_ID, 1L);
        values.put(StateKeys.TOPIC_TITLE, TITLE);
        values.put(StateKeys.CONFIDENCE, "HIGH");
        values.put(StateKeys.TOPIC_ID, 99L);

        Map<String, Object> result = node.apply(new OverAllState(values));

        assertThat(result).containsEntry(StateKeys.ENSURE_SUCCESS, true);
        assertThat(result).doesNotContainKey(StateKeys.TOPIC_TITLE);
        verify(topicRepository, never()).save(any(Topic.class));
    }

    @Test
    @DisplayName("语义回溯命中：相似话题结论注入 userHistoryHint")
    void shouldInjectSimilarTopicConclusionWhenSemanticHit() {
        mockCreateSuccess();
        when(topicVectorService.findSimilarTopics(anyString(), anyInt(), anyDouble()))
                .thenReturn(List.of(new TopicVectorService.SimilarTopic(99L, "JUC 并发编程", 0.9)));
        Topic old = new Topic();
        old.setId(99L);
        old.setTitle("JUC 并发编程");
        old.setConclusion("# 锁的粒度选择……");
        when(topicRepository.findById(99L)).thenReturn(Optional.of(old));
        when(topicProfileRepository.findByUserIdAndTopicTitleOrderByCreatedAtDesc(
                GroupAppService.DEFAULT_USER_ID, "JUC 并发编程")).thenReturn(List.of());

        Map<String, Object> result = node.apply(state("HIGH"));

        assertThat(result).containsEntry(StateKeys.ENSURE_SUCCESS, true);
        assertThat(result.get(StateKeys.USER_HISTORY_HINT).toString())
                .contains("JUC 并发编程")
                .contains("锁的粒度选择");
        // 无画像但有相似结论：restartHint 走"参考既往结论"分支
        assertThat(result.get(StateKeys.RESTART_HINT).toString())
                .contains("检测到与历史话题「JUC 并发编程」相关");
    }

    @Test
    @DisplayName("向量服务异常回退标题精确匹配：不阻塞建题，画像注入 hint")
    void shouldFallbackToExactMatchWhenVectorFails() {
        mockCreateSuccess();
        when(topicVectorService.findSimilarTopics(anyString(), anyInt(), anyDouble()))
                .thenThrow(new RuntimeException("pg down"));
        when(topicProfileRepository.findByUserIdAndTopicTitleOrderByCreatedAtDesc(
                GroupAppService.DEFAULT_USER_ID, TITLE))
                .thenReturn(List.of(profile(TITLE, "volatile 可见性")));

        Map<String, Object> result = node.apply(state("HIGH"));

        assertThat(result).containsEntry(StateKeys.ENSURE_SUCCESS, true);
        assertThat(result.get(StateKeys.USER_HISTORY_HINT).toString()).contains("volatile 可见性");
        assertThat(result.get(StateKeys.RESTART_HINT).toString())
                .contains("第2次讨论").contains("volatile 可见性");
    }

    @Test
    @DisplayName("向量召回仅自身：过滤后回退精确匹配，不读自身结论")
    void shouldExcludeSelfAndFallbackWhenOnlySelfRecalled() {
        mockCreateSuccess();
        // topicId=100 与新题 save 后 ID 相同（自身召回）
        when(topicVectorService.findSimilarTopics(anyString(), anyInt(), anyDouble()))
                .thenReturn(List.of(new TopicVectorService.SimilarTopic(100L, TITLE, 0.95)));
        when(topicProfileRepository.findByUserIdAndTopicTitleOrderByCreatedAtDesc(
                GroupAppService.DEFAULT_USER_ID, TITLE))
                .thenReturn(List.of(profile(TITLE, "锁的粒度")));

        Map<String, Object> result = node.apply(state("HIGH"));

        verify(topicRepository, never()).findById(anyLong());
        assertThat(result).containsEntry(StateKeys.ENSURE_SUCCESS, true);
        assertThat(result.get(StateKeys.USER_HISTORY_HINT).toString()).contains("锁的粒度");
    }
}
