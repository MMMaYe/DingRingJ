package com.dingring.infrastructure.agent.runtime;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.dingring.common.util.LogHelper;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.service.LlmService.ToolSet;
import com.dingring.infrastructure.agent.hook.GroupRosterHook;
import com.dingring.infrastructure.agent.hook.MemoryInjectionHook;
import com.dingring.infrastructure.agent.hook.ProfileInjectionHook;
import com.dingring.infrastructure.agent.hook.RagInjectionHook;
import com.dingring.infrastructure.agent.hook.SystemMessageMergeHook;
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
 * <p>注意：recursionLimit 按图节点执行次数计数。每个推理轮次 = __START__ + 5 个 beforeModel Hook
 * + _AGENT_MODEL_（+ 工具节点），单轮至少 7 步，因此必须 > 7，否则模型节点永远无法执行
 * （图提前终止，抛出 "No AssistantMessage found in 'messages' state"）。
 * <p>必须留足"工具调用后第二轮回读结果"的余量：一次工具往返（第一轮调工具 + 第二轮回读最终文本）
 * 实测需要 recursionLimit ≥ 20，取 19 时第二轮在 hook 中途被掐断，只剩工具调用消息
 * （文本为空，实测日志 "内容长度=0 hasToolCalls=true"）。
 * <p>场景配置：
 * <ul>
 *   <li>讨论场景（CHAT/DISCUSS/CONCLUDE）：recursionLimit=40，支撑 1 次工具往返（20 步）+ 20 步缓冲，不深陷工具循环</li>
 *   <li>工作场景（WORK）：recursionLimit=40，深度 ReAct，支撑约 3 轮模型推理</li>
 * </ul>
 * <p>Hook 为单例 Bean，通过 OverAllState（per-call）读取 groupId 等参数，无共享可变状态。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SaaReactAgentFactory {

    /** 讨论场景 ReAct 上限：一次工具往返（实测需 limit≥20，19 时第二轮被掐断）+ 20 步缓冲 */
    private static final int DISCUSS_RECURSION_LIMIT = 40;

    /** 工作场景 ReAct 上限：深度 ReAct，支撑约 3 轮模型推理（每轮约 10 步） */
    private static final int WORK_RECURSION_LIMIT = 40;

    private final SaaModelFactory modelFactory;
    private final MemoryInjectionHook memoryInjectionHook;
    private final ProfileInjectionHook profileInjectionHook;
    private final GroupRosterHook groupRosterHook;
    private final RagInjectionHook ragInjectionHook;
    private final SystemMessageMergeHook systemMessageMergeHook;
    private final UserProfileQueryTool userProfileQueryTool;
    private final TopicHistoryTool topicHistoryTool;
    private final KnowledgeSearchTool knowledgeSearchTool;

    /**
     * 构建讨论场景 ReactAgent（轻量工具）。
     * <p>系统提示词不再挂载为 ReactAgent 字段级 systemPrompt，而是由统一 LlmService
     * 写入 state（{@link SystemMessageMergeHook#BASE_SYSTEM_PROMPT_KEY}），由合并 Hook 与其他
     * 注入的 SystemMessage 拼成单条，避免多 SystemMessage 干扰模型。
     *
     * @param domainAgent 领域 Agent 实体
     * @param toolSet     工具集
     * @return 配置好的 ReactAgent（每次新建，无状态）
     */
    public ReactAgent buildDiscussAgent(Agent domainAgent, ToolSet toolSet) {
        return build(domainAgent, toolSet, DISCUSS_RECURSION_LIMIT);
    }

    /**
     * 构建工作场景 ReactAgent（通用工具集，深度 ReAct）。
     */
    public ReactAgent buildWorkAgent(Agent domainAgent) {
        return build(domainAgent, ToolSet.WORK, WORK_RECURSION_LIMIT);
    }

    /**
     * 构建 ReactAgent 通用方法。
     */
    private ReactAgent build(Agent domainAgent, ToolSet toolSet, int recursionLimit) {
        ToolCallback[] tools = resolveTools(toolSet);

        ReactAgent agent = ReactAgent.builder()
                .name(domainAgent.getName())
                .description(domainAgent.getDescription() != null ? domainAgent.getDescription() : "")
                .model(modelFactory.buildChatModel(domainAgent, null))
                .tools(tools)
                // Hook 单例共享安全：实现仅从 state 读 per-call 参数，不使用 agent 引用。
                // 合并 Hook 必须注册在最后：ReactAgent 按注册顺序执行 Hook，保证模型调用前
                // 已把所有 SystemMessage 收敛为单条置顶（SystemMessageMergeHook.beforeModel）
                .hooks(memoryInjectionHook, profileInjectionHook, groupRosterHook, ragInjectionHook,
                        systemMessageMergeHook)
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
