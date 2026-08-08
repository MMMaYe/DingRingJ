package com.dingring.app.workflow.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.dingring.app.service.MessageAssembler;
import com.dingring.common.constant.WsConstants;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.agent.AgentRepository;
import com.dingring.domain.group.Group;
import com.dingring.domain.group.GroupMember;
import com.dingring.domain.group.GroupRepository;
import com.dingring.domain.group.MemberRole;
import com.dingring.domain.group.MemberType;
import com.dingring.domain.group.MessageRepository;
import com.dingring.domain.service.AgentSpeakerService;
import com.dingring.domain.service.GroupBroadcastService;
import com.dingring.infrastructure.agent.runtime.SupervisorAgentFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link WorkNode} 工作流节点单测（Phase F Supervisor 增强）。
 * <p>验证：
 * <ul>
 *   <li>Supervisor 开关关闭 → 单 Agent 模式（Phase D 原行为）</li>
 *   <li>成员不足 2 人时自动降级单 Agent</li>
 *   <li>Supervisor 模式：委派执行后以编排者身份入库广播</li>
 *   <li>Supervisor 执行异常 → 回退单 Agent 模式（方案十一回退策略）</li>
 * </ul>
 */
@DisplayName("WorkNode 工作流节点")
class WorkNodeTest {

    private GroupRepository groupRepository;
    private AgentRepository agentRepository;
    private MessageRepository messageRepository;
    private MessageAssembler messageAssembler;
    private AgentSpeakerService agentSpeakerService;
    private GroupBroadcastService groupBroadcastService;
    private SupervisorAgentFactory supervisorAgentFactory;
    private WorkNode node;

    @BeforeEach
    void setUp() {
        groupRepository = mock(GroupRepository.class);
        agentRepository = mock(AgentRepository.class);
        messageRepository = mock(MessageRepository.class);
        messageAssembler = mock(MessageAssembler.class);
        agentSpeakerService = mock(AgentSpeakerService.class);
        groupBroadcastService = mock(GroupBroadcastService.class);
        supervisorAgentFactory = mock(SupervisorAgentFactory.class);
        node = new WorkNode(groupRepository, agentRepository, messageRepository, messageAssembler,
                agentSpeakerService, groupBroadcastService, supervisorAgentFactory);
    }

    private void setSupervisorEnabled(boolean enabled) {
        ReflectionTestUtils.setField(node, "supervisorEnabled", enabled);
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

    private OverAllState stateWith(Long groupId, String input) {
        return new OverAllState(Map.of("groupId", groupId, "input", input));
    }

    @Test
    @DisplayName("Supervisor 开关关闭时走单 Agent 模式（Phase D 原行为）")
    void shouldUseSingleAgentWhenSupervisorDisabled() {
        Long groupId = 1L;
        when(groupRepository.findById(groupId)).thenReturn(Optional.of(groupWithAgentIds(groupId, List.of(10L, 20L))));
        when(agentRepository.findByIds(List.of(10L, 20L))).thenReturn(List.of(agent(10L, "柯南"), agent(20L, "灰原")));
        when(agentSpeakerService.call(any(Agent.class), anyString(), anyList(),
                any(AgentSpeakerService.ToolSet.class), any(Map.class)))
                .thenReturn(AgentSpeakerService.AgentResult.of("单 Agent 结果"));

        Map<String, Object> result = node.apply(stateWith(groupId, "帮我查一下资料"));

        assertThat(result).containsEntry("workResult", "单 Agent 结果");
        verify(supervisorAgentFactory, never()).buildSupervisor(any(), anyString(), anyList());
    }

    @Test
    @DisplayName("Supervisor 开启但成员不足 2 人时自动降级单 Agent")
    void shouldFallbackWhenNotEnoughMembers() {
        setSupervisorEnabled(true);
        Long groupId = 1L;
        when(groupRepository.findById(groupId)).thenReturn(Optional.of(groupWithAgentIds(groupId, List.of(10L))));
        when(agentRepository.findByIds(List.of(10L))).thenReturn(List.of(agent(10L, "柯南")));
        when(agentSpeakerService.call(any(Agent.class), anyString(), anyList(),
                any(AgentSpeakerService.ToolSet.class), any(Map.class)))
                .thenReturn(AgentSpeakerService.AgentResult.of("单 Agent 结果"));

        Map<String, Object> result = node.apply(stateWith(groupId, "帮我查一下资料"));

        assertThat(result).containsEntry("workResult", "单 Agent 结果");
        verify(supervisorAgentFactory, never()).buildSupervisor(any(), anyString(), anyList());
    }

    @Test
    @DisplayName("Supervisor 模式：委派执行后以编排者身份入库广播")
    void shouldExecuteWithSupervisor() throws GraphRunnerException {
        setSupervisorEnabled(true);
        Long groupId = 1L;
        when(groupRepository.findById(groupId)).thenReturn(Optional.of(groupWithAgentIds(groupId, List.of(10L, 20L))));
        when(agentRepository.findByIds(List.of(10L, 20L))).thenReturn(List.of(agent(10L, "柯南"), agent(20L, "灰原")));

        ReactAgent supervisor = mock(ReactAgent.class);
        when(supervisorAgentFactory.buildSupervisor(any(Agent.class), anyString(), anyList()))
                .thenReturn(supervisor);
        when(supervisor.call(any(Map.class))).thenReturn(new AssistantMessage("Supervisor 汇总结果"));

        Map<String, Object> result = node.apply(stateWith(groupId, "生成一份调研报告"));

        assertThat(result).containsEntry("workResult", "Supervisor 汇总结果");
        verify(supervisorAgentFactory).buildSupervisor(any(Agent.class), anyString(), anyList());
        verify(agentSpeakerService, never()).call(any(), anyString(), anyList(), any(), any());
        verify(messageRepository).save(any(com.dingring.domain.group.GroupMessage.class));
        verify(groupBroadcastService).broadcast(anyLong(), org.mockito.ArgumentMatchers.eq(WsConstants.NEW_MESSAGE), any());
    }

    @Test
    @DisplayName("Supervisor 执行异常时回退单 Agent 模式")
    void shouldFallbackToSingleAgentOnSupervisorFailure() throws GraphRunnerException {
        setSupervisorEnabled(true);
        Long groupId = 1L;
        when(groupRepository.findById(groupId)).thenReturn(Optional.of(groupWithAgentIds(groupId, List.of(10L, 20L))));
        when(agentRepository.findByIds(List.of(10L, 20L))).thenReturn(List.of(agent(10L, "柯南"), agent(20L, "灰原")));

        ReactAgent supervisor = mock(ReactAgent.class);
        when(supervisorAgentFactory.buildSupervisor(any(Agent.class), anyString(), anyList()))
                .thenReturn(supervisor);
        when(supervisor.call(any(Map.class))).thenThrow(new RuntimeException("Supervisor LLM 故障"));
        when(agentSpeakerService.call(any(Agent.class), anyString(), anyList(),
                any(AgentSpeakerService.ToolSet.class), any(Map.class)))
                .thenReturn(AgentSpeakerService.AgentResult.of("降级结果"));

        Map<String, Object> result = node.apply(stateWith(groupId, "生成一份调研报告"));

        assertThat(result).containsEntry("workResult", "降级结果");
        verify(agentSpeakerService).call(any(Agent.class), anyString(), anyList(),
                org.mockito.ArgumentMatchers.eq(AgentSpeakerService.ToolSet.WORK), any(Map.class));
    }
}
