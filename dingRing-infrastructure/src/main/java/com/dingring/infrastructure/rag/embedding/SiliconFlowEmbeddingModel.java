package com.dingring.infrastructure.rag.embedding;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.dingring.common.util.LogHelper;
import com.dingring.infrastructure.rag.config.RagProperties;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SiliconFlow Embedding 客户端（P2，Qwen3-Embedding-0.6B / 1024 维）。
 * <p>继承 AbstractEmbeddingModel（与官方 OpenAiEmbeddingModel 同姿势）：
 * 仅实现 call + embed(Document)，embed(String)/embed(List) 由接口 default 统一路由到 call。
 * <p>关键设计：
 * <ul>
 *   <li>base-url 是完整端点，直接 POST 不拼路径（配置所见即所得）</li>
 *   <li>批量 input 分批调用，单批 batchSize 条，降低 429 概率</li>
 *   <li>429/503 指数退避重试（最多 3 次），其余错误立即抛出由摄入管道置 FAILED</li>
 *   <li>dimensions() 返回配置值：PgVectorStore 启动建表会调用它，绝不能真实打 API</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "dingring.rag.embedding.provider", havingValue = "siliconflow")
public class SiliconFlowEmbeddingModel extends AbstractEmbeddingModel {

    private static final int MAX_RETRIES = 3;

    private final RagProperties.Embedding.Siliconflow props;
    private final RestClient restClient;

    public SiliconFlowEmbeddingModel(RagProperties props, RestClient.Builder restClientBuilder) {
        this.props = props.getEmbedding().getSiliconflow();
        // timeoutSeconds=0 不覆盖 Builder 已有的 requestFactory：
        // requestFactory 是"后设置者胜出"，单测 MockRestServiceServer 依赖预先绑定的 mock 工厂
        if (this.props.getTimeoutSeconds() > 0) {
            org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                    new org.springframework.http.client.SimpleClientHttpRequestFactory();
            // 公网 API 超时兜底：连接与读取分开设置，避免慢响应拖死摄入线程
            int timeoutMs = (int) Duration.ofSeconds(this.props.getTimeoutSeconds()).toMillis();
            factory.setConnectTimeout(timeoutMs);
            factory.setReadTimeout(timeoutMs);
            restClientBuilder.requestFactory(factory);
        }
        this.restClient = restClientBuilder.build();
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> instructions = request.getInstructions();
        int batchSize = Math.max(1, props.getBatchSize());

        List<org.springframework.ai.embedding.Embedding> all = new ArrayList<>();
        for (int i = 0; i < instructions.size(); i += batchSize) {
            List<String> batch = instructions.subList(i, Math.min(i + batchSize, instructions.size()));
            List<float[]> vectors = callBatchWithRetry(batch);
            for (int j = 0; j < vectors.size(); j++) {
                all.add(new org.springframework.ai.embedding.Embedding(vectors.get(j), i + j));
            }
        }
        return new EmbeddingResponse(all);
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }

    @Override
    public int dimensions() {
        // 启动期 PgVectorStore 建表调用；接口 default 实现会真实打一次 API，必须覆盖
        return props.getDimensions();
    }

    /** 单批调用：429/503 指数退避重试，其余状态码立即抛出 */
    private List<float[]> callBatchWithRetry(List<String> batch) {
        RuntimeException last = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                return callBatch(batch);
            } catch (RetryableHttpException e) {
                last = e;
                long backoff = props.getRetryBackoffBaseMs() * (1L << attempt);
                LogHelper.printWarnLog(SiliconFlowEmbeddingModel.class, "callBatchWithRetry",
                        "SF_EMBEDDING", "限流退避重试",
                        "attempt={}/{} batch={} backoffMs={}", attempt + 1, MAX_RETRIES + 1, batch.size(), backoff);
                sleepQuietly(backoff);
            }
        }
        throw last != null ? last : new IllegalStateException("embedding 调用失败");
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private List<float[]> callBatch(List<String> batch) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", props.getModel());
        body.put("input", batch);
        body.put("dimensions", props.getDimensions());

        String responseBody;
        try {
            responseBody = restClient.post()
                    .uri(props.getBaseUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + props.getApiKey())
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            // 429/503 是限流/过载，可退避重试；其余 4xx（如 401 key 失效）立即抛出
            if (e.getStatusCode().value() == 429 || e.getStatusCode().value() == 503) {
                throw new RetryableHttpException("HTTP " + e.getStatusCode().value());
            }
            throw e;
        }

        JSONObject json = JSONObject.parseObject(responseBody);
        // 空 body 时 parseObject 返回 null，一并纳入下方快速失败分支（统一错误出口）
        JSONArray data = json == null ? null : json.getJSONArray("data");
        if (data == null || data.size() != batch.size()) {
            // 畸形/短返回会给上游留下 NPE 或难定位的条数不匹配，这里快速失败并携带期望条数
            throw new IllegalStateException(
                    "SiliconFlow 响应缺少 data 或条数不匹配: expected=" + batch.size()
                            + " actual=" + (data == null ? "null" : data.size()));
        }
        List<float[]> result = new ArrayList<>(data.size());
        for (int i = 0; i < data.size(); i++) {
            JSONArray arr = data.getJSONObject(i).getJSONArray("embedding");
            float[] v = new float[arr.size()];
            for (int j = 0; j < arr.size(); j++) {
                v[j] = arr.getFloatValue(j);
            }
            result.add(v);
        }
        return result;
    }

    /** 可重试状态码标记（仅 429/503） */
    private static class RetryableHttpException extends RuntimeException {
        RetryableHttpException(String message) {
            super(message);
        }
    }
}
