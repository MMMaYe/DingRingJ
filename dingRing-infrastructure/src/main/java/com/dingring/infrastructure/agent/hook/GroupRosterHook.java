package com.dingring.infrastructure.agent.hook;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import com.dingring.common.util.LogHelper;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.agent.AgentRepository;
import com.dingring.domain.group.Group;
import com.dingring.domain.group.GroupRepository;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 群成员名单注入 Hook（Phase D）。
 * <p>在 LLM 调用前注入群成员名单（花名 + 一句话简介），替代 Phase C 由 ContextBuilder 静态拼接。
 * <p>注入方式：beforeModel 返回 {@code Map.of("messages", new SystemMessage(roster))}，
 * SAA 图引擎按 AppendStrategy 追加到 messages 列表。
 * <p>发言者本人标「你」强化自我认知，抑制冒充他人发言。
 */
@Component
public class GroupRosterHook extends ModelHook {

    /** 成员一句话简介最大长度（防名单撑爆 token） */
    private static final int MEMBER_INTRO_MAX_LEN = 30;

    /** 群成员名单段标题 */
    private static final String GROUP_MEMBERS_HEADER = "群成员名单：";

    private final GroupRepository groupRepository;
    private final AgentRepository agentRepository;

    public GroupRosterHook(GroupRepository groupRepository, AgentRepository agentRepository) {
        this.groupRepository = groupRepository;
        this.agentRepository = agentRepository;
    }

    @Override
    public String getName() {
        return "group-roster";
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeModel(OverAllState state, RunnableConfig config) {
        Long groupId = state.<Long>value("groupId").orElse(null);
        Long speakerAgentId = state.<Long>value("speakerAgentId").orElse(null);
        if (groupId == null) {
            return CompletableFuture.completedFuture(Map.of());
        }

        Group group = groupRepository.findById(groupId).orElse(null);
        if (group == null) {
            return CompletableFuture.completedFuture(Map.of());
        }

        List<Agent> members = agentRepository.findByIds(group.memberAgentIds());
        if (members.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }

        String roster = buildRoster(members, speakerAgentId);
        LogHelper.printLog(GroupRosterHook.class, "GroupRosterHook.beforeModel",
                "HOOK_ROSTER", "注入群成员名单", "groupId={} 成员数={}", groupId, members.size());

        return CompletableFuture.completedFuture(
                Map.of("messages", new SystemMessage(roster))
        );
    }

    /** 构建群成员名单段：花名 + 一句话简介，发言者本人标「你」 */
    private String buildRoster(List<Agent> members, Long speakerAgentId) {
        StringBuilder roster = new StringBuilder(GROUP_MEMBERS_HEADER);
        for (Agent m : members) {
            boolean isSelf = speakerAgentId != null && speakerAgentId.equals(m.getId());
            roster.append("\n- ").append(isSelf ? "你（" + m.getName() + "）" : m.getName());
            String intro = memberIntro(m);
            if (!intro.isBlank()) {
                roster.append("：").append(intro);
            }
        }
        return roster.toString();
    }

    /** 成员一句话简介：优先性格描述，否则取人设首句 */
    private String memberIntro(Agent m) {
        String source = m.getDescription() != null && !m.getDescription().isBlank()
                ? m.getDescription()
                : m.getSystemPrompt();
        if (source == null || source.isBlank()) {
            return "";
        }
        String first = source.strip().split("[。\n！？!?]", 2)[0].strip();
        return first.length() > MEMBER_INTRO_MAX_LEN ? first.substring(0, MEMBER_INTRO_MAX_LEN) : first;
    }
}
