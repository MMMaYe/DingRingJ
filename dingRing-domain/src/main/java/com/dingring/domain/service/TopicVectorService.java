package com.dingring.domain.service;

import com.dingring.domain.discussion.Topic;

import java.util.List;

/**
 * 话题向量服务端口（P2，依赖倒置，infrastructure 实现）。
 * <p>topic_id_store 表的写入与语义检索：TopicClosed 时写入标题向量，
 * 建题/讨论时按标题语义召回相似历史话题。
 */
public interface TopicVectorService {

    /** 把已关闭话题的标题写入向量库（content=标题，metadata 携带 topicId/groupId/title） */
    void indexTopic(Topic topic);

    /**
     * 语义检索相似历史话题。
     *
     * @param title     查询标题（建题标题/当前话题标题）
     * @param topK      返回条数上限
     * @param threshold 相似度阈值（1-distance，低于不返回）
     * @return 按 score 降序的相似话题（含自身则由调用方排除）
     */
    List<SimilarTopic> findSimilarTopics(String title, int topK, double threshold);

    /** 相似话题结果（score = 1 - 余弦距离） */
    record SimilarTopic(Long topicId, String title, double score) {}
}
