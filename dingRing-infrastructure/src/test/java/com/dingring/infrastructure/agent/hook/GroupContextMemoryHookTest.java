package com.dingring.infrastructure.agent.hook;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.state.ReplaceAllWith;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.agent.AgentRepository;
import com.dingring.domain.service.GroupContextMemoryService;
import com.dingring.domain.service.GroupContextMemoryService.AgentPromptContext;
import com.dingring.domain.service.LlmService.ChatTurn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link GroupContextMemoryHook} 意图分发单测。
 * <p>验证：CHAT 追加 SystemMessage；DISCUSS/CONCLUDE 整表替换（SystemMessage 置首 + 轮次）；
 * WORK/intent 缺失/groupId/speakerAgentId/topicId 缺失均安全跳过。
 */
@DisplayName("GroupContextMemoryHook 群上下文记忆注入")
class GroupContextMemoryHookTest {

    private GroupContextMemoryService contextMemoryService;
    private AgentRepository agentRepository;
    private GroupContextMemoryHook hook;

    private final Agent speaker = new Agent();

    @BeforeEach
    void setUp() {
        contextMemoryService = mock(GroupContextMemoryService.class);
        agentRepository = mock(AgentRepository.class);
        hook = new GroupContextMemoryHook(contextMemoryService, agentRepository);
        speaker.setId(10L);
        speaker.setName("老王");
        when(agentRepository.findById(10L)).thenReturn(Optional.of(speaker));
    }

    private OverAllState state(Map<String, Object> data) {
        return new OverAllState(data);
    }

    @Test
    @DisplayName("CHAT：无轮次，追加 SystemMessage（不动节点兜底轮）")
    void chatShouldAppendSystemMessage() {
        when(contextMemoryService.buildChatContext(speaker, 1L))
                .thenReturn(new AgentPromptContext("人设+闲聊记忆", List.of()));

        Map<String, Object> result = hook.beforeAgent(state(Map.of(
                "groupId", 1L, "speakerAgentId", 10L, "intent", "CHAT")), null).join();

        assertThat(result).containsKey("messages");
        assertThat(result.get("messages")).isInstanceOf(SystemMessage.class);
        assertThat(((SystemMessage) result.get("messages")).getText()).isEqualTo("人设+闲聊记忆");
    }

    @Test
    @DisplayName("DISCUSS：整表替换（SystemMessage 置首 + 轮次，角色正确）")
    void discussShouldReplaceAllWithTurns() {
        when(contextMemoryService.buildDiscussContext(eq(speaker), eq(1L), eq(100L), eq("提示")))
                .thenReturn(new AgentPromptContext("人设+讨论上下文", List.of(
                        ChatTurn.user("用户: 提问"),
                        ChatTurn.assistant("我之前的回答"))));

        Map<String, Object> result = hook.beforeAgent(state(Map.of(
                "groupId", 1L, "speakerAgentId", 10L, "intent", "DISCUSS",
                "topicId", 100L, "userHistoryHint", "提示")), null).join();

        List<Message> messages = replaceAllValues(result);
        assertThat(messages).hasSize(3);
        assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(((SystemMessage) messages.get(0)).getText()).isEqualTo("人设+讨论上下文");
        assertThat(messages.get(1)).isInstanceOf(UserMessage.class);
        assertThat(messages.get(2)).isInstanceOf(AssistantMessage.class);
    }

    @Test
    @DisplayName("CONCLUDE：读取 topicTitle 组装，整表替换")
    void concludeShouldPassTopicTitle() {
        when(contextMemoryService.buildConclusionContext(speaker, 100L, "缓存方案"))
                .thenReturn(new AgentPromptContext("收束提示词", List.of(ChatTurn.user("缓存方案"))));

        Map<String, Object> result = hook.beforeAgent(state(Map.of(
                "groupId", 1L, "speakerAgentId", 10L, "intent", "CONCLUDE",
                "topicId", 100L, "topicTitle", "缓存方案")), null).join();

        List<Message> messages = replaceAllValues(result);
        assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(messages).hasSize(2);
        verify(contextMemoryService).buildConclusionContext(speaker, 100L, "缓存方案");
    }

    @Test
    @DisplayName("WORK / 未知意图：不注入")
    void workIntentShouldSkip() {
        Map<String, Object> result = hook.beforeAgent(state(Map.of(
                "groupId", 1L, "speakerAgentId", 10L, "intent", "WORK")), null).join();

        assertThat(result).isEmpty();
        verifyNoInteractions(contextMemoryService);
    }

    @Test
    @DisplayName("intent 缺失（Supervisor Worker clearContext 场景之一）：不注入")
    void missingIntentShouldSkip() {
        Map<String, Object> result = hook.beforeAgent(state(Map.of(
                "groupId", 1L, "speakerAgentId", 10L)), null).join();

        assertThat(result).isEmpty();
        verifyNoInteractions(contextMemoryService);
    }

    @Test
    @DisplayName("groupId 缺失：不注入")
    void missingGroupIdShouldSkip() {
        Map<String, Object> result = hook.beforeAgent(state(Map.of(
                "speakerAgentId", 10L, "intent", "CHAT")), null).join();

        assertThat(result).isEmpty();
        verifyNoInteractions(contextMemoryService);
    }

    @Test
    @DisplayName("speakerAgentId 缺失：不注入")
    void missingSpeakerAgentIdShouldSkip() {
        Map<String, Object> result = hook.beforeAgent(state(Map.of(
                "groupId", 1L, "intent", "CHAT")), null).join();

        assertThat(result).isEmpty();
        verifyNoInteractions(contextMemoryService);
    }

    @Test
    @DisplayName("DISCUSS 缺 topicId：跳过且不调 Service")
    void discussWithoutTopicIdShouldSkip() {
        Map<String, Object> result = hook.beforeAgent(state(Map.of(
                "groupId", 1L, "speakerAgentId", 10L, "intent", "DISCUSS")), null).join();

        assertThat(result).isEmpty();
        verifyNoInteractions(contextMemoryService);
    }

    @Test
    @DisplayName("发言 Agent 不存在：跳过")
    void unknownSpeakerShouldSkip() {
        when(agentRepository.findById(any())).thenReturn(Optional.empty());

        Map<String, Object> result = hook.beforeAgent(state(Map.of(
                "groupId", 1L, "speakerAgentId", 999L, "intent", "CHAT")), null).join();

        assertThat(result).isEmpty();
        verifyNoInteractions(contextMemoryService);
    }

    @Test
    @DisplayName("Service 返回空 systemPrompt：不注入")
    void blankPromptShouldSkip() {
        when(contextMemoryService.buildChatContext(speaker, 1L))
                .thenReturn(new AgentPromptContext("", List.of()));

        Map<String, Object> result = hook.beforeAgent(state(Map.of(
                "groupId", 1L, "speakerAgentId", 10L, "intent", "CHAT")), null).join();

        assertThat(result).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private List<Message> replaceAllValues(Map<String, Object> result) {
        assertThat(result).containsKey("messages");
        Object value = result.get("messages");
        assertThat(value).isInstanceOf(ReplaceAllWith.class);
        return ((ReplaceAllWith<Message>) value).newValues();
    }
}
