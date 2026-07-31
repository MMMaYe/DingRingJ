package com.dingring.app.task;

import com.dingring.app.event.CardEventHandler;
import com.dingring.common.util.LogHelper;
import com.dingring.domain.discussion.CardRepository;
import com.dingring.domain.discussion.Topic;
import com.dingring.domain.discussion.TopicRepository;
import com.dingring.domain.event.TopicClosed;
import com.dingring.domain.group.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 卡片对账：TopicClosed 事件是进程内投递，崩溃会丢；
 * 定时扫描近期已关闭但没有知识卡片的主题，走幂等入口补生成。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CardReconciler {

    private final TopicRepository topicRepository;
    private final CardRepository cardRepository;
    private final MessageRepository messageRepository;
    private final CardEventHandler cardEventHandler;

    /** 对账回溯窗口（天） */
    @Value("${dingring.orchestrator.card-reconcile-window-days:7}")
    private int reconcileWindowDays;

    @Scheduled(fixedDelay = 600_000, initialDelay = 120_000)
    public void reconcile() {
        LocalDateTime since = LocalDateTime.now().minusDays(reconcileWindowDays);
        List<Topic> closed = topicRepository.findClosedSince(since);
        for (Topic topic : closed) {
            try {
                if (topic.getConclusion() == null || topic.getConclusion().isBlank()) {
                    continue;
                }
                if (!cardRepository.findByTopicId(topic.getId()).isEmpty()) {
                    continue;
                }
                Long concluderAgentId = topic.concludedByAgentId().orElse(null);
                if (concluderAgentId == null) {
                    LogHelper.printWarnLog(log, "CardReconciler.reconcile", "主题无总结Agent记录跳过", "topicId=" + topic.getId());
                    continue;
                }
                LogHelper.printLog(log, "CardReconciler.reconcile", "发现无卡片已关闭主题补生成", "topicId=%d", topic.getId());
                cardEventHandler.generateCards(new TopicClosed(
                        topic.getId(), topic.getChatGroupId(), topic.getTitle(), topic.getConclusion(),
                        messageRepository.countByTopicId(topic.getId()), "RECONCILE", concluderAgentId));
            } catch (Exception e) {
                LogHelper.printWarnLog(log, "CardReconciler.reconcile", "补生成失败", "topicId=" + topic.getId(), e);
            }
        }
    }
}
