package com.dingring.app.workflow.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.dingring.common.util.JsonHelper;
import com.dingring.common.util.LogHelper;
import com.dingring.domain.discussion.Topic;
import com.dingring.domain.discussion.TopicRepository;
import com.dingring.domain.group.Group;
import com.dingring.domain.group.GroupRepository;
import com.dingring.domain.workflow.StateKeys;
import com.dingring.infrastructure.aop.Event;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 预处理节点：群聊流程入口节点。
 * <p>核心职责：加载群信息和活跃话题，把 topicId/topicTitle 写入 OverAllState 供下游节点使用。
 * <p>迁移自 {@link com.dingring.app.orchestrator.ChatOrchestrator#onUserMessage} 中的活跃话题查询逻辑。
 * <p>注意：消息入库广播仍由 ChatOrchestrator.onUserMessage 同步完成（保证 API 响应时序），
 * 本节点只负责"加载上下文信息"——消息已入库，从 DB 查询活跃话题即可。
 * <p>设计要点（方案 6.4 决策 6）：
 * <ul>
 *   <li>信号折叠(fold)已移除：消息已入库，意图分类和 Agent 发言都从 DB 拉完整上下文</li>
 *   <li>每个群同一时刻最多只有一个活跃话题，所以查询 findActiveByGroupId 即可</li>
 * </ul>
 */
@Slf4j
@Component("preprocessHandler")
@RequiredArgsConstructor
public class PreprocessNode implements NodeAction {

    private final GroupRepository groupRepository;
    private final TopicRepository topicRepository;

    /**
     * 加载群信息和活跃话题，设置到 state。
     * <p>不返回意图（由 IntentClassifyNode 负责判定），只设置 topicId/topicTitle 供下游节点读取。
     *
     * @param state OverAllState，包含 groupId/input/mentionedAgentIds/repliedToAgentId
     * @return 状态更新：topicId/topicTitle（无活跃话题时为 null/空）
     */
    @Override
    @Event(eventCode = "PREPROCESS", eventName = "预处理节点")
    public Map<String, Object> apply(OverAllState state) {
        Long groupId = state.<Long>value(StateKeys.GROUP_ID).orElse(null);
        String input = state.value(StateKeys.INPUT, "");

        LogHelper.printLog(PreprocessNode.class, "PreprocessNode.apply", "PREPROCESS", "预处理开始",
                "request={}", JsonHelper.mapToJsonStr(Map.of("groupId", groupId, "inputLen", input == null ? 0 : input.length())));

        if (groupId == null) {
            throw new IllegalStateException("PreprocessNode 缺少必要参数 groupId");
        }

        // 校验群存在（防御性检查，ChatOrchestrator 已校验过）
        Optional<Group> groupOpt = groupRepository.findById(groupId);
        if (groupOpt.isEmpty()) {
            throw new IllegalStateException("群不存在: " + groupId);
        }

        // 加载活跃话题（每群最多一个活跃话题）
        Optional<Topic> activeTopic = topicRepository.findActiveByGroupId(groupId)
                .filter(Topic::isInProgress);

        Map<String, Object> result = new HashMap<>();
        if (activeTopic.isPresent()) {
            Topic topic = activeTopic.get();
            result.put(StateKeys.TOPIC_ID, topic.getId());
            result.put(StateKeys.TOPIC_TITLE, topic.getTitle());
            LogHelper.printLog(PreprocessNode.class, "PreprocessNode.apply", "PREPROCESS", "存在活跃话题",
                    "groupId={} topicId={} title={}", groupId, topic.getId(), topic.getTitle());
        } else {
            result.put(StateKeys.TOPIC_ID, null);
            result.put(StateKeys.TOPIC_TITLE, null);
            LogHelper.printLog(PreprocessNode.class, "PreprocessNode.apply", "PREPROCESS", "无活跃话题",
                    "groupId={}", groupId);
        }

        return result;
    }
}
