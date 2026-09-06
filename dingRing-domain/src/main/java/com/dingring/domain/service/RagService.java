package com.dingring.domain.service;

import java.util.List;

/**
 * RAG 知识检索服务端口（依赖倒置，infrastructure 层实现）。
 * <p>检索流程：向量召回 Top-20 + LLM 重排 Top-5。
 * <p>过滤口径：仅检索群绑定的知识库（chat_group.knowledge_base_config.kbIds），
 * 未绑定任何库的群不注入知识。
 * <p>容错：任何环节失败不阻塞群聊主流程，返回空字符串。
 */
public interface RagService {

    /**
     * 检索与当前对话相关的知识片段。
     *
     * @param query 检索文本（主题标题 + 近期消息拼接）
     * @param kbIds 允许检索的知识库 ID 列表（向量 metadata.kbId IN 过滤；
     *              null 或空列表直接返回空字符串）
     * @return 格式化的知识文本段，无结果返回空字符串
     */
    String retrieve(String query, List<Long> kbIds);

    default RetrievalResult retrieveStructured(String query, List<Long> kbIds) {
        return RetrievalResult.empty(query);
    }

    /**
     * P3 多阶段检索（7.3）：dense + lexical → RRF 融合 → 邻居扩展 → rerank → MMR。
     * <p>带 groupId 维度：Redis 目录缓存按群隔离（同群同 query 复用，跨群不串）。
     *
     * @param query   原始检索文本（规范化与改写在实现内完成）
     * @param kbIds   允许检索的知识库 ID 列表
     * @param groupId 群 ID（缓存隔离维度；可空表示无群上下文，跳过目录缓存）
     * @return 结构化检索结果（含规范化后的 query 与候选列表）；无结果返回空候选
     */
    default RetrievalResult retrieveStructured(String query, List<Long> kbIds, Long groupId) {
        return RetrievalResult.empty(query);
    }
}
