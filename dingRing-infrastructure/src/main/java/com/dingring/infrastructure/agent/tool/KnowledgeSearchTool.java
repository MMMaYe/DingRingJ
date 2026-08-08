package com.dingring.infrastructure.agent.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 知识库检索工具（Phase D 占位，Phase E 实现）。
 * <p>Agent 讨论时可调用此工具检索 RAG 知识库，获取与查询相关的知识段落。
 * <p>Phase E 将注入 RagService 实现真正的向量检索 + 重排：
 * <ol>
 *   <li>EmbeddingModel 将 query 向量化</li>
 *   <li>PgVectorStore 召回 Top-20 相关文档</li>
 *   <li>LlmReranker 重排 Top-5 返回</li>
 * </ol>
 * <p>当前为占位实现，返回提示语告知 Agent 知识库尚未启用。
 */
@Slf4j
@Component
public class KnowledgeSearchTool {

    /**
     * 搜索知识库。
     * <p>Phase E 实现后将调用 RagService.retrieve(query, groupId) 返回相关知识段落。
     *
     * @param query   搜索查询
     * @param groupId 群组 ID（用于隔离不同群的知识库）
     * @return 知识段落（Phase D 占位返回提示语）
     */
    @Tool(description = "搜索知识库，返回与查询相关的知识段落，用于讨论时引用权威资料")
    public String searchKnowledge(
            @ToolParam(description = "搜索查询关键词") String query,
            @ToolParam(description = "群组 ID") Long groupId) {
        log.debug("KnowledgeSearchTool 占位调用: query={} groupId={}", query, groupId);
        // Phase E 将注入 RagService 并实现真正的向量检索
        return "知识库功能将在 Phase E 启用，当前暂不可用。请基于你自身知识回答。";
    }
}
