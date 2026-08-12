package com.dingring.app.workflow.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.dingring.app.service.GroupAppService;
import com.dingring.app.service.MessageAssembler;
import com.dingring.common.util.JsonHelper;
import com.dingring.common.util.LogHelper;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.agent.AgentRepository;
import com.dingring.domain.group.GroupMessage;
import com.dingring.domain.group.GroupRepository;
import com.dingring.domain.group.MessageRepository;
import com.dingring.domain.service.ProfileService;
import com.dingring.domain.workflow.StateKeys;
import com.dingring.infrastructure.aop.Event;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 画像提炼节点：从近期闲聊对话中提炼用户画像（跨群全局记忆）。
 * <p>迁移自 {@link com.dingring.app.orchestrator.DiscussionEngine#triggerProfileExtraction}。
 * <p>设计要点：
 * <ul>
 *   <li>异步执行：虚拟线程调用 ProfileService.extractAndMerge，不阻塞 StateGraph</li>
 *   <li>用户维度串行：ProfileService 内部保证同一时刻只跑一个提炼任务</li>
 *   <li>失败仅日志留痕：画像提炼是辅助功能，失败不影响主流程</li>
 *   <li>extractor 用群首个成员 Agent（复用其模型配置）</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProfileExtractNode implements NodeAction {

    private final GroupRepository groupRepository;
    private final AgentRepository agentRepository;
    private final MessageRepository messageRepository;
    private final MessageAssembler messageAssembler;
    private final ProfileService profileService;

    /**
     * 触发画像提炼（异步虚拟线程）。
     *
     * @param state OverAllState，包含 groupId/profileExtractThreshold
     * @return 空状态更新（画像提炼异步执行，结果不回写 state）
     */
    @Override
    @Event(eventCode = "PROFILE_EXTRACT_NODE", eventName = "画像提炼节点")
    public Map<String, Object> apply(OverAllState state) {
        Long groupId = state.<Long>value(StateKeys.GROUP_ID).orElse(null);
        int profileThreshold = state.value("profileExtractThreshold", 15);

        // 用 HashMap 而非 Map.of：groupId 可能为 null（防御性日志不应在入口先抛 NPE）
        Map<String, Object> logMap = new HashMap<>();
        logMap.put("groupId", groupId);
        logMap.put("profileThreshold", profileThreshold);
        LogHelper.printLog(ProfileExtractNode.class, "ProfileExtractNode.apply", "PROFILE_EXTRACT_NODE",
                "画像提炼开始", "request={}", JsonHelper.mapToJsonStr(logMap));

        if (groupId == null) {
            LogHelper.printWarnLog(ProfileExtractNode.class, "ProfileExtractNode.apply", "PROFILE_EXTRACT_NODE",
                    "缺少groupId跳过", "");
            return Map.of();
        }

        GroupMessage absent = new GroupMessage();
        absent.setChatGroupId(groupId);
        var groupOpt = groupRepository.findById(groupId);
        if (groupOpt.isEmpty()) {
            return Map.of();
        }
        List<Agent> agents = agentRepository.findByIds(groupOpt.get().memberAgentIds());
        if (agents.isEmpty()) {
            LogHelper.printWarnLog(ProfileExtractNode.class, "ProfileExtractNode.apply", "PROFILE_EXTRACT_NODE",
                    "无成员Agent跳过", "groupId={}", groupId);
            return Map.of();
        }
        Agent extractor = agents.get(0);
        List<GroupMessage> recent = messageRepository.findRecentChatByGroupId(groupId, profileThreshold);
        String dialogue = formatDialogue(recent);
        String groupName = groupOpt.get().getName();

        LogHelper.printLog(ProfileExtractNode.class, "ProfileExtractNode.apply", "PROFILE_EXTRACT_NODE",
                "触发画像提炼", "groupId={} 输入消息数={} extractor={}", groupId, recent.size(), extractor.getName());

        // 异步虚拟线程执行：不阻塞 StateGraph
        Thread.ofVirtual().name("profile-extract-" + groupId).start(() -> {
            try {
                profileService.extractAndMerge(GroupAppService.DEFAULT_USER_ID,
                        extractor, groupName, dialogue);
                LogHelper.printLog(ProfileExtractNode.class, "ProfileExtractNode.asyncTask", "PROFILE_EXTRACT_NODE",
                        "画像提炼完成", "groupId={} extractor={} 输入消息数={}",
                        groupId, extractor.getName(), recent.size());
            } catch (Exception e) {
                LogHelper.printWarnLog(ProfileExtractNode.class, "ProfileExtractNode.asyncTask", "PROFILE_EXTRACT_NODE",
                        "画像提炼失败", "groupId={} extractor={}", groupId, extractor.getName(), e);
            }
        });

        return Map.of();
    }

    private String formatDialogue(List<GroupMessage> messages) {
        return messages.stream()
                .map(m -> messageAssembler.resolveSenderName(m) + ": " + m.getContent())
                .collect(Collectors.joining("\n"));
    }
}
