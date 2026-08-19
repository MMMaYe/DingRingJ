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
import com.dingring.domain.service.LlmService;
import com.dingring.domain.service.GroupBroadcastService;
import com.dingring.infrastructure.agent.runtime.SupervisorAgentFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.test.util.ReflectionTestUtils;

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
    private LlmService llmService;
    private GroupBroadcastService groupBroadcastService;
    private SupervisorAgentFactory supervisorAgentFactory;
    private WorkNode node;

    @BeforeEach
    void setUp() {
        groupRepository = mock(GroupRepository.class);
        agentRepository = mock(AgentRepository.class);
        messageRepository = mock(MessageRepository.class);
        messageAssembler = mock(MessageAssembler.class);
        llmService = mock(LlmService.class);
        groupBroadcastService = mock(GroupBroadcastService.class);
        supervisorAgentFactory = mock(SupervisorAgentFactory.class);
        node = new WorkNode(groupRepository, agentRepository, messageRepository, messageAssembler,
                llmService, groupBroadcastService, supervisorAgentFactory);
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

    private OverAllState stateWithMentioned(Long groupId, String input, List<Long> mentionedAgentIds) {
        Map<String, Object> values = new HashMap<>();
        values.put("groupId", groupId);
        values.put("input", input);
        values.put("mentionedAgentIds", mentionedAgentIds);
        return new OverAllState(values);
    }

    private OverAllState stateWithReplied(Long groupId, String input, Long repliedToAgentId) {
        Map<String, Object> values = new HashMap<>();
        values.put("groupId", groupId);
        values.put("input", input);
        values.put("repliedToAgentId", repliedToAgentId);
        return new OverAllState(values);
    }

    @Test
    @DisplayName("Supervisor 开关关闭时走单 Agent 模式（Phase D 原行为）")
    void shouldUseSingleAgentWhenSupervisorDisabled() {
        Long groupId = 1L;
        when(groupRepository.findById(groupId)).thenReturn(Optional.of(groupWithAgentIds(groupId, List.of(10L, 20L))));
        when(agentRepository.findByIds(List.of(10L, 20L))).thenReturn(List.of(agent(10L, "柯南"), agent(20L, "灰原")));
        when(llmService.chat(any(Agent.class), anyString(), anyList(),
                any(LlmService.ToolSet.class), any(Map.class)))
                .thenReturn(LlmService.AgentResult.of("单 Agent 结果"));

        Map<String, Object> result = node.apply(stateWith(groupId, "帮我查一下资料"));

        assertThat(result).containsEntry("workResult", "单 Agent 结果");
        verify(supervisorAgentFactory, never()).buildSupervisor(any(), anyString(), anyList());

        ArgumentCaptor<Map> payload = ArgumentCaptor.forClass(Map.class);
        verify(groupBroadcastService).broadcast(anyLong(),
                org.mockito.ArgumentMatchers.eq(WsConstants.WORK_TASK_STARTED), payload.capture());
        assertThat(payload.getValue()).containsEntry("supervisorMode", false);
    }

    @Test
    @DisplayName("Supervisor 开启但成员不足 2 人时自动降级单 Agent")
    void shouldFallbackWhenNotEnoughMembers() {
        setSupervisorEnabled(true);
        Long groupId = 1L;
        when(groupRepository.findById(groupId)).thenReturn(Optional.of(groupWithAgentIds(groupId, List.of(10L))));
        when(agentRepository.findByIds(List.of(10L))).thenReturn(List.of(agent(10L, "柯南")));
        when(llmService.chat(any(Agent.class), anyString(), anyList(),
                any(LlmService.ToolSet.class), any(Map.class)))
                .thenReturn(LlmService.AgentResult.of("单 Agent 结果"));

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
        verify(llmService, never()).chat(any(), anyString(), anyList(), any(), any());
        verify(messageRepository).save(any(com.dingring.domain.group.GroupMessage.class));
        verify(groupBroadcastService).broadcast(anyLong(), org.mockito.ArgumentMatchers.eq(WsConstants.NEW_MESSAGE), any());

        ArgumentCaptor<Map> payload = ArgumentCaptor.forClass(Map.class);
        verify(groupBroadcastService).broadcast(anyLong(),
                org.mockito.ArgumentMatchers.eq(WsConstants.WORK_TASK_STARTED), payload.capture());
        assertThat(payload.getValue()).containsEntry("supervisorMode", true);
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
        when(llmService.chat(any(Agent.class), anyString(), anyList(),
                any(LlmService.ToolSet.class), any(Map.class)))
                .thenReturn(LlmService.AgentResult.of("降级结果"));

        Map<String, Object> result = node.apply(stateWith(groupId, "生成一份调研报告"));

        assertThat(result).containsEntry("workResult", "降级结果");
        verify(llmService).chat(any(Agent.class), anyString(), anyList(),
                org.mockito.ArgumentMatchers.eq(LlmService.ToolSet.WORK), any(Map.class));
    }

    @Test
    @DisplayName("单 Agent 模式：@ 提及优先于群首（用户点名的人来执行）")
    void shouldPickMentionedAgentAsWorkAgent() {
        Long groupId = 1L;
        when(groupRepository.findById(groupId)).thenReturn(Optional.of(groupWithAgentIds(groupId, List.of(10L, 20L))));
        when(agentRepository.findByIds(List.of(10L, 20L))).thenReturn(List.of(agent(10L, "柯南"), agent(20L, "灰原")));
        when(llmService.chat(any(Agent.class), anyString(), anyList(),
                any(LlmService.ToolSet.class), any(Map.class)))
                .thenReturn(LlmService.AgentResult.of("被 @ 者结果"));

        Map<String, Object> result = node.apply(stateWithMentioned(groupId, "@灰原 帮我画个图", List.of(20L)));

        assertThat(result).containsEntry("workResult", "被 @ 者结果");
        ArgumentCaptor<Agent> agentCaptor = ArgumentCaptor.forClass(Agent.class);
        verify(llmService).chat(agentCaptor.capture(), anyString(), anyList(),
                org.mockito.ArgumentMatchers.eq(LlmService.ToolSet.WORK), any(Map.class));
        assertThat(agentCaptor.getValue().getId()).isEqualTo(20L);
        assertThat(agentCaptor.getValue().getName()).isEqualTo("灰原");
    }

    @Test
    @DisplayName("单 Agent 模式：@ 提及对象不在群内时回退群首成员")
    void shouldFallbackToFirstMemberWhenMentionedNotInGroup() {
        Long groupId = 1L;
        when(groupRepository.findById(groupId)).thenReturn(Optional.of(groupWithAgentIds(groupId, List.of(10L, 20L))));
        when(agentRepository.findByIds(List.of(10L, 20L))).thenReturn(List.of(agent(10L, "柯南"), agent(20L, "灰原")));
        when(llmService.chat(any(Agent.class), anyString(), anyList(),
                any(LlmService.ToolSet.class), any(Map.class)))
                .thenReturn(LlmService.AgentResult.of("群首结果"));

        Map<String, Object> result = node.apply(stateWithMentioned(groupId, "@路人 帮我查资料", List.of(99L)));

        assertThat(result).containsEntry("workResult", "群首结果");
        ArgumentCaptor<Agent> agentCaptor = ArgumentCaptor.forClass(Agent.class);
        verify(llmService).chat(agentCaptor.capture(), anyString(), anyList(),
                org.mockito.ArgumentMatchers.eq(LlmService.ToolSet.WORK), any(Map.class));
        assertThat(agentCaptor.getValue().getId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("Supervisor 模式：@ 点名的成员作为编排者并注入点名提示")
    void shouldPickMentionedAsSupervisorAndInjectNames() throws GraphRunnerException {
        setSupervisorEnabled(true);
        Long groupId = 1L;
        when(groupRepository.findById(groupId)).thenReturn(Optional.of(groupWithAgentIds(groupId, List.of(10L, 20L))));
        when(agentRepository.findByIds(List.of(10L, 20L))).thenReturn(List.of(agent(10L, "柯南"), agent(20L, "灰原")));

        ReactAgent supervisor = mock(ReactAgent.class);
        when(supervisorAgentFactory.buildSupervisor(any(Agent.class), anyString(), anyList()))
                .thenReturn(supervisor);
        when(supervisor.call(any(Map.class))).thenReturn(new AssistantMessage("汇总结果"));

        Map<String, Object> result = node.apply(stateWithMentioned(groupId, "@灰原 生成一份调研报告", List.of(20L)));

        assertThat(result).containsEntry("workResult", "汇总结果");
        ArgumentCaptor<Agent> agentCaptor = ArgumentCaptor.forClass(Agent.class);
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(supervisorAgentFactory).buildSupervisor(agentCaptor.capture(), promptCaptor.capture(), anyList());
        assertThat(agentCaptor.getValue().getId()).isEqualTo(20L);
        assertThat(promptCaptor.getValue()).contains("点名了「灰原」");
    }

    @Test
    @DisplayName("单 Agent 模式：纯引用回复（无@）时被引用的 Agent 优先执行")
    void shouldPickRepliedAgentAsWorkAgent() {
        Long groupId = 1L;
        when(groupRepository.findById(groupId)).thenReturn(Optional.of(groupWithAgentIds(groupId, List.of(10L, 20L))));
        when(agentRepository.findByIds(List.of(10L, 20L))).thenReturn(List.of(agent(10L, "柯南"), agent(20L, "灰原")));
        when(llmService.chat(any(Agent.class), anyString(), anyList(),
                any(LlmService.ToolSet.class), any(Map.class)))
                .thenReturn(LlmService.AgentResult.of("被引用者结果"));

        // 场景还原：用户引用灰原的消息追问（无@文本），期望灰原而非群首柯南执行
        Map<String, Object> result = node.apply(stateWithReplied(groupId, "搜下什么是MACP", 20L));

        assertThat(result).containsEntry("workResult", "被引用者结果");
        ArgumentCaptor<Agent> agentCaptor = ArgumentCaptor.forClass(Agent.class);
        verify(llmService).chat(agentCaptor.capture(), anyString(), anyList(),
                org.mockito.ArgumentMatchers.eq(LlmService.ToolSet.WORK), any(Map.class));
        assertThat(agentCaptor.getValue().getId()).isEqualTo(20L);
        assertThat(agentCaptor.getValue().getName()).isEqualTo("灰原");
    }

    @Test
    @DisplayName("单 Agent 模式：@ 提及优先于引用目标（显式点名最高）")
    void shouldPreferMentionedOverRepliedAgent() {
        Long groupId = 1L;
        when(groupRepository.findById(groupId)).thenReturn(Optional.of(groupWithAgentIds(groupId, List.of(10L, 20L))));
        when(agentRepository.findByIds(List.of(10L, 20L))).thenReturn(List.of(agent(10L, "柯南"), agent(20L, "灰原")));
        when(llmService.chat(any(Agent.class), anyString(), anyList(),
                any(LlmService.ToolSet.class), any(Map.class)))
                .thenReturn(LlmService.AgentResult.of("被@者结果"));

        // 同时有@柯南与引用灰原：显式点名柯南应胜出
        Map<String, Object> values = new HashMap<>();
        values.put("groupId", groupId);
        values.put("input", "@柯南 搜下什么是MACP");
        values.put("mentionedAgentIds", List.of(10L));
        values.put("repliedToAgentId", 20L);
        Map<String, Object> result = node.apply(new OverAllState(values));

        assertThat(result).containsEntry("workResult", "被@者结果");
        ArgumentCaptor<Agent> agentCaptor = ArgumentCaptor.forClass(Agent.class);
        verify(llmService).chat(agentCaptor.capture(), anyString(), anyList(),
                org.mockito.ArgumentMatchers.eq(LlmService.ToolSet.WORK), any(Map.class));
        assertThat(agentCaptor.getValue().getId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("单 Agent 模式：引用目标不在群内时回退群首成员")
    void shouldFallbackToFirstMemberWhenRepliedNotInGroup() {
        Long groupId = 1L;
        when(groupRepository.findById(groupId)).thenReturn(Optional.of(groupWithAgentIds(groupId, List.of(10L, 20L))));
        when(agentRepository.findByIds(List.of(10L, 20L))).thenReturn(List.of(agent(10L, "柯南"), agent(20L, "灰原")));
        when(llmService.chat(any(Agent.class), anyString(), anyList(),
                any(LlmService.ToolSet.class), any(Map.class)))
                .thenReturn(LlmService.AgentResult.of("群首结果"));

        // 引用的是用户消息（repliedToAgentId=null）或已退群 Agent（99 不在群内）都应回退群首
        Map<String, Object> result = node.apply(stateWithReplied(groupId, "搜下什么是MACP", 99L));

        assertThat(result).containsEntry("workResult", "群首结果");
        ArgumentCaptor<Agent> agentCaptor = ArgumentCaptor.forClass(Agent.class);
        verify(llmService).chat(agentCaptor.capture(), anyString(), anyList(),
                org.mockito.ArgumentMatchers.eq(LlmService.ToolSet.WORK), any(Map.class));
        assertThat(agentCaptor.getValue().getId()).isEqualTo(10L);
    }
}
