package com.dingring.app.workflow.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.dingring.app.orchestrator.MessageRouter;
import com.dingring.app.service.GroupAppService;
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
import com.dingring.domain.service.TopicVectorService;
import com.dingring.domain.user.UserTopicProfile;
import com.dingring.domain.user.UserTopicProfileRepository;
import com.dingring.domain.workflow.StateKeys;
import com.dingring.infrastructure.aop.Event;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
@Component
@RequiredArgsConstructor
public class EnsureTopicNode implements NodeAction {

    /** 连续 LOW 置信度 DISCUSS 达该次数即建题（与原 DiscussionEngine 保持一致） */
    private static final int LOW_DISCUSS_CREATE_STREAK = 2;

    /** 语义回溯相似度阈值：低于此值的历史话题视为不相关 */
    private static final double SIMILAR_THRESHOLD = 0.75;
    private static final int SIMILAR_TOP_K = 3;

    private final GroupRepository groupRepository;
    private final TopicRepository topicRepository;
    private final MessageRepository messageRepository;
    private final DomainEventPublisher eventPublisher;
    private final GroupBroadcastService groupBroadcastService;
    private final UserTopicProfileRepository topicProfileRepository;
    private final TopicVectorService topicVectorService;

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

        // 用 HashMap 而非 Map.of：groupId/topicTitle 可能为 null（防御性日志不应在入口先抛 NPE）
        Map<String, Object> logMap = new HashMap<>();
        logMap.put("groupId", groupId);
        logMap.put("topicTitle", topicTitle);
        logMap.put("confidence", confidenceStr);
        logMap.put("lowDiscussStreak", lowDiscussStreak);
        logMap.put("backfillLimit", backfillLimit);
        LogHelper.printLog(EnsureTopicNode.class, "EnsureTopicNode.apply", "ENSURE_TOPIC", "建题判定开始",
                "request={}", JsonHelper.mapToJsonStr(logMap));

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
            LogHelper.printWarnLog(EnsureTopicNode.class, "EnsureTopicNode.apply", "ENSURE_TOPIC",
                    "confidence解析失败按LOW处理", "groupId={} confidenceStr={}", groupId, confidenceStr);
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

        // 话题重启回溯（P2 升级：向量语义检索相似历史话题，无命中/异常回退标题精确匹配）
        // 用原始 topicTitle 而非 topic.getTitle()：撞车重试后者的标题带时间后缀，
        // 语义检索与回退精确匹配都会因后缀噪音而失配
        TopicBacktrack backtrack = backtrackTopic(topic, topicTitle);
        List<UserTopicProfile> history = backtrack.history();
        String restartHint = "新话题「" + topic.getTitle() + "」已开始，可以开始讨论";
        String userHistoryHint = "";
        if (!history.isEmpty()) {
            UserTopicProfile latest = history.get(0);
            restartHint = String.format("这是第%d次讨论「%s」，上次你在「%s」方面还需提升",
                    history.size() + 1, topic.getTitle(),
                    latest.getWeakPoints() == null || latest.getWeakPoints().isBlank()
                            ? "知识深度" : latest.getWeakPoints());
            userHistoryHint = formatHistoryHint(history, backtrack.relatedConclusions());
            LogHelper.printLog(EnsureTopicNode.class, "EnsureTopicNode.apply", "ENSURE_TOPIC", "话题重启回溯命中",
                    "topicId={} title={} 历史次数={} 相似话题结论={}",
                    topic.getId(), topic.getTitle(), history.size(), backtrack.relatedConclusions().size());
        } else if (!backtrack.relatedConclusions().isEmpty()) {
            // 无画像但有相似历史结论：历史讨论沉淀仍有价值（如用户第一次聊但系统已有相关结论）
            restartHint = "检测到与历史话题「" + backtrack.topSimilarTitle() + "」相关，可参考既往结论展开";
            userHistoryHint = formatHistoryHint(history, backtrack.relatedConclusions());
            LogHelper.printLog(EnsureTopicNode.class, "EnsureTopicNode.apply", "ENSURE_TOPIC", "相似话题结论命中",
                    "topicId={} title={} 相似话题={}", topic.getId(), topic.getTitle(), backtrack.topSimilarTitle());
        }

        // 发布领域事件 + WS 广播
        eventPublisher.publish(new TopicCreated(topic.getId(), groupId, topic.getTitle()));
        groupBroadcastService.broadcast(groupId, WsConstants.TOPIC_CREATED, Map.of(
                "groupId", groupId,
                "topicId", topic.getId(),
                "title", topic.getTitle(),
                "status", topic.getStatus().name(),
                "round", history.size(),
                "restartHint", restartHint));

        result.put(StateKeys.TOPIC_ID, topic.getId());
        result.put(StateKeys.TOPIC_TITLE, topic.getTitle());
        result.put(StateKeys.ENSURE_SUCCESS, true);
        result.put(StateKeys.RESTART_HINT, restartHint);
        result.put(StateKeys.USER_HISTORY_HINT, userHistoryHint);

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

    /**
     * 语义回溯：向量检索相似历史话题 → 回查结论与用户画像。
     * <p>容错链：向量服务异常/返回 null/无命中 → 回退标题精确匹配（Phase E 原逻辑），
     * 任何情况不阻塞建题。
     *
     * @param originalTitle 意图分类拟定的原始标题（撞车重试前），语义检索与回退匹配均基于它
     */
    private TopicBacktrack backtrackTopic(Topic topic, String originalTitle) {
        List<TopicVectorService.SimilarTopic> similar;
        try {
            similar = topicVectorService.findSimilarTopics(originalTitle, SIMILAR_TOP_K, SIMILAR_THRESHOLD);
        } catch (Exception e) {
            LogHelper.printWarnLog(EnsureTopicNode.class, "EnsureTopicNode.backtrackTopic", "ENSURE_TOPIC",
                    "语义回溯异常回退精确匹配", "topicId={} 错误: {}", topic.getId(), e.getMessage());
            similar = List.of();
        }
        if (similar == null) {
            similar = List.of();  // 外部服务边界的防御性判空（如实现缺陷返回 null）：按无命中处理
        }
        // 排除自身（标题撞车/重复讨论时向量库可能召回自己）
        List<TopicVectorService.SimilarTopic> filtered = similar.stream()
                .filter(s -> !topic.getId().equals(s.topicId()))
                .toList();

        if (filtered.isEmpty()) {
            // 回退：标题精确匹配（原 Phase E 行为，同样用原始标题——带后缀标题查画像必空）
            List<UserTopicProfile> exact = topicProfileRepository
                    .findByUserIdAndTopicTitleOrderByCreatedAtDesc(GroupAppService.DEFAULT_USER_ID, originalTitle);
            return new TopicBacktrack(exact, List.of(), null);
        }

        List<UserTopicProfile> history = new ArrayList<>();
        List<String> relatedConclusions = new ArrayList<>();
        for (TopicVectorService.SimilarTopic s : filtered) {
            // 相似话题的画像按其标题精确回查（复用既有仓储方法，零 schema 变更）
            history.addAll(topicProfileRepository.findByUserIdAndTopicTitleOrderByCreatedAtDesc(
                    GroupAppService.DEFAULT_USER_ID, s.title()));
            // 相似话题结论（截断 200 字符控制 prompt 长度）
            topicRepository.findById(s.topicId())
                    .map(t -> t.getConclusion() == null || t.getConclusion().isBlank()
                            ? null : t.getConclusion())
                    .ifPresent(c -> {
                        String trimmed = c.length() > 200 ? c.substring(0, 200) + "…" : c;
                        relatedConclusions.add("「" + s.title() + "」：" + trimmed);
                    });
        }
        return new TopicBacktrack(history, relatedConclusions, filtered.get(0).title());
    }

    /** 语义回溯结果载体 */
    private record TopicBacktrack(List<UserTopicProfile> history,
                                  List<String> relatedConclusions,
                                  String topSimilarTitle) {}

    /**
     * 格式化用户历史表现提示（给 Agent 看，注入 DiscussNode 的 system prompt）。
     * <p>history 按 create_time 倒序，history.get(0) = 最近一次讨论；2 条以上追加进步轨迹，
     * 让 Agent 感知用户是否在进步并调整引导力度。
     */
    private String formatHistoryHint(List<UserTopicProfile> history, List<String> relatedConclusions) {
        if (history.isEmpty()) {
            return relatedConclusions == null || relatedConclusions.isEmpty() ? ""
                    : "相关历史话题结论（供参考，勿直接复述）：\n- " + String.join("\n- ", relatedConclusions);
        }
        UserTopicProfile latest = history.get(0);
        StringBuilder sb = new StringBuilder();
        sb.append("用户上次讨论「").append(latest.getTopicTitle()).append("」时:\n");
        sb.append("- 理解程度: ").append(nullToDash(latest.getUnderstandingLevel())).append("\n");
        sb.append("- 薄弱点: ").append(nullToDash(latest.getWeakPoints())).append("\n");
        sb.append("- 建议提升: ").append(nullToDash(latest.getSuggestedFocus()));

        // 2 条以上展示进步轨迹（最老在前），让 Agent 感知成长
        if (history.size() >= 2) {
            sb.append("\n\n进步轨迹:");
            for (int i = history.size() - 1; i >= 0; i--) {
                UserTopicProfile p = history.get(i);
                sb.append("\n- 第").append(history.size() - i).append("次: ")
                        .append(nullToDash(p.getUnderstandingLevel()))
                        .append("(薄弱: ").append(nullToDash(p.getWeakPoints())).append(")");
            }
        }
        sb.append("\n请在本次讨论中针对性地引导用户提升薄弱点。");
        if (relatedConclusions != null && !relatedConclusions.isEmpty()) {
            sb.append("\n\n相关历史话题结论（供参考，勿直接复述）：");
            for (String c : relatedConclusions) {
                sb.append("\n- ").append(c);
            }
        }
        return sb.toString();
    }

    /** 空值渲染为占位符，避免提示词中出现 "null" 字样 */
    private String nullToDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private LocalDateTime lastClosedAt(Long groupId) {
        List<Topic> closed = topicRepository.findClosedByGroupId(groupId);
        return closed.isEmpty() ? null : closed.get(closed.size() - 1).getClosedAt();
    }
}
