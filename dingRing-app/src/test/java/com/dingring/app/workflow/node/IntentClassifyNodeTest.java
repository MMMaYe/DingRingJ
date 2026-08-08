package com.dingring.app.workflow.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.dingring.app.orchestrator.MessageRouter;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.agent.AgentRepository;
import com.dingring.domain.group.Group;
import com.dingring.domain.group.GroupMember;
import com.dingring.domain.group.GroupRepository;
import com.dingring.domain.group.MemberRole;
import com.dingring.domain.group.MemberType;
import com.dingring.domain.workflow.StateKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link IntentClassifyNode} 意图分类节点单测（Phase G WORK 闭环）。
 * <p>验证：
 * <ul>
 *   <li>MessageRouter 判定 WORK → 意图透传写入 state（供 StateGraph 条件边路由到 work 节点）</li>
 *   <li>预设 intent 跳过 LLM 直接透传（REST 强制收束 / DIVERGE 自动推进等场景）</li>
 * </ul>
 */
@DisplayName("IntentClassifyNode 意图分类节点")
class IntentClassifyNodeTest {

    private MessageRouter messageRouter;
    private AgentRepository agentRepository;
    private GroupRepository groupRepository;
    private IntentClassifyNode node;

    @BeforeEach
    void setUp() {
        messageRouter = mock(MessageRouter.class);
        agentRepository = mock(AgentRepository.class);
        groupRepository = mock(GroupRepository.class);
        node = new IntentClassifyNode(messageRouter, agentRepository, groupRepository);
    }

    private Agent agent(Long id, String name) {
        Agent a = new Agent();
        a.setId(id);
        a.setName(name);
        return a;
    }

    private OverAllState stateWith(Long groupId, String input) {
        return new OverAllState(Map.of("groupId", groupId, "input", input));
    }

    @Test
    @DisplayName("WORK 意图透传写入 state（Phase G 闭环入口）")
    void workIntentShouldPassThrough() {
        when(agentRepository.findRouteJudge()).thenReturn(Optional.of(agent(10L, "老王")));
        when(messageRouter.route(any(Agent.class), anyString(), any()))
                .thenReturn(new MessageRouter.Route(MessageRouter.Intent.WORK, "", MessageRouter.Confidence.HIGH));

        Map<String, Object> result = node.apply(stateWith(1L, "帮我写一份商城系统的技术方案文档"));

        assertThat(result).containsEntry(StateKeys.INTENT, "WORK");
        assertThat(result).containsEntry(StateKeys.CONFIDENCE, "HIGH");
    }

    @Test
    @DisplayName("预设 intent 跳过 LLM 直接透传")
    void presetIntentShouldSkipLlm() {
        OverAllState state = new OverAllState(Map.of("groupId", 1L, "input", "强制执行任务",
                StateKeys.INTENT, "WORK"));

        Map<String, Object> result = node.apply(state);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("未配置路由判定器时降级为群首成员并路由")
    void missingJudgeShouldFallbackToFirstMember() {
        when(agentRepository.findRouteJudge()).thenReturn(Optional.empty());
        Group g = new Group();
        g.setId(1L);
        g.setGroupMember(List.of(new GroupMember(20L, MemberType.AGENT, MemberRole.MEMBER)));
        when(groupRepository.findById(1L)).thenReturn(Optional.of(g));
        when(agentRepository.findByIds(List.of(20L))).thenReturn(List.of(agent(20L, "柯南")));
        when(messageRouter.route(any(Agent.class), anyString(), any()))
                .thenReturn(new MessageRouter.Route(MessageRouter.Intent.DISCUSS, "缓存方案选型",
                        MessageRouter.Confidence.HIGH));

        Map<String, Object> result = node.apply(stateWith(1L, "该用 Redis 还是本地缓存？"));

        assertThat(result).containsEntry(StateKeys.INTENT, "DISCUSS");
        assertThat(result).containsEntry(StateKeys.TOPIC_TITLE, "缓存方案选型");
    }
}
