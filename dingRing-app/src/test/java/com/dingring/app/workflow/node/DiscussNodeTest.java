package com.dingring.app.workflow.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.dingring.app.orchestrator.ContextBuilder;
import com.dingring.app.orchestrator.SpeakerScheduler;
import com.dingring.app.orchestrator.Terminator;
import com.dingring.app.service.MessageAssembler;
import com.dingring.common.constant.WsConstants;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.agent.AgentRepository;
import com.dingring.domain.group.Group;
import com.dingring.domain.group.GroupMember;
import com.dingring.domain.group.GroupMessage;
import com.dingring.domain.group.GroupRepository;
import com.dingring.domain.group.MemberRole;
import com.dingring.domain.group.MemberType;
import com.dingring.domain.group.MessageRepository;
import com.dingring.domain.service.AgentSpeakerService;
import com.dingring.domain.service.DomainEventPublisher;
import com.dingring.domain.service.GroupBroadcastService;
import com.dingring.domain.workflow.StateKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DiscussNode} 讨论推进节点单测（统一 @提及 策略后）。
 * <p>验证"保证一次发言权"：
 * <ul>
 *   <li>被 @ 者豁免未消费（mentionHandled=false）时，从 PASS 中恢复候选并优先发言</li>
 *   <li>豁免已消费（mentionHandled=true）后不再豁免，回归正常 PASS/轮转</li>
 *   <li>被 @ 者本轮 PASS 同样消费豁免（本轮结束无论 SPOKE/PASSED 均置 true）</li>
 * </ul>
 */
@DisplayName("DiscussNode 讨论推进节点")
class DiscussNodeTest {

    private GroupRepository groupRepository;
    private AgentRepository agentRepository;
    private MessageRepository messageRepository;
    private SpeakerScheduler speakerScheduler;
    private ContextBuilder contextBuilder;
    private MessageAssembler messageAssembler;
    private AgentSpeakerService agentSpeakerService;
    private DomainEventPublisher eventPublisher;
    private GroupBroadcastService groupBroadcastService;
    private Terminator terminator;
    private DiscussNode node;

    @BeforeEach
    void setUp() {
        groupRepository = mock(GroupRepository.class);
        agentRepository = mock(AgentRepository.class);
        messageRepository = mock(MessageRepository.class);
        speakerScheduler = new SpeakerScheduler();
        contextBuilder = mock(ContextBuilder.class);
        messageAssembler = mock(MessageAssembler.class);
        agentSpeakerService = mock(AgentSpeakerService.class);
        eventPublisher = mock(DomainEventPublisher.class);
        groupBroadcastService = mock(GroupBroadcastService.class);
        terminator = mock(Terminator.class);
        node = new DiscussNode(groupRepository, agentRepository, messageRepository, speakerScheduler,
                contextBuilder, messageAssembler, agentSpeakerService, eventPublisher,
                groupBroadcastService, terminator);
    }

    private Group groupWithAgentIds(Long groupId, List<Long> memberAgentIds) {
        Group g = new Group();
        g.setId(groupId);
        List<GroupMember> members = new ArrayList<>();
        memberAgentIds.forEach(id -> members.add(new GroupMember(id, MemberType.AGENT, MemberRole.MEMBER)));
        g.setGroupMember(members);
        return g;
    }

    private Agent agent(Long id, String name) {
        Agent a = new Agent();
        a.setId(id);
        a.setName(name);
        a.setSystemPrompt("测试人设");
        return a;
    }

    private void mockCommon() {
        when(groupRepository.findById(1L)).thenReturn(Optional.of(groupWithAgentIds(1L, List.of(10L, 20L))));
        when(agentRepository.findByIds(List.of(10L, 20L))).thenReturn(List.of(agent(10L, "柯南"), agent(20L, "灰原")));
        when(terminator.reachedMaxRounds(anyLong())).thenReturn(false);
        when(messageRepository.countByTopicIdAndSender(anyLong(), anyLong(), any())).thenReturn(0L);
        when(messageRepository.findLastByGroupId(1L)).thenReturn(Optional.empty());
        when(contextBuilder.build(any(Agent.class), anyLong(), anyLong(), any()))
                .thenReturn(new ContextBuilder.LlmContext("测试systemPrompt", List.of()));
        // 默认所有 Agent 正常发言
        when(agentSpeakerService.call(any(Agent.class), anyString(), anyList(),
                eq(AgentSpeakerService.ToolSet.DISCUSS), any(Map.class)))
                .thenReturn(AgentSpeakerService.AgentResult.of("讨论内容"));
    }

    private OverAllState state(List<Long> passed, List<Long> mentioned, boolean mentionHandled) {
        Map<String, Object> values = new HashMap<>();
        values.put(StateKeys.GROUP_ID, 1L);
        values.put(StateKeys.TOPIC_ID, 99L);
        values.put(StateKeys.PASSED_AGENT_IDS, passed);
        values.put(StateKeys.MENTIONED_AGENT_IDS, mentioned);
        values.put(StateKeys.MENTION_HANDLED, mentionHandled);
        return new OverAllState(values);
    }

    @Test
    @DisplayName("被 @ 者豁免未消费时：从 PASS 恢复候选并优先发言，本轮消费豁免")
    void shouldExemptMentionedOnceAndSpeak() {
        mockCommon();

        Map<String, Object> result = node.apply(state(List.of(20L), List.of(20L), false));

        // 灰原(20)虽已 PASS，但 @提及豁免后仍被选为发言者（评分加分排第一）
        ArgumentCaptor<Agent> agentCaptor = ArgumentCaptor.forClass(Agent.class);
        verify(agentSpeakerService).call(agentCaptor.capture(), anyString(), anyList(),
                eq(AgentSpeakerService.ToolSet.DISCUSS), any(Map.class));
        assertThat(agentCaptor.getValue().getId()).isEqualTo(20L);
        // 本轮豁免已消费
        assertThat(result).containsEntry(StateKeys.MENTION_HANDLED, true);
        assertThat(result).containsEntry(StateKeys.SPEAKER_AGENT_ID, 20L);
    }

    @Test
    @DisplayName("豁免已消费后不再豁免：被 @ 者已 PASS 则保持排除，由其他成员发言")
    void shouldNotExemptWhenAlreadyHandled() {
        mockCommon();

        Map<String, Object> result = node.apply(state(List.of(20L), List.of(20L), true));

        // 灰原(20) 已被豁免过（mentionHandled=true），保持在 PASS 集合中，由柯南(10)发言
        ArgumentCaptor<Agent> agentCaptor = ArgumentCaptor.forClass(Agent.class);
        verify(agentSpeakerService).call(agentCaptor.capture(), anyString(), anyList(),
                eq(AgentSpeakerService.ToolSet.DISCUSS), any(Map.class));
        assertThat(agentCaptor.getValue().getId()).isEqualTo(10L);
        // 透传原值 true
        assertThat(result).containsEntry(StateKeys.MENTION_HANDLED, true);
        assertThat(result).containsEntry(StateKeys.SPEAKER_AGENT_ID, 10L);
    }

    @Test
    @DisplayName("被 @ 者本轮 PASS：豁免同样消费，不再无限豁免")
    void shouldConsumeExemptionEvenWhenPassed() {
        mockCommon();
        // 被 @ 的灰原返回空内容 → PASS
        when(agentSpeakerService.call(any(Agent.class), anyString(), anyList(),
                eq(AgentSpeakerService.ToolSet.DISCUSS), any(Map.class)))
                .thenReturn(AgentSpeakerService.AgentResult.of(""));

        Map<String, Object> result = node.apply(state(List.of(), List.of(20L), false));

        // 灰原(20)因 @提及被豁免并获得发言机会，但 PASS 了 → 本轮结束豁免被消费
        assertThat(result).containsEntry(StateKeys.MENTION_HANDLED, true);
        // PASS 累积：灰原进入本轮 PASS 列表
        assertThat(result.get(StateKeys.PASSED_AGENT_IDS)).asList().contains(20L);
        assertThat(result).containsEntry(StateKeys.DISCUSS_MODE, StateKeys.MODE_DIVERGE);
    }

    @Test
    @DisplayName("无提及时正常轮转：mentionHandled 透传原值")
    void shouldPassThroughWhenNoMention() {
        mockCommon();

        Map<String, Object> result = node.apply(state(List.of(), List.of(), false));

        verify(agentSpeakerService, times(1)).call(any(Agent.class), anyString(), anyList(),
                eq(AgentSpeakerService.ToolSet.DISCUSS), any(Map.class));
        assertThat(result).containsEntry(StateKeys.MENTION_HANDLED, false);
        assertThat(result).containsEntry(StateKeys.DISCUSS_MODE, StateKeys.MODE_CONVERGE);
        // 正常发言无 ERROR 广播
        verify(groupBroadcastService, never()).broadcast(anyLong(), eq(WsConstants.ERROR), any());
    }
}
