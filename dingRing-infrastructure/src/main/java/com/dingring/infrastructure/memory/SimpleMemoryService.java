package com.dingring.infrastructure.memory;

import com.dingring.domain.discussion.Topic;
import com.dingring.domain.discussion.TopicRepository;
import com.dingring.domain.service.MemoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 记忆服务 v1.0 实现：直接拼接同群历史 Topic（CLOSED/ARCHIVED）的结论文本。
 */
@Service
@RequiredArgsConstructor
public class SimpleMemoryService implements MemoryService {

    /** 最多带入的历史结论条数，防止 Prompt 过长 */
    private static final int MAX_MEMORY_TOPICS = 5;

    private final TopicRepository topicRepository;

    @Override
    public String retrieveMemory(Long groupId) {
        List<Topic> closedTopics = topicRepository.findClosedByGroupId(groupId);
        if (closedTopics == null || closedTopics.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("## 本群历史讨论结论（供参考）\n");
        closedTopics.stream()
                .filter(t -> t.getConclusion() != null && !t.getConclusion().isBlank())
                .limit(MAX_MEMORY_TOPICS)
                .forEach(t -> sb.append("\n### 主题：").append(t.getTitle()).append('\n')
                        .append(t.getConclusion()).append('\n'));
        return sb.toString();
    }
}
