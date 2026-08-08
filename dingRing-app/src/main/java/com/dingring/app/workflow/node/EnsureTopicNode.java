package com.dingring.app.workflow.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.dingring.app.orchestrator.MessageRouter;
import com.dingring.common.constant.WsConstants;
import com.dingring.common.util.JsonHelper;
import com.dingring.common.util.LogHelper;
import com.dingring.domain.discussion.Topic;
import com.dingring.domain.discussion.TopicRepository;
import com.dingring.domain.discussion.TopicStatus;
import com.dingring.domain.event.TopicCreated;
import com.dingring.domain.group.GroupMessage;
import com.dingring.domain.group.GroupRepository;
import com.dingring.domain.group.MessageRepository;
import com.dingring.domain.service.DomainEventPublisher;
import com.dingring.domain.service.GroupBroadcastService;
import com.dingring.domain.workflow.StateKeys;
import com.dingring.infrastructure.aop.Event;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 建题节点：追溯式建题（HIGH 置信度立即建；连续 LOW 达 2 次建）。
 * <p>迁移自 {@link com.dingring.app.orchestrator.DiscussionEngine#ensureTopic} + {@code backfillChatMessages}。
 * <p>设计要点（方案 6.4 决策）：
 * <ul>
 *   <li>无状态：lowDiscussStreak 由 DiscussionEngine 维护并通过 inputs 传入，每次 advance 独立判定</li>
 *   <li>建题失败（未达门槛）：写 ensureSuccess=false，条件边回退到 chat 节点按闲聊应答</li>
 *   <li>唯一约束冲突：沿用现有 Topic（并发建题）或加时间后缀重试一次（标题撞车）</li>
 *   <li>回填：把上一个主题关闭之后的近期闲聊消息归入新主题，保证话题上下文完整</li>
 *   <li>广播 TOPIC_CREATED 事件：携带 restartHint 供前端展示重启提示</li>
 * </ul>
 * <p>条件边：SaaWorkflow 中 ensure-topic 节点按 ensureSuccess 分流到 discuss / chat。
 */
@Slf4j
@Component("ensureTopicHandler")
@RequiredArgsConstructor
public class EnsureTopicNode implements NodeAction {

    /** 连续 LOW 置信度 DISCUSS 达该次数即建题（与原 DiscussionEngine 保持一致） */
    private static final int LOW_DISCUSS_CREATE_STREAK = 2;

    private final GroupRepository groupRepository;
    private final TopicRepository topicRepository;
    private final MessageRepository messageRepository;
    private final DomainEventPublisher eventPublisher;
    private final GroupBroadcastService groupBroadcastService;

    /**
     * 追溯式建题。
     *
     * @param state OverAllState，包含 groupId/topicTitle（DISCUSS 拟定标题）/confidence/lowDiscussStreak/backfillLimit
     * @return 状态更新：ensureSuccess + topicId/topicTitle（建题成功）/ lowDiscussStreak（更新值）
     */
    @Override
    @Event(eventCode = "ENSURE_TOPIC", eventName = "建题节点")
    public Map<String, Object> apply(OverAllState state) {
        Long groupId = state.<Long>value(StateKeys.GROUP_ID).orElse(null);
        String topicTitle = state.<String>value(StateKeys.TOPIC_TITLE).orElse(null);
        String confidenceStr = state.value(StateKeys.CONFIDENCE, "LOW");
        int lowDiscussStreak = state.value(StateKeys.LOW_DISCUSS_STREAK, 0);
        int backfillLimit = state.value("backfillLimit", 15);

        LogHelper.printLog(EnsureTopicNode.class, "EnsureTopicNode.apply", "ENSURE_TOPIC", "建题判定开始",
                "request={}", JsonHelper.mapToJsonStr(Map.of(
                        "groupId", groupId,
                        "topicTitle", topicTitle,
                        "confidence", confidenceStr,
                        "lowDiscussStreak", lowDiscussStreak,
                        "backfillLimit", backfillLimit)));

        if (groupId == null) {
            throw new IllegalStateException("EnsureTopicNode 缺少必要参数 groupId");
        }

        // 已有活跃话题（PreprocessNode 已加载 topicId）：跳过建题，直接进入讨论
        Long existingTopicId = state.<Long>value(StateKeys.TOPIC_ID).orElse(null);
        if (existingTopicId != null) {
            LogHelper.printLog(EnsureTopicNode.class, "EnsureTopicNode.apply", "ENSURE_TOPIC",
                    "已有活跃话题跳过建题", "groupId={} topicId={}", groupId, existingTopicId);
            Map<String, Object> skip = new HashMap<>();
            skip.put(StateKeys.ENSURE_SUCCESS, true);
            return skip;
        }

        MessageRouter.Confidence confidence;
        try {
            confidence = MessageRouter.Confidence.valueOf(confidenceStr);
        } catch (Exception e) {
            confidence = MessageRouter.Confidence.LOW;
        }

        // 判定是否建题：HIGH 立即建；LOW 累计达门槛建
        boolean create = confidence == MessageRouter.Confidence.HIGH
                || (lowDiscussStreak + 1) >= LOW_DISCUSS_CREATE_STREAK;

        Map<String, Object> result = new HashMap<>();
        if (!create) {
            // 未达建题门槛：累加计数，标记失败让条件边回退到 chat
            result.put(StateKeys.LOW_DISCUSS_STREAK, lowDiscussStreak + 1);
            result.put(StateKeys.ENSURE_SUCCESS, false);
            LogHelper.printLog(EnsureTopicNode.class, "EnsureTopicNode.apply", "ENSURE_TOPIC", "低置信度暂不建题",
                    "streak={}/{} groupId={}", lowDiscussStreak + 1, LOW_DISCUSS_CREATE_STREAK, groupId);
            return result;
        }

        // 建题：重置计数
        result.put(StateKeys.LOW_DISCUSS_STREAK, 0);

        Topic topic = new Topic();
        topic.setChatGroupId(groupId);
        topic.setTitle(topicTitle);
        topic.setStatus(TopicStatus.IN_PROGRESS);
        try {
            topicRepository.save(topic);
        } catch (DuplicateKeyException e) {
            Optional<Topic> existing = topicRepository.findActiveByGroupId(groupId);
            if (existing.isPresent()) {
                LogHelper.printWarnLog(EnsureTopicNode.class, "EnsureTopicNode.apply", "ENSURE_TOPIC",
                        "建题并发冲突沿用现有", "groupId={}", groupId);
                topic = existing.get();
            } else {
                // 与历史主题标题撞车：加时间后缀重试一次
                topic.setId(null);
                topic.setTitle(topicTitle + "·"
                        + LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMdd-HHmm")));
                try {
                    topicRepository.save(topic);
                } catch (DuplicateKeyException e2) {
                    LogHelper.printWarnLog(EnsureTopicNode.class, "EnsureTopicNode.apply", "ENSURE_TOPIC",
                            "建题重试仍冲突放弃", "groupId={}", groupId);
                    result.put(StateKeys.ENSURE_SUCCESS, false);
                    return result;
                }
            }
        }

        // 回填上一个主题关闭之后的近期闲聊消息到新主题
        int backfilled = backfillChatMessages(groupId, topic.getId(), backfillLimit);

        // 发布领域事件 + WS 广播
        eventPublisher.publish(new TopicCreated(topic.getId(), groupId, topic.getTitle()));
        groupBroadcastService.broadcast(groupId, WsConstants.TOPIC_CREATED, Map.of(
                "groupId", groupId,
                "topicId", topic.getId(),
                "title", topic.getTitle(),
                "status", topic.getStatus().name(),
                "round", 0,
                "restartHint", "新话题「" + topic.getTitle() + "」已开始，可以开始讨论"));

        result.put(StateKeys.TOPIC_ID, topic.getId());
        result.put(StateKeys.TOPIC_TITLE, topic.getTitle());
        result.put(StateKeys.ENSURE_SUCCESS, true);

        LogHelper.printLog(EnsureTopicNode.class, "EnsureTopicNode.apply", "ENSURE_TOPIC", "追溯式建题成功",
                "groupId={} topicId={} title={} 回填条数={}",
                groupId, topic.getId(), topic.getTitle(), backfilled);
        return result;
    }

    /** 把上一个主题关闭之后的近期闲聊消息回填进新主题 */
    private int backfillChatMessages(Long groupId, Long topicId, int backfillLimit) {
        List<GroupMessage> recent = messageRepository.findRecentChatByGroupId(groupId, backfillLimit);
        LocalDateTime boundary = lastClosedAt(groupId);
        List<Long> ids = recent.stream()
                .filter(m -> boundary == null || m.getCreateTime() == null
                        || m.getCreateTime().isAfter(boundary))
                .map(GroupMessage::getId)
                .toList();
        if (ids.isEmpty()) {
            return 0;
        }
        int updated = messageRepository.updateTopicId(ids, topicId);
        LogHelper.printLog(EnsureTopicNode.class, "EnsureTopicNode.backfillChatMessages", "ENSURE_TOPIC",
                "回填完成", "topicId={} 回填条数={}", topicId, updated);
        return updated;
    }

    private LocalDateTime lastClosedAt(Long groupId) {
        List<Topic> closed = topicRepository.findClosedByGroupId(groupId);
        return closed.isEmpty() ? null : closed.get(closed.size() - 1).getClosedAt();
    }
}
