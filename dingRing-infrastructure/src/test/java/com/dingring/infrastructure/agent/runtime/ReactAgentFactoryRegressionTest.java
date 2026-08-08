package com.dingring.infrastructure.agent.runtime;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.agent.AgentRepository;
import com.dingring.domain.discussion.TopicRepository;
import com.dingring.domain.group.Group;
import com.dingring.domain.group.GroupRepository;
import com.dingring.domain.service.AgentSpeakerService.ToolSet;
import com.dingring.domain.service.MemoryService;
import com.dingring.domain.service.ProfileService;
import com.dingring.domain.service.RagService;
import com.dingring.infrastructure.agent.hook.GroupRosterHook;
import com.dingring.infrastructure.agent.hook.MemoryInjectionHook;
import com.dingring.infrastructure.agent.hook.ProfileInjectionHook;
import com.dingring.infrastructure.agent.hook.RagInjectionHook;
import com.dingring.infrastructure.agent.tool.KnowledgeSearchTool;
import com.dingring.infrastructure.agent.tool.TopicHistoryTool;
import com.dingring.infrastructure.agent.tool.UserProfileQueryTool;
import com.dingring.infrastructure.llm.SaaModelFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SaaReactAgentFactory 回归测试。
 * <p>回归背景（Phase G）：recursionLimit=3 时图在 memory/profile hook 后即终止，
 * _AGENT_MODEL_ 节点从未执行，抛出 "No AssistantMessage found in 'messages' state"。
 * <p>本测试走真实工厂路径（真实 hook + 真实工具 + mock ChatModel），
 * 断言四个场景（CHAT/DISCUSS/CONCLUDE/WORK）构建出的 ReactAgent 均能真正调用模型并返回内容。
 */
class ReactAgentFactoryRegressionTest {

    private final OpenAiChatModel chatModel = mock(OpenAiChatModel.class);
    private final SaaModelFactory modelFactory = mock(SaaModelFactory.class);
    private final Agent domainAgent = mock(Agent.class);

    private final AgentRepository agentRepository = mock(AgentRepository.class);
    private final GroupRepository groupRepository = mock(GroupRepository.class);
    private final MemoryService memoryService = mock(MemoryService.class);
    private final ProfileService profileService = mock(ProfileService.class);
    private final RagService ragService = mock(RagService.class);
    private final TopicRepository topicRepository = mock(TopicRepository.class);

    /** 真实工具实例（依赖用 mock 服务），走 ToolCallbacks.from 转 ToolCallback */
    private SaaReactAgentFactory newFactory() {
        return new SaaReactAgentFactory(
                modelFactory,
                new MemoryInjectionHook(memoryService),
                new ProfileInjectionHook(profileService),
                new GroupRosterHook(groupRepository, agentRepository),
                new RagInjectionHook(ragService),
                new UserProfileQueryTool(profileService),
                new TopicHistoryTool(topicRepository),
                new KnowledgeSearchTool(ragService));
    }

    private void stubBaseMocks() {
        when(modelFactory.buildChatModel(any(), any())).thenReturn(chatModel);
        when(domainAgent.getId()).thenReturn(1L);
        when(domainAgent.getName()).thenReturn("老王");
        when(domainAgent.getDescription()).thenReturn("程序员老王，专注高并发与缓存");

        when(chatModel.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage("这是老王的回复")))));

        when(memoryService.retrieveMemory(10L)).thenReturn("历史结论：Redis 用 ZSET 缓存热点数据");
        when(profileService.getProfile(any())).thenReturn("程序员老王，专注高并发与缓存");
        when(ragService.retrieve(any(), any())).thenReturn("");
        when(agentRepository.findByIds(any())).thenReturn(List.of(domainAgent));
        Group group = mock(Group.class);
        when(group.memberAgentIds()).thenReturn(List.of(1L));
        when(groupRepository.findById(10L)).thenReturn(Optional.of(group));
        when(topicRepository.findClosedByGroupId(10L)).thenReturn(List.of());
    }

    private AssistantMessage callAgent(ReactAgent agent) throws Exception {
        return agent.call(Map.of(
                "messages", List.of(new UserMessage("帮我整理一份 Java 面试高频题 TOP10 的清单")),
                "groupId", 10L,
                "ragQuery", "帮我整理一份 Java 面试高频题 TOP10 的清单",
                "speakerAgentId", 1L,
                "userId", 1L
        ));
    }

    @Test
    @DisplayName("回归：CHAT 场景经工厂构建后可真正调用模型")
    void chatScenarioInvokesModel() throws Exception {
        stubBaseMocks();
        ReactAgent agent = newFactory().buildDiscussAgent(domainAgent, "你是群里的技术专家老王。", ToolSet.CHAT);
        AssistantMessage result = callAgent(agent);
        assertEquals("这是老王的回复", result.getText());
        verify(chatModel, atLeastOnce()).call(any(Prompt.class));
    }

    @Test
    @DisplayName("回归：DISCUSS 场景经工厂构建后可真正调用模型")
    void discussScenarioInvokesModel() throws Exception {
        stubBaseMocks();
        ReactAgent agent = newFactory().buildDiscussAgent(domainAgent, "你是群里的技术专家老王。", ToolSet.DISCUSS);
        AssistantMessage result = callAgent(agent);
        assertEquals("这是老王的回复", result.getText());
        verify(chatModel, atLeastOnce()).call(any(Prompt.class));
    }

    @Test
    @DisplayName("回归：CONCLUDE 场景经工厂构建后可真正调用模型")
    void concludeScenarioInvokesModel() throws Exception {
        stubBaseMocks();
        ReactAgent agent = newFactory().buildDiscussAgent(domainAgent, "你是群里的技术专家老王。", ToolSet.CONCLUDE);
        AssistantMessage result = callAgent(agent);
        assertEquals("这是老王的回复", result.getText());
        verify(chatModel, atLeastOnce()).call(any(Prompt.class));
    }

    @Test
    @DisplayName("回归：WORK 场景经工厂构建后可真正调用模型")
    void workScenarioInvokesModel() throws Exception {
        stubBaseMocks();
        ReactAgent agent = newFactory().buildWorkAgent(domainAgent, "你是群里的技术专家老王。");
        AssistantMessage result = callAgent(agent);
        assertEquals("这是老王的回复", result.getText());
        verify(chatModel, atLeastOnce()).call(any(Prompt.class));
    }
}
