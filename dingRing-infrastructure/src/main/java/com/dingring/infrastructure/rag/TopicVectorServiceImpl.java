package com.dingring.infrastructure.rag;

import com.dingring.common.util.LogHelper;
import com.dingring.domain.discussion.Topic;
import com.dingring.domain.service.TopicVectorService;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * <p>幂等策略：写入前按 topicId 先删旧向量（delete-then-write），
 * 防止事件重放/话题重复关闭累积重复条目挤占 topK 名额。
 */
@Component
@ConditionalOnProperty(name = "dingring.rag.enabled", havingValue = "true", matchIfMissing = true)
public class TopicVectorServiceImpl implements TopicVectorService {

    private final VectorStore topicVectorStore;
    private final JdbcTemplate vectorJdbcTemplate;

    public TopicVectorServiceImpl(@Qualifier("topicVectorStore") VectorStore topicVectorStore,
                                  @Qualifier("vectorJdbcTemplate") JdbcTemplate vectorJdbcTemplate) {
        this.topicVectorStore = topicVectorStore;
        this.vectorJdbcTemplate = vectorJdbcTemplate;
    }

    @Override
    public void indexTopic(Topic topic) {
        // 幂等补强：按 topicId 先删旧向量再写入，防止事件重放/重复关闭累积重复条目
        // （重复条目会挤占 topK 名额，同一话题反复出现在相似结果中）
        try {
            vectorJdbcTemplate.update("DELETE FROM topic_id_store WHERE metadata->>'topicId' = ?",
                    String.valueOf(topic.getId()));
        } catch (Exception e) {
            // 删除失败不阻断写入：最坏情况是重复条目（与补强前行为一致），优于丢失话题向量
            LogHelper.printWarnLog(TopicVectorServiceImpl.class, "indexTopic", "TOPIC_VECTOR",
                    "清理旧向量失败继续写入", "topicId={} 错误: {}", topic.getId(), e.getMessage());
        }
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
