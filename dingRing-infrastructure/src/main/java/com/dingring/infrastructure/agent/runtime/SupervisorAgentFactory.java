package com.dingring.infrastructure.agent.runtime;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.agent.AgentTool;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.dingring.common.util.LogHelper;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.skill.Skill;
import com.dingring.domain.skill.SkillLoaderService;
import com.dingring.infrastructure.agent.hook.GroupContextMemoryHook;
import com.dingring.infrastructure.agent.hook.GroupRosterHook;
import com.dingring.infrastructure.agent.hook.InjectKbHook;
import com.dingring.infrastructure.agent.hook.ProfileInjectionHook;
import com.dingring.infrastructure.agent.hook.WorkProgressBroadcastHook;
import com.dingring.infrastructure.agent.tool.KnowledgeSearchTool;
import com.dingring.infrastructure.agent.tool.TopicHistoryTool;
import com.dingring.infrastructure.agent.tool.UserProfileQueryTool;
import com.dingring.infrastructure.llm.SaaLlmFactory;
import com.dingring.infrastructure.skill.SkillToolkitFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Supervisor Agent 工厂（Phase F）。
 * <p>将子 Agent 包装为工具（{@link AgentTool#getFunctionToolCallback(ReactAgent)}）注册给
 * Supervisor，由 Supervisor 负责任务拆解与委派，实现「编排者-执行者」模式。
 * <p>设计要点（方案 9.2）：
 * <ul>
 *   <li>Supervisor 只注册子 Agent 作为工具：不掺普通工具，强制走委派而非亲自动手</li>
 *   <li>挂载 {@link WorkProgressBroadcastHook}：每次委派（工具调用）前后推送 WORK_PROGRESS/WORK_RESULT</li>
 *   <li>子 Agent（worker）构建：人设 systemPrompt + 通用工具集 + 该 Agent 绑定的 SKILL 工具
 *       （SkillToolkitFactory 按 toolNames 解析）</li>
 *   <li>注意：SAA AgentTool 委派时会 {@code config.clearContext()} 且初始 state 仅含 messages，
 *       子 Agent 无法读到 groupId 等上下文——因此子 Agent 的 state 依赖 Hook 会安全跳过，
 *       群上下文需 Supervisor 在委派参数中携带</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SupervisorAgentFactory {

    /** Supervisor 角色名（工具注册名与日志标识） */
    private static final String SUPERVISOR_NAME = "work-supervisor";
    /** Supervisor ReAct 循环上限：拆解+多次委派，需足够深度 */
    private static final int SUPERVISOR_RECURSION_LIMIT = 15;
    /**
     * 子 Agent ReAct 循环上限。
     * <p>注意：recursionLimit 按图节点执行次数计数（单轮 = __START__ + 4 hooks + 模型 ≥ 6 步），
     * 必须留足「工具调用后第二轮回读结果」的余量，否则模型第二轮永不执行。
     */
    private static final int WORKER_RECURSION_LIMIT = 15;

    private final SaaLlmFactory llmFactory;
    private final WorkProgressBroadcastHook workProgressBroadcastHook;
    private final GroupContextMemoryHook groupContextMemoryHook;
    private final ProfileInjectionHook profileInjectionHook;
    private final GroupRosterHook groupRosterHook;
    private final InjectKbHook injectKbHook;
    private final SkillToolkitFactory skillToolkitFactory;
    private final SkillLoaderService skillLoaderService;
    private final KnowledgeSearchTool knowledgeSearchTool;
    private final TopicHistoryTool topicHistoryTool;
    private final UserProfileQueryTool userProfileQueryTool;

    /**
     * 构建 Supervisor Agent。
     *
     * @param supervisorAgent 编排者 Agent（提供 LLM 模型配置与角色）
     * @param systemPrompt    Supervisor 系统提示词（任务拆解与委派协议）
     * @param subAgents       子 Agent 列表（每个包装为一个工具）
     * @return 配置好的 Supervisor ReactAgent（每次新建，无状态）
     */
    public ReactAgent buildSupervisor(Agent supervisorAgent, String systemPrompt, List<Agent> subAgents) {
        ToolCallback[] subTools = new ToolCallback[subAgents.size()];
        for (int i = 0; i < subAgents.size(); i++) {
            ReactAgent worker = buildWorker(subAgents.get(i));
            subTools[i] = AgentTool.getFunctionToolCallback(worker);
        }

        ReactAgent supervisor = ReactAgent.builder()
                .name(SUPERVISOR_NAME)
                .description("工作流编排者：将任务拆解为子任务并委派给专业子 Agent，汇总结果")
                .model(llmFactory.buildChatModel(supervisorAgent, null))
                .systemPrompt(systemPrompt)
                .tools(subTools)
                // Supervisor 的 state 含 groupId（WorkNode 注入），state 依赖 Hook 可正常工作；
                // InjectKbHook 防御行——Worker 被 AgentTool 委派时 clearContext 后 state 无 intent/ragQuery/topicId，自然跳过
                .hooks(workProgressBroadcastHook, groupContextMemoryHook, profileInjectionHook,
                        groupRosterHook, injectKbHook)
                .compileConfig(CompileConfig.builder()
                        .recursionLimit(SUPERVISOR_RECURSION_LIMIT)
                        .build())
                .build();

        LogHelper.printLog(SupervisorAgentFactory.class, "buildSupervisor", "SUPERVISOR_BUILD",
                "Supervisor 构建完成", "supervisor={} workerCount={} subTools={}",
                supervisorAgent.getName(), subAgents.size(), subTools.length);
        return supervisor;
    }

    /**
     * 构建子 Agent（worker）：人设 + 通用工具集 + SKILL 工具。
     * <p>state 依赖 Hook 照常挂载（SAA 委派时 state 被清空，Hook 会安全跳过，无副作用）。
     */
    private ReactAgent buildWorker(Agent agent) {
        ToolCallback[] tools = resolveWorkerTools(agent);
        String systemPrompt = buildWorkerPrompt(agent);

        ReactAgent worker = ReactAgent.builder()
                .name(agent.getName())
                .description(agent.getDescription() != null ? agent.getDescription() : "")
                .model(llmFactory.buildChatModel(agent, null))
                .systemPrompt(systemPrompt)
                .tools(tools)
                .hooks(groupContextMemoryHook, profileInjectionHook, groupRosterHook, injectKbHook)
                .compileConfig(CompileConfig.builder()
                        .recursionLimit(WORKER_RECURSION_LIMIT)
                        .build())
                .build();

        LogHelper.printLog(SupervisorAgentFactory.class, "buildWorker", "SUPERVISOR_BUILD",
                "子 Agent 构建完成", "worker={} tools={}", agent.getName(), tools.length);
        return worker;
    }

    /**
     * 子 Agent 工具集 = 通用工作工具（知识检索/历史/画像）+ SKILL 声明工具。
     * <p>SKILL 工具按名称从 SkillToolkitFactory 注册表解析，未命中（名称拼错或工具未注册）由工厂 WARN 跳过。
     */
    private ToolCallback[] resolveWorkerTools(Agent agent) {
        List<ToolCallback> tools = new ArrayList<>();
        tools.addAll(Arrays.asList(ToolCallbacks.from(knowledgeSearchTool, topicHistoryTool, userProfileQueryTool)));
        List<Skill> skills = skillLoaderService.loadAgentSkills(agent.getId());
        for (Skill skill : skills) {
            tools.addAll(Arrays.asList(skillToolkitFactory.resolveTools(skill)));
        }
        return tools.toArray(new ToolCallback[0]);
    }

    /** 子 Agent systemPrompt：人设 + 单任务执行协议 */
    private String buildWorkerPrompt(Agent agent) {
        StringBuilder sp = new StringBuilder();
        if (agent.getSystemPrompt() != null && !agent.getSystemPrompt().isBlank()) {
            sp.append(agent.getSystemPrompt()).append("\n\n");
        }
        sp.append("你是工作流中的专业执行者。Supervisor 会委派给你一个具体子任务，");
        sp.append("请专注完成该子任务：必要时调用可用工具（知识检索/历史结论/用户画像等）辅助，");
        sp.append("然后直接输出子任务结果，不要自行扩展任务范围。");
        return sp.toString();
    }
}
