package com.dingring.infrastructure.rag;

import com.dingring.common.util.LogHelper;
import com.dingring.domain.discussion.Topic;
import com.dingring.domain.service.TopicVectorService;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 话题向量服务实现（P2）。
 * <p>content 只存标题：标题短且语义浓缩，相似度判断最准；
 * 结论从 MySQL topic.conclusion 回查，避免向量表冗余。
 */
@Component
@ConditionalOnProperty(name = "dingring.rag.enabled", havingValue = "true", matchIfMissing = true)
public class TopicVectorServiceImpl implements TopicVectorService {

    private final VectorStore topicVectorStore;

    public TopicVectorServiceImpl(@Qualifier("topicVectorStore") VectorStore topicVectorStore) {
        this.topicVectorStore = topicVectorStore;
    }

    @Override
    public void indexTopic(Topic topic) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("topicId", topic.getId());
        metadata.put("groupId", topic.getChatGroupId());
        metadata.put("title", topic.getTitle());
        metadata.put("closedAt", LocalDateTime.now().toString());
        topicVectorStore.add(List.of(new Document(topic.getTitle(), metadata)));
        LogHelper.printLog(TopicVectorServiceImpl.class, "indexTopic", "TOPIC_VECTOR",
                "话题标题已向量化", "topicId={} title={}", topic.getId(), topic.getTitle());
    }

    @Override
    public List<SimilarTopic> findSimilarTopics(String title, int topK, double threshold) {
        List<Document> hits = topicVectorStore.similaritySearch(SearchRequest.builder()
                .query(title)
                .topK(topK)
                .similarityThreshold(threshold)
                .build());
        List<SimilarTopic> result = new ArrayList<>();
        if (hits == null) {
            return result;
        }
        for (Document doc : hits) {
            Object topicIdObj = doc.getMetadata().get("topicId");
            if (!(topicIdObj instanceof Number n)) {
                continue;  // 脏数据防御：无 topicId 的向量无法回查，跳过
            }
            double score = 1.0;
            Object distance = doc.getMetadata().get("distance");
            if (distance instanceof Number d) {
                score = 1.0 - d.doubleValue();  // pgvector 余弦距离：0=完全相同
            }
            result.add(new SimilarTopic(n.longValue(), doc.getText(), score));
        }
        return result;
    }
}
