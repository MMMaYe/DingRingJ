package com.dingring.infrastructure.rag.retrieval;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.dingring.common.util.LogHelper;
import com.dingring.domain.service.Reranker;
import com.dingring.domain.service.RetrievalCandidate;
import com.dingring.infrastructure.rag.config.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SiliconFlow Rerank 客户端（7.4，Qwen/Qwen3-Reranker-4B）。
 * <p>专门化 rerank 模型替代 LLM 打分：输出连续 relevance_score（0-1），
 * 无需解析自由文本 JSON，延迟与成本都低于通用 LLM。
 * <p>请求体：model + query + documents[] + instruction + top_n；
 * 响应 results[] 携带 index 与 relevance_score，meta 记录 token 用量。
 * <p>documents 用「[来源: fileName | 章节: headingPath]\ncontent」格式（7.4），
 * 让 reranker 在相关性判断中利用溯源信息。
 * <p>容错：429/503 指数退避重试（2 次退避）；失败返回 null，
 * 由调用方（SaaRagService）回退 RRF 顺序——检索绝不能因 rerank 挂掉而失败。
 * <p>复用 embedding 的 apiKey/baseUrl（同一 SiliconFlow 账号体系），
 * 端点 /v1/rerank 与 /v1/embeddings 并列。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "dingring.rag.reranker.provider", havingValue = "siliconflow")
public class SiliconFlowReranker implements Reranker {

    /** 重排指令（7.4）：与通用 LLM 打分的角色设定对齐 */
    private static final String INSTRUCTION = "根据用户问题对知识库候选片段按相关性排序";

    private static final int MAX_BACKOFF_RETRIES = 2;

    private final RagProperties ragProperties;
    private final RestClient restClient;

    public SiliconFlowReranker(RagProperties ragProperties, RestClient.Builder restClientBuilder) {
        this.ragProperties = ragProperties;
        // 不覆盖超时：复用 Spring Boot 自动配置默认值，rerank 响应短（仅 index+score）
        this.restClient = restClientBuilder.build();
    }

    /**
     * legacy 单阶段链路不支持：本实现只服务 P3 多阶段检索（rerankStructured）。
     * 返回原始顺序（默认分 0.5），旧链路应继续使用 LlmReranker。
     */
    @Override
    public List<ScoredDocument> rerank(String query, List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        return candidates.stream().map(c -> new ScoredDocument(c, 0.5)).toList();
    }

    @Override
    public RerankOutcome rerankStructured(String query, List<RetrievalCandidate> candidates) {
        if (query == null || query.isBlank() || candidates == null || candidates.isEmpty()) {
            return null;
        }
        try {
            return callWithBackoff(query, candidates);
        } catch (Exception e) {
            LogHelper.printWarnLog(SiliconFlowReranker.class, "rerankStructured", "RAG_RERANK",
                    "rerank 调用失败（回退 RRF 顺序）", "候选数={} 错误: {}", candidates.size(), e.getMessage());
            return null;
        }
    }

    private RerankOutcome callWithBackoff(String query, List<RetrievalCandidate> candidates) {
        long backoffBase = 500;
        for (int attempt = 0; attempt <= MAX_BACKOFF_RETRIES; attempt++) {
            try {
                return doCall(query, candidates);
            } catch (RestClientResponseException e) {
                if (e.getStatusCode().value() == 429 || e.getStatusCode().value() == 503) {
                    long backoff = backoffBase * (1L << attempt);
                    LogHelper.printWarnLog(SiliconFlowReranker.class, "callWithBackoff", "RAG_RERANK",
                            "限流退避重试", "attempt={}/{} backoffMs={}",
                            attempt + 1, MAX_BACKOFF_RETRIES + 1, backoff);
                    sleepQuietly(backoff);
                    continue;
                }
                throw e;
            }
        }
        throw new IllegalStateException("rerank 重试耗尽");
    }

    private RerankOutcome doCall(String query, List<RetrievalCandidate> candidates) {
        RagProperties.Embedding.Siliconflow sf = ragProperties.getEmbedding().getSiliconflow();
        String endpoint = rerankEndpoint(sf.getBaseUrl());

        List<String> documents = new ArrayList<>(candidates.size());
        for (RetrievalCandidate c : candidates) {
            documents.add(formatDocument(c));
        }
        Map<String, Object> body = new HashMap<>();
        body.put("model", "Qwen/Qwen3-Reranker-4B");
        body.put("query", query);
        body.put("documents", documents);
        body.put("instruction", INSTRUCTION);
        body.put("top_n", candidates.size());
        body.put("return_documents", false);

        String responseBody = restClient.post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + sf.getApiKey())
                .body(body)
                .retrieve()
                .body(String.class);

        return parse(responseBody, candidates);
    }

    /** /v1/embeddings → /v1/rerank：同一账号体系下的并列端点 */
    static String rerankEndpoint(String embeddingBaseUrl) {
        if (embeddingBaseUrl != null && embeddingBaseUrl.endsWith("/embeddings")) {
            return embeddingBaseUrl.substring(0, embeddingBaseUrl.length() - "/embeddings".length()) + "/rerank";
        }
        return "https://api.siliconflow.cn/v1/rerank";
    }

    /** 候选格式化（7.4）：来源+章节前置，全文给足上下文（rerank 模型对长度不敏感） */
    private static String formatDocument(RetrievalCandidate c) {
        String section = String.join(" > ", c.headingPath());
        StringBuilder sb = new StringBuilder("[来源: ");
        sb.append(c.fileName() == null ? "" : c.fileName());
        if (!section.isBlank()) {
            sb.append(" | 章节: ").append(section);
        }
        sb.append("]\n").append(c.content());
        return sb.toString();
    }

    private RerankOutcome parse(String responseBody, List<RetrievalCandidate> candidates) {
        JSONObject json = JSONObject.parseObject(responseBody);
        JSONArray results = json == null ? null : json.getJSONArray("results");
        if (results == null || results.isEmpty()) {
            throw new IllegalStateException("rerank 响应缺少 results");
        }
        List<RetrievalCandidate> ranked = new ArrayList<>(results.size());
        for (int i = 0; i < results.size(); i++) {
            JSONObject item = results.getJSONObject(i);
            int index = item.getIntValue("index");
            double score = item.getDoubleValue("relevance_score");
            if (index >= 0 && index < candidates.size()) {
                RetrievalCandidate origin = candidates.get(index);
                ranked.add(new RetrievalCandidate(origin.candidateId(), origin.chunkId(),
                        origin.content(), origin.fileName(), origin.headingPath(),
                        origin.sourceLocation(), score, origin.metadata()));
            }
        }
        ranked.sort((a, b) -> Double.compare(b.score(), a.score()));

        long tokensIn = 0;
        long tokensOut = 0;
        if (json != null) {
            JSONObject meta = json.getJSONObject("meta");
            if (meta != null && meta.getJSONObject("tokens") != null) {
                tokensIn = meta.getJSONObject("tokens").getLongValue("input_tokens");
                tokensOut = meta.getJSONObject("tokens").getLongValue("output_tokens");
            }
        }
        LogHelper.printLog(SiliconFlowReranker.class, "parse", "RAG_RERANK",
                "rerank 完成", "候选数={} 重排数={} 最高分={} tokensIn={} tokensOut={}",
                candidates.size(), ranked.size(),
                ranked.isEmpty() ? 0 : ranked.get(0).score(), tokensIn, tokensOut);
        return new RerankOutcome(ranked, tokensIn, tokensOut);
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
