package com.dingring.app.task;

import com.dingring.app.orchestrator.DiscussionEngine;
import com.dingring.app.service.ChatPusher;
import com.dingring.common.constant.WsConstants;
import com.dingring.common.util.LogHelper;
import com.dingring.domain.discussion.Topic;
import com.dingring.domain.discussion.TopicRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 收束看门狗：结论生成中途进程崩溃会让主题永久卡在 CONCLUDING，
 * 定时把超时的 CONCLUDING 主题回滚为 IN_PROGRESS 并唤醒引擎重新推进。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConclusionWatchdog {

    private final TopicRepository topicRepository;
    private final ChatPusher chatPusher;
    private final DiscussionEngine discussionEngine;

    /** CONCLUDING 状态超过该分钟数视为卡死 */
    @Value("${dingring.orchestrator.concluding-timeout-minutes:5}")
    private int concludingTimeoutMinutes;

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void rescueStuckTopics() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(concludingTimeoutMinutes);
        List<Topic> stuck = topicRepository.findConcludingBefore(threshold);
        for (Topic topic : stuck) {
            try {
                LogHelper.printWarnLog(ConclusionWatchdog.class, "ConclusionWatchdog.rescueStuckTopics", "RESCUE_STUCK_TOPICS", "发现卡死CONCLUDING主题回滚",
                        "topicId={} updateTime={}", topic.getId(), topic.getUpdateTime());
                topic.rollbackToInProgress();
                if (!topicRepository.update(topic)) {
                    // 乐观锁冲突：状态已被别处流转，无需处理
                    continue;
                }
                chatPusher.pushToGroup(topic.getChatGroupId(), WsConstants.TOPIC_STATUS_CHANGED, Map.of(
                        "groupId", topic.getChatGroupId(),
                        "topicId", topic.getId(),
                        "title", topic.getTitle(),
                        "status", topic.getStatus().name(),
                        "previousStatus", "CONCLUDING"));
                discussionEngine.wake(topic.getChatGroupId());
            } catch (Exception e) {
                LogHelper.printWarnLog(ConclusionWatchdog.class, "ConclusionWatchdog.rescueStuckTopics", "RESCUE_STUCK_TOPICS", "卡死主题回滚失败", "topicId={}", topic.getId(), e);
            }
        }
    }
}
