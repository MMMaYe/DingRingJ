package com.dingring.app.event;

import com.dingring.common.util.LogHelper;
import com.dingring.domain.discussion.Topic;
import com.dingring.domain.event.TopicClosed;
import com.dingring.domain.service.TopicVectorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 话题向量事件处理器（P2，第 4 个 TopicClosed 消费者）。
 * <p>TopicClosed 时把标题写入 topic_id_store：此刻标题终态 + 结论 + 用户画像齐备，
 * 建题语义回溯命中后能注入最完整上下文。
 * <p>@Async + 异常吞没：向量写入是增强能力，绝不能反噬收束主流程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TopicVectorEventHandler {

    private final TopicVectorService topicVectorService;

    @Async
    @EventListener
    public void onTopicClosed(TopicClosed event) {
        try {
            Topic topic = new Topic();
            topic.setId(event.getTopicId());
            topic.setChatGroupId(event.getGroupId());
            topic.setTitle(event.getTitle());
            topicVectorService.indexTopic(topic);
        } catch (Exception e) {
            LogHelper.printWarnLog(TopicVectorEventHandler.class, "onTopicClosed", "TOPIC_VECTOR",
                    "话题向量化失败不影响收束", "topicId={} 错误: {}", event.getTopicId(), e.getMessage());
        }
    }
}
