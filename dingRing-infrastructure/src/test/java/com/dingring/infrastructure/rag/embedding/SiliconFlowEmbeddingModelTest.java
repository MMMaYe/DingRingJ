package com.dingring.infrastructure.rag.embedding;

import com.dingring.infrastructure.rag.config.RagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * {@link SiliconFlowEmbeddingModel} 单测。
 * <p>用 MockRestServiceServer 绑定 RestClient.Builder，验证请求体/分批/重试/异常，零真实 API 调用。
 */
@DisplayName("SiliconFlowEmbeddingModel 真实 Embedding 客户端")
class SiliconFlowEmbeddingModelTest {

    private RagProperties props;
    private RestClient.Builder builder;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        props = new RagProperties();
        RagProperties.Embedding.Siliconflow sf = props.getEmbedding().getSiliconflow();
        sf.setRetryBackoffBaseMs(1);  // 单测退避几乎为 0，提速
        // 0=不覆盖 requestFactory：实现按"后设置者胜出"替换工厂，会顶掉 MockRestServiceServer 的 mock 绑定
        sf.setTimeoutSeconds(0);
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
    }

    private String embedResp(int count) {
        StringBuilder sb = new StringBuilder("{\"data\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"embedding\":[0.1,0.2],\"index\":").append(i).append("}");
        }
        return sb.append("]}").toString();
    }

    @Test
    @DisplayName("call 请求体携带 model/input/dimensions 并解析向量")
    void shouldSendCorrectRequestBodyAndParseVectors() {
        server.expect(requestTo("https://api.siliconflow.cn/v1/embeddings"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.model").value("Qwen/Qwen3-Embedding-0.6B"))
                .andExpect(jsonPath("$.dimensions").value(1024))
                .andExpect(jsonPath("$.input[0]").value("你好"))
                .andRespond(withSuccess(embedResp(1), MediaType.APPLICATION_JSON));

        SiliconFlowEmbeddingModel model = new SiliconFlowEmbeddingModel(props, builder);
        List<float[]> result = model.embed(List.of("你好"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsExactly(0.1f, 0.2f);
        server.verify();
    }

    @Test
    @DisplayName("批量输入超过 batch-size 时自动分批多次请求")
    void shouldSplitInputIntoBatches() {
        props.getEmbedding().getSiliconflow().setBatchSize(2);
        server.expect(requestTo("https://api.siliconflow.cn/v1/embeddings"))
                .andExpect(jsonPath("$.input.length()").value(2))
                .andRespond(withSuccess(embedResp(2), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.siliconflow.cn/v1/embeddings"))
                .andExpect(jsonPath("$.input.length()").value(1))
                .andRespond(withSuccess(embedResp(1), MediaType.APPLICATION_JSON));

        SiliconFlowEmbeddingModel model = new SiliconFlowEmbeddingModel(props, builder);
        List<float[]> result = model.embed(List.of("a", "b", "c"));

        assertThat(result).hasSize(3);
        server.verify();
    }

    @Test
    @DisplayName("429 响应按指数退避重试，重试成功不抛异常")
    void shouldRetryOn429() {
        server.expect(requestTo("https://api.siliconflow.cn/v1/embeddings"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo("https://api.siliconflow.cn/v1/embeddings"))
                .andRespond(withSuccess(embedResp(1), MediaType.APPLICATION_JSON));

        SiliconFlowEmbeddingModel model = new SiliconFlowEmbeddingModel(props, builder);
        List<float[]> result = model.embed(List.of("x"));

        assertThat(result).hasSize(1);
        server.verify();
    }

    @Test
    @DisplayName("非 429/503 错误不重试直接抛出（由摄入管道置 FAILED）")
    void shouldNotRetryOnBadRequest() {
        server.expect(requestTo("https://api.siliconflow.cn/v1/embeddings"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.BAD_REQUEST));

        SiliconFlowEmbeddingModel model = new SiliconFlowEmbeddingModel(props, builder);
        assertThatThrownBy(() -> model.embed(List.of("x")))
                .isInstanceOf(Exception.class);
        server.verify();  // 恰好 1 次请求，未重试
    }

    @Test
    @DisplayName("dimensions 返回配置值且不发起任何 HTTP 请求（启动建表零 API 消耗）")
    void shouldReturnConfiguredDimensionsWithoutHttpCall() {
        SiliconFlowEmbeddingModel model = new SiliconFlowEmbeddingModel(props, builder);
        assertThat(model.dimensions()).isEqualTo(1024);
        // 未注册任何期望：若发起请求 MockRestServiceServer 会因无匹配期望而失败
        server.verify();
    }
}
