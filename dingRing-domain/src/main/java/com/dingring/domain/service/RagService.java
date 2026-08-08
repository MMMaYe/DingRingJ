package com.dingring.domain.service;

/**
 * RAG 知识检索服务端口（依赖倒置，infrastructure 层实现）。
 * <p>检索流程：向量召回 Top-20 + LLM 重排 Top-5。
 * <p>容错：任何环节失败不阻塞群聊主流程，返回空字符串。
 */
public interface RagService {

    /**
     * 检索与当前对话相关的知识片段。
     * <p>双层过滤：全局知识(scope=GLOBAL) + 群专属知识(scope=GROUP AND groupId匹配)。
     *
     * @param query   检索文本（主题标题 + 近期消息拼接）
     * @param groupId 群 ID（用于群专属知识过滤）
     * @return 格式化的知识文本段，无结果返回空字符串
     */
    String retrieve(String query, Long groupId);
}
