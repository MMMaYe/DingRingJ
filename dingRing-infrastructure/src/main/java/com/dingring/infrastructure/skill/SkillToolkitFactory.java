package com.dingring.infrastructure.skill;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.AgentTool;
import com.dingring.common.util.LogHelper;
import com.dingring.domain.skill.Skill;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SKILL 工具集工厂（Phase F）。
 * <p>将 Skill 声明的 toolNames 解析为 ToolCallback 数组，供 ReactAgent 挂载。
 * <p>工具注册表是固定的 @Tool Bean（Phase D 的 UserProfileQueryTool/TopicHistoryTool/KnowledgeSearchTool），
 * 按名称匹配（Spring AI @Tool 默认取方法名），未知工具名 WARN 跳过（不阻断装配）。
 * <p>后续接入新工具：新增 @Tool Bean + 在此注册即可，无需改 ReactAgent 装配逻辑。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillToolkitFactory {

    /** 工具名 → ToolCallback 注册表（构造时初始化） */
    private final Map<String, ToolCallback> toolRegistry = new LinkedHashMap<>();

    private final com.dingring.infrastructure.agent.tool.UserProfileQueryTool userProfileQueryTool;
    private final com.dingring.infrastructure.agent.tool.TopicHistoryTool topicHistoryTool;
    private final com.dingring.infrastructure.agent.tool.KnowledgeSearchTool knowledgeSearchTool;

    /** 初始化工具注册表（Bean 构造完成后执行，先于 SkillLoader 装配） */
    @PostConstruct
    public void init() {
        register(userProfileQueryTool);
        register(topicHistoryTool);
        register(knowledgeSearchTool);
        LogHelper.printLog(SkillToolkitFactory.class, "init", "SKILL_TOOLKIT",
                "工具注册表初始化完成", "tools={}", toolRegistry.keySet());
    }

    /** 按 Skill 声明解析工具集（未知工具名跳过并告警） */
    public ToolCallback[] resolveTools(Skill skill) {
        List<ToolCallback> callbacks = new ArrayList<>();
        for (String toolName : skill.toolNameList()) {
            ToolCallback callback = toolRegistry.get(toolName);
            if (callback == null) {
                log.warn("技能声明了未知工具，跳过: skill={} tool={}", skill.getName(), toolName);
                continue;
            }
            callbacks.add(callback);
        }
        return callbacks.toArray(new ToolCallback[0]);
    }

    /** 将 @Tool Bean 的每个 @Tool 方法注册为命名工具 */
    private void register(Object toolBean) {
        for (ToolCallback callback : org.springframework.ai.support.ToolCallbacks.from(toolBean)) {
            String name = callback.getToolDefinition().name();
            toolRegistry.put(name, callback);
        }
    }
}
