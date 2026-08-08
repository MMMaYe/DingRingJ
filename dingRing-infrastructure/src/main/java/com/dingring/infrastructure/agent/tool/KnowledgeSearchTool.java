package com.dingring.infrastructure.agent.tool;

import com.dingring.domain.service.RagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 知识库检索工具（Phase E 实现）。
 * <p>Agent 讨论时可调用此工具检索 RAG 知识库，获取与查询相关的知识段落。
 * <p>检索流程：向量召回 Top-20 + LLM 重排 Top-5，返回格式化文本。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "dingring.rag.enabled", havingValue = "true", matchIfMissing = true)
public class KnowledgeSearchTool {

    private final RagService ragService;

    /**
     * 搜索知识库。
     * <p>调用 RagService.retrieve(query, groupId) 返回相关知识段落。
     *
     * @param query   搜索查询关键词
     * @param groupId 群组 ID（用于群专属知识过滤）
     * @return 知识段落文本，无结果时返回提示语
     */
    @Tool(description = "搜索知识库，返回与查询相关的知识段落，用于讨论时引用权威资料")
    public String searchKnowledge(
            @ToolParam(description = "搜索查询关键词") String query,
            @ToolParam(description = "群组 ID，用于过滤群专属知识") Long groupId) {
        log.debug("KnowledgeSearchTool 调用: query={} groupId={}", query, groupId);
        String result = ragService.retrieve(query, groupId);
        if (result == null || result.isBlank()) {
            return "未检索到相关知识。请基于你自身知识回答。";
        }
        return result;
    }
}
