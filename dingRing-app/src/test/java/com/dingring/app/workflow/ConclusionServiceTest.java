package com.dingring.app.workflow;

import com.dingring.app.orchestrator.ContextBuilder;
import com.dingring.app.orchestrator.SpeakerScheduler;
import com.dingring.app.service.MessageAssembler;
import com.dingring.common.constant.WsConstants;
import com.dingring.common.exception.BizException;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.agent.AgentRepository;
import com.dingring.domain.discussion.Topic;
import com.dingring.domain.discussion.TopicRepository;
import com.dingring.domain.discussion.TopicStatus;
import com.dingring.domain.event.TopicClosed;
import com.dingring.domain.group.Group;
import com.dingring.domain.group.GroupMember;
import com.dingring.domain.group.GroupRepository;
import com.dingring.domain.group.MemberRole;
import com.dingring.domain.group.MemberType;
import com.dingring.domain.group.MessageRepository;
import com.dingring.domain.service.AgentSpeakerService;
import com.dingring.domain.service.DomainEventPublisher;
import com.dingring.domain.service.GroupBroadcastService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ConclusionService} 异步结论生成测试。
 * <p>验证：triggerAsync 同步流转+异步提交、generate 幂等关闭/失败回滚、per-topic 并发守卫。
 * <p>ConclusionExecutor 为 mock：捕获提交的 Runnable 手动执行，避免测试真正起虚拟线程。
 */
@DisplayName("ConclusionService 异步结论生成")
class ConclusionServiceTest {

    private TopicRepository topicRepository;
    private GroupRepository groupRepository;
    private AgentRepository agentRepository;
    private MessageRepository messageRepository;
    private SpeakerScheduler speakerScheduler;
    private ContextBuilder contextBuilder;
    private MessageAssembler messageAssembler;
    private AgentSpeakerService agentSpeakerService;
    private DomainEventPublisher eventPublisher;
    private GroupBroadcastService groupBroadcastService;
    private ConclusionExecutor conclusionExecutor;
    private ConclusionService service;

    private static final long TOPIC_ID = 1L;
    private static final long GROUP_ID = 2L;
    private static final long AGENT_ID = 5L;

    @BeforeEach
    void setUp() {
        topicRepository = mock(TopicRepository.class);
        groupRepository = mock(GroupRepository.class);
        agentRepository = mock(AgentRepository.class);
        messageRepository = mock(MessageRepository.class);
        speakerScheduler = mock(SpeakerScheduler.class);
        contextBuilder = mock(ContextBuilder.class);
        messageAssembler = mock(MessageAssembler.class);
        agentSpeakerService = mock(AgentSpeakerService.class);
        eventPublisher = mock(DomainEventPublisher.class);
        groupBroadcastService = mock(GroupBroadcastService.class);
        conclusionExecutor = mock(ConclusionExecutor.class);
        service = new ConclusionService(topicRepository, groupRepository, agentRepository, messageRepository,
                speakerScheduler, contextBuilder, messageAssembler, agentSpeakerService,
                eventPublisher, groupBroadcastService, conclusionExecutor);
    }

    /* ==================== 辅助构造 ==================== */

    private Topic topic(TopicStatus status) {
        Topic t = new Topic();
        t.setId(TOPIC_ID);
        t.setChatGroupId(GROUP_ID);
        t.setTitle("主题");
        t.setStatus(status);
        return t;
    }

    private Group group() {
        Group g = new Group();
        g.setId(GROUP_ID);
        g.setGroupMember(List.of(new GroupMember(AGENT_ID, MemberType.AGENT, MemberRole.MEMBER)));
        return g;
    }

    private Agent agent() {
        Agent a = new Agent();
        a.setId(AGENT_ID);
        a.setName("老王");
        return a;
    }

    /** 准备 generate 成功路径所需的全部 stub（concluder 解析 + LLM 调用 + 关闭） */
    private void stubGenerateSuccess(Topic t) {
        when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(group()));
        when(agentRepository.findByIds(List.of(AGENT_ID))).thenReturn(List.of(agent()));
        when(speakerScheduler.rank(any(), any()))
                .thenReturn(List.of(new SpeakerScheduler.ScoredAgent(agent(), 100, "FREE_SCHEDULE")));
        when(contextBuilder.buildForConclusion(any(), any(), any(), any(), any()))
                .thenReturn(new ContextBuilder.LlmContext("sp", List.of()));
        when(agentSpeakerService.call(any(), any(), any(), any(), any()))
                .thenReturn(AgentSpeakerService.AgentResult.of("结论内容"));
        when(topicRepository.update(t)).thenReturn(true);
        when(messageRepository.countByTopicId(TOPIC_ID)).thenReturn(2L);
    }

    /* ==================== triggerAsync ==================== */

    @Test
    @DisplayName("IN_PROGRESS：同步流转为 CONCLUDING 并提交异步任务")
    void triggerShouldTransitionAndSubmit() {
        Topic t = topic(TopicStatus.IN_PROGRESS);
        when(topicRepository.findById(TOPIC_ID)).thenReturn(Optional.of(t));
        when(topicRepository.update(t)).thenReturn(true);

        service.triggerAsync(TOPIC_ID, GROUP_ID, "USER", null);

        assertThat(t.getStatus()).isEqualTo(TopicStatus.CONCLUDING);
        verify(topicRepository).update(t);
        verify(conclusionExecutor).execute(any());
        verify(groupBroadcastService).broadcast(eq(GROUP_ID), eq(WsConstants.TOPIC_STATUS_CHANGED), any());
    }

    @Test
    @DisplayName("已 CLOSED：忽略，不流转不提交")
    void triggerShouldIgnoreClosed() {
        Topic t = topic(TopicStatus.CLOSED);
        when(topicRepository.findById(TOPIC_ID)).thenReturn(Optional.of(t));

        service.triggerAsync(TOPIC_ID, GROUP_ID, "USER", null);

        verify(topicRepository, never()).update(any());
        verify(conclusionExecutor, never()).execute(any());
    }

    @Test
    @DisplayName("已 CONCLUDING：跳过流转但仍提交（幂等，generate 侧去重）")
    void triggerShouldSubmitWhenAlreadyConcluding() {
        Topic t = topic(TopicStatus.CONCLUDING);
        when(topicRepository.findById(TOPIC_ID)).thenReturn(Optional.of(t));

        service.triggerAsync(TOPIC_ID, GROUP_ID, "AGENT", null);

        verify(topicRepository, never()).update(any());
        verify(conclusionExecutor).execute(any());
    }

    @Test
    @DisplayName("乐观锁流转冲突：抛业务异常")
    void triggerShouldThrowOnTransitionConflict() {
        Topic t = topic(TopicStatus.IN_PROGRESS);
        when(topicRepository.findById(TOPIC_ID)).thenReturn(Optional.of(t));
        when(topicRepository.update(t)).thenReturn(false);

        assertThatThrownBy(() -> service.triggerAsync(TOPIC_ID, GROUP_ID, "USER", null))
                .isInstanceOf(BizException.class);
    }

    /* ==================== generate ==================== */

    @Test
    @DisplayName("成功：CONCLUDING → CLOSED + 广播 TOPIC_CLOSED + 发 TopicClosed 事件")
    void generateShouldCloseAndBroadcast() {
        Topic t = topic(TopicStatus.CONCLUDING);
        when(topicRepository.findById(TOPIC_ID)).thenReturn(Optional.of(t));
        stubGenerateSuccess(t);

        service.generate(TOPIC_ID, GROUP_ID, "USER", null);

        assertThat(t.getStatus()).isEqualTo(TopicStatus.CLOSED);
        assertThat(t.getConclusion()).isEqualTo("结论内容");
        verify(topicRepository).update(t);
        verify(groupBroadcastService).broadcast(eq(GROUP_ID), eq(WsConstants.TOPIC_CLOSED), any());
        verify(eventPublisher).publish(any(TopicClosed.class));
    }

    @Test
    @DisplayName("LLM 返回空结论：回滚为 IN_PROGRESS + 广播 ERROR")
    void generateShouldRollbackOnEmptyConclusion() {
        Topic t = topic(TopicStatus.CONCLUDING);
        when(topicRepository.findById(TOPIC_ID)).thenReturn(Optional.of(t));
        when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(group()));
        when(agentRepository.findByIds(List.of(AGENT_ID))).thenReturn(List.of(agent()));
        when(speakerScheduler.rank(any(), any()))
                .thenReturn(List.of(new SpeakerScheduler.ScoredAgent(agent(), 100, "FREE_SCHEDULE")));
        when(contextBuilder.buildForConclusion(any(), any(), any(), any(), any()))
                .thenReturn(new ContextBuilder.LlmContext("sp", List.of()));
        when(agentSpeakerService.call(any(), any(), any(), any(), any()))
                .thenReturn(AgentSpeakerService.AgentResult.of(""));

        service.generate(TOPIC_ID, GROUP_ID, "USER", null);

        assertThat(t.getStatus()).isEqualTo(TopicStatus.IN_PROGRESS);
        verify(groupBroadcastService).broadcast(eq(GROUP_ID), eq(WsConstants.ERROR), any());
        verify(groupBroadcastService, never()).broadcast(eq(GROUP_ID), eq(WsConstants.TOPIC_CLOSED), any());
    }

    @Test
    @DisplayName("Topic 已 CLOSED：直接放弃，不调 LLM 不广播")
    void generateShouldNoopWhenAlreadyClosed() {
        Topic t = topic(TopicStatus.CLOSED);
        when(topicRepository.findById(TOPIC_ID)).thenReturn(Optional.of(t));

        service.generate(TOPIC_ID, GROUP_ID, "USER", null);

        verify(agentSpeakerService, never()).call(any(), any(), any(), any(), any());
        verify(groupBroadcastService, never()).broadcast(eq(GROUP_ID), eq(WsConstants.TOPIC_CLOSED), any());
    }

    /* ==================== 并发守卫 ==================== */

    @Test
    @DisplayName("同一 Topic 并发触发：inflight 守卫保证只跑一次结论生成")
    void concurrentTriggerShouldRunOnlyOneGeneration() throws Exception {
        Topic t = topic(TopicStatus.CONCLUDING);
        when(topicRepository.findById(TOPIC_ID)).thenReturn(Optional.of(t));
        stubGenerateSuccess(t);

        service.triggerAsync(TOPIC_ID, GROUP_ID, "USER", null);
        service.triggerAsync(TOPIC_ID, GROUP_ID, "USER", null);

        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(conclusionExecutor, times(2)).execute(captor.capture());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures = captor.getAllValues().stream().map(pool::submit).toList();
            for (Future<?> f : futures) {
                f.get(3, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // 两个任务并发执行，per-topic CAS 只放行一个进入 generate
        verify(agentSpeakerService, times(1)).call(any(), any(), any(), any(), any());
        verify(groupBroadcastService, times(1)).broadcast(eq(GROUP_ID), eq(WsConstants.TOPIC_CLOSED), any());
    }
}
