package com.dingring.app.workflow.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.dingring.app.orchestrator.ContextBuilder;
import com.dingring.app.orchestrator.SpeakerScheduler;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChatNode} 闲聊应答节点单测（统一 @提及 策略后）。
 * <p>验证：
 * <ul>
 *   <li>统一走 SpeakerScheduler 评分，被 @ 者大权重加分通常优先（不再硬选）</li>
 *   <li>被 @ 者失败/空内容时按评分顺延下一位候选，不整条 ALL_AGENTS_FAILED</li>
 *   <li>全部候选都失败才广播 ALL_AGENTS_FAILED</li>
 * </ul>
 */
@DisplayName("ChatNode 闲聊应答节点")
class ChatNodeTest {

    private GroupRepository groupRepository;
    private AgentRepository agentRepository;
    private MessageRepository messageRepository;
    private SpeakerScheduler speakerScheduler;
    private ContextBuilder contextBuilder;
    private MessageAssembler messageAssembler;
    private AgentSpeakerService agentSpeakerService;
    private DomainEventPublisher eventPublisher;
    private GroupBroadcastService groupBroadcastService;
    private ChatNode node;

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
        node = new ChatNode(groupRepository, agentRepository, messageRepository, speakerScheduler,
                contextBuilder, messageAssembler, agentSpeakerService, eventPublisher, groupBroadcastService);
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
        when(messageRepository.findLastByGroupId(1L)).thenReturn(Optional.empty());
        when(contextBuilder.build(any(Agent.class), anyLong(), eq(null), any()))
                .thenReturn(new ContextBuilder.LlmContext("测试systemPrompt", List.of()));
        // 默认所有 Agent 都正常回答
        when(agentSpeakerService.call(any(Agent.class), anyString(), anyList(),
                eq(AgentSpeakerService.ToolSet.CHAT), any(Map.class)))
                .thenReturn(AgentSpeakerService.AgentResult.of("正常回答"));
    }

    private OverAllState state(String input, List<Long> mentioned) {
        Map<String, Object> values = new java.util.HashMap<>();
        values.put("groupId", 1L);
        values.put("input", input);
        values.put("mentionedAgentIds", mentioned);
        return new OverAllState(values);
    }

    @Test
    @DisplayName("被 @ 者大权重加分优先发言（统一评分，非硬选）")
    void shouldLetMentionedAgentSpeakFirst() {
        mockCommon();

        Map<String, Object> result = node.apply(state("@灰原 今天天气如何", List.of(20L)));

        // 首位发言成功即停止级联：只有灰原被调用
        ArgumentCaptor<Agent> agentCaptor = ArgumentCaptor.forClass(Agent.class);
        verify(agentSpeakerService, times(1)).call(agentCaptor.capture(), anyString(), anyList(),
                eq(AgentSpeakerService.ToolSet.CHAT), any(Map.class));
        assertThat(agentCaptor.getValue().getId()).isEqualTo(20L);
        assertThat(result).containsEntry(StateKeys.CHAT_BUFFER, 1);
        verify(groupBroadcastService, never()).broadcast(anyLong(), eq(WsConstants.ERROR), any());
    }

    @Test
    @DisplayName("被 @ 者返回空内容时顺延下一位候选发言，不整条报错")
    void shouldCascadeToNextWhenMentionedSilent() {
        mockCommon();
        // 灰原(被@)空内容，柯南正常回答
        when(agentSpeakerService.call(any(Agent.class), anyString(), anyList(),
                eq(AgentSpeakerService.ToolSet.CHAT), any(Map.class)))
                .thenAnswer(invocation -> {
                    Agent a = invocation.getArgument(0);
                    return a.getId().equals(20L)
                            ? AgentSpeakerService.AgentResult.of("")
                            : AgentSpeakerService.AgentResult.of("柯南的回答");
                });

        Map<String, Object> result = node.apply(state("@灰原 帮我分析下", List.of(20L)));

        // 灰原先被尝试，空内容后顺延柯南发言成功
        ArgumentCaptor<Agent> agentCaptor = ArgumentCaptor.forClass(Agent.class);
        verify(agentSpeakerService, times(2)).call(agentCaptor.capture(), anyString(), anyList(),
                eq(AgentSpeakerService.ToolSet.CHAT), any(Map.class));
        assertThat(agentCaptor.getAllValues().stream().map(Agent::getId)).containsExactly(20L, 10L);

        ArgumentCaptor<GroupMessage> msgCaptor = ArgumentCaptor.forClass(GroupMessage.class);
        verify(messageRepository).save(msgCaptor.capture());
        assertThat(msgCaptor.getValue().getSenderId()).isEqualTo(10L);
        assertThat(msgCaptor.getValue().getContent()).isEqualTo("柯南的回答");
        // 有成员成功发言：不广播 ALL_AGENTS_FAILED
        verify(groupBroadcastService, never()).broadcast(anyLong(), eq(WsConstants.ERROR), any());
    }

    @Test
    @DisplayName("全部候选都发言失败才广播 ALL_AGENTS_FAILED")
    void shouldBroadcastErrorWhenAllSilent() {
        mockCommon();
        when(agentSpeakerService.call(any(Agent.class), anyString(), anyList(),
                eq(AgentSpeakerService.ToolSet.CHAT), any(Map.class)))
                .thenReturn(AgentSpeakerService.AgentResult.of(""));

        node.apply(state("有人回答吗", List.of()));

        verify(groupBroadcastService).broadcast(anyLong(), eq(WsConstants.ERROR),
                argThat(payload -> payload instanceof Map map && Boolean.FALSE.equals(map.get("success"))));
    }

    @Test
    @DisplayName("无提及时按评分选最高分者发言")
    void shouldPickTopScoredWhenNoMention() {
        mockCommon();

        Map<String, Object> result = node.apply(state("闲聊一句", List.of()));

        // 无提及：首位评分最高者正常发言，消息入库
        ArgumentCaptor<Agent> agentCaptor = ArgumentCaptor.forClass(Agent.class);
        verify(agentSpeakerService, times(1)).call(agentCaptor.capture(), anyString(), anyList(),
                eq(AgentSpeakerService.ToolSet.CHAT), any(Map.class));
        verify(messageRepository).save(any(GroupMessage.class));
        assertThat(result).containsEntry(StateKeys.CHAT_BUFFER, 1);
    }
}
