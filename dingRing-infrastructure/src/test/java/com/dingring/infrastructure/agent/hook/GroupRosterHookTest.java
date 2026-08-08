package com.dingring.infrastructure.agent.hook;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.agent.AgentRepository;
import com.dingring.domain.group.Group;
import com.dingring.domain.group.GroupRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link GroupRosterHook} 群成员名单注入单测。
 * <p>承接自 Phase C ContextBuilder 的群成员名单拼接逻辑（Phase D 迁移到 Hook）。
 * 验证：本人标「你」+ 人设首句、他人用 description、空群不注入。
 * <p>策略：用真实 {@link OverAllState}（Map 构造），mock {@link GroupRepository}
 * 返回 {@link Group}（再 mock 其 memberAgentIds），mock {@link AgentRepository} 返回成员。
 */
@DisplayName("GroupRosterHook 群成员名单注入")
class GroupRosterHookTest {

    private GroupRepository groupRepository;
    private AgentRepository agentRepository;
    private GroupRosterHook hook;

    @BeforeEach
    void setUp() {
        groupRepository = mock(GroupRepository.class);
        agentRepository = mock(AgentRepository.class);
        hook = new GroupRosterHook(groupRepository, agentRepository);
    }

    private Agent agent(Long id, String name, String systemPrompt) {
        Agent a = new Agent();
        a.setId(id);
        a.setName(name);
        a.setSystemPrompt(systemPrompt);
        return a;
    }

    @Test
    @DisplayName("注入群成员名单：本人标「你」+ 人设首句，他人用 description")
    void shouldInjectRosterWithSelfMarkedAndOtherIntro() {
        Agent self = agent(10L, "老王", "你是后端架构师。擅长分布式系统");
        Agent other = agent(11L, "小李", null);
        other.setDescription("产品经理，关注用户价值");
        Group group = mock(Group.class);
        when(group.memberAgentIds()).thenReturn(List.of(10L, 11L));
        when(groupRepository.findById(1L)).thenReturn(Optional.of(group));
        when(agentRepository.findByIds(List.of(10L, 11L))).thenReturn(List.of(self, other));

        OverAllState state = new OverAllState(Map.of("groupId", 1L, "speakerAgentId", 10L));
        Map<String, Object> result = hook.beforeModel(state, null).join();

        assertThat(result).containsKey("messages");
        SystemMessage msg = (SystemMessage) result.get("messages");
        assertThat(msg.getText())
                .contains("群成员名单")                  // 名单段标题
                .contains("你（老王）")                  // 本人标「你」
                .contains("你是后端架构师")             // 人设首句（split on 句号）
                .contains("小李")                       // 他人花名
                .contains("产品经理，关注用户价值");    // 他人 description
    }

    @Test
    @DisplayName("群不存在时返回空 Map（不注入）")
    void shouldReturnEmptyWhenGroupNotFound() {
        when(groupRepository.findById(1L)).thenReturn(Optional.empty());
        OverAllState state = new OverAllState(Map.of("groupId", 1L));

        Map<String, Object> result = hook.beforeModel(state, null).join();

        assertThat(result).doesNotContainKey("messages");
    }

    @Test
    @DisplayName("群内无 Agent 成员时返回空 Map")
    void shouldReturnEmptyWhenNoMembers() {
        Group group = mock(Group.class);
        when(group.memberAgentIds()).thenReturn(List.of());
        when(groupRepository.findById(1L)).thenReturn(Optional.of(group));
        OverAllState state = new OverAllState(Map.of("groupId", 1L));

        Map<String, Object> result = hook.beforeModel(state, null).join();

        assertThat(result).doesNotContainKey("messages");
    }

    @Test
    @DisplayName("groupId 为 null 时返回空 Map")
    void shouldReturnEmptyWhenGroupIdNull() {
        OverAllState state = new OverAllState(Map.of());

        Map<String, Object> result = hook.beforeModel(state, null).join();

        assertThat(result).doesNotContainKey("messages");
    }
}
