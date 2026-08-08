package com.dingring.infrastructure.agent.runtime;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.dingring.common.util.LogHelper;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.service.AgentSpeakerService.ToolSet;
import com.dingring.infrastructure.agent.hook.GroupRosterHook;
import com.dingring.infrastructure.agent.hook.MemoryInjectionHook;
import com.dingring.infrastructure.agent.hook.ProfileInjectionHook;
import com.dingring.infrastructure.agent.hook.RagInjectionHook;
import com.dingring.infrastructure.agent.tool.KnowledgeSearchTool;
import com.dingring.infrastructure.agent.tool.TopicHistoryTool;
import com.dingring.infrastructure.agent.tool.UserProfileQueryTool;
import com.dingring.infrastructure.llm.SaaModelFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/**
 * 按 Agent 领域实体配置构建 SAA ReactAgent（Phase D）。
 * <p>复用 {@link SaaModelFactory} 构建 ChatModel，避免重复 resolveUrl 逻辑。
 * <p>ReAct 循环上限用 {@link CompileConfig.Builder#recursionLimit(int)}（SAA 1.1.2.3 无 maxIters API）。
 * <p>注意：recursionLimit 按图节点执行次数计数。每个推理轮次 = __START__ + 4 个 beforeModel Hook
 * + _AGENT_MODEL_（+ 工具节点），单轮至少 7 步，因此必须 > 7，否则模型节点永远无法执行
 * （图提前终止，抛出 "No AssistantMessage found in 'messages' state"）。
 * <p>场景配置：
 * <ul>
 *   <li>讨论场景（CHAT/DISCUSS/CONCLUDE）：recursionLimit=10，允许 1 个完整推理轮次 + 工具调用，不深陷工具循环</li>
 *   <li>工作场景（WORK）：recursionLimit=15，深度 ReAct</li>
 * </ul>
 * <p>Hook 为单例 Bean，通过 OverAllState（per-call）读取 groupId 等参数，无共享可变状态。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SaaReactAgentFactory {

    /** 讨论场景 ReAct 上限：1 轮完整推理 + 工具调用后仍有余量（实测单轮 ≥7 步） */
    private static final int DISCUSS_RECURSION_LIMIT = 10;

    /** 工作场景 ReAct 上限：深度推理 + 多轮工具调用 */
    private static final int WORK_RECURSION_LIMIT = 15;

    private final SaaModelFactory modelFactory;
    private final MemoryInjectionHook memoryInjectionHook;
    private final ProfileInjectionHook profileInjectionHook;
    private final GroupRosterHook groupRosterHook;
    private final RagInjectionHook ragInjectionHook;
    private final UserProfileQueryTool userProfileQueryTool;
    private final TopicHistoryTool topicHistoryTool;
    private final KnowledgeSearchTool knowledgeSearchTool;

    /**
     * 构建讨论场景 ReactAgent（轻量工具）。
     *
     * @param domainAgent   领域 Agent 实体
     * @param systemPrompt  基础系统提示词（Agent 人设 + 协作协议）
     * @param toolSet       工具集
     * @return 配置好的 ReactAgent（每次新建，无状态）
     */
    public ReactAgent buildDiscussAgent(Agent domainAgent, String systemPrompt, ToolSet toolSet) {
        return build(domainAgent, systemPrompt, toolSet, DISCUSS_RECURSION_LIMIT);
    }

    /**
     * 构建工作场景 ReactAgent（通用工具集，深度 ReAct）。
     */
    public ReactAgent buildWorkAgent(Agent domainAgent, String systemPrompt) {
        return build(domainAgent, systemPrompt, ToolSet.WORK, WORK_RECURSION_LIMIT);
    }

    /**
     * 构建 ReactAgent 通用方法。
     */
    private ReactAgent build(Agent domainAgent, String systemPrompt, ToolSet toolSet, int recursionLimit) {
        ToolCallback[] tools = resolveTools(toolSet);

        ReactAgent agent = ReactAgent.builder()
                .name(domainAgent.getName())
                .description(domainAgent.getDescription() != null ? domainAgent.getDescription() : "")
                .model(modelFactory.buildChatModel(domainAgent, null))
                .systemPrompt(systemPrompt)
                .tools(tools)
                // Hook 单例共享安全：实现仅从 state 读 per-call 参数，不使用 agent 引用
                .hooks(memoryInjectionHook, profileInjectionHook, groupRosterHook, ragInjectionHook)
                .compileConfig(CompileConfig.builder()
                        .recursionLimit(recursionLimit)
                        .build())
                .build();

        LogHelper.printLog(SaaReactAgentFactory.class, "SaaReactAgentFactory.build", "REACT_AGENT_BUILD",
                "ReactAgent 构建完成", "agent={} toolSet={} recursionLimit={} tools={}",
                domainAgent.getName(), toolSet, recursionLimit, tools.length);
        return agent;
    }

    /**
     * 按 ToolSet 解析工具集。
     * <p>使用 Spring AI {@link ToolCallbacks#from(Object...)} 将 @Tool 注解方法转为 ToolCallback。
     */
    private ToolCallback[] resolveTools(ToolSet toolSet) {
        return switch (toolSet) {
            case CHAT -> ToolCallbacks.from(userProfileQueryTool);
            case DISCUSS -> ToolCallbacks.from(knowledgeSearchTool, userProfileQueryTool);
            case CONCLUDE -> ToolCallbacks.from(topicHistoryTool);
            case WORK -> ToolCallbacks.from(knowledgeSearchTool, topicHistoryTool, userProfileQueryTool);
        };
    }
}
