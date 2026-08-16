package com.dingring.infrastructure.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 配置属性（P2）。
 * <p>统一承载 dingring.rag.* 配置；既有 @Value 读取点（FileStorageService/LlmReranker）不动，
 * 仅新增代码（Embedding/Splitter）从这里取值，避免一次性大迁移。
 * <p>嵌套对象用 final 字段 + getter 暴露：Spring Boot 按 getter 绑定嵌套节点，叶子字段可 set。
 */
@Data
@Component
@ConfigurationProperties(prefix = "dingring.rag")
public class RagProperties {

    private boolean enabled = true;

    private final Embedding embedding = new Embedding();
    private final Chunk chunk = new Chunk();

    @Data
    public static class Embedding {
        /** mock=本地确定性向量（单测/联调）；siliconflow=真实 API */
        private String provider = "mock";
        private final Siliconflow siliconflow = new Siliconflow();

        @Data
        public static class Siliconflow {
            /** 完整端点 URL，代码直接 POST，不拼接路径（配置即所见即所得） */
            private String baseUrl = "https://api.siliconflow.cn/v1/embeddings";
            private String apiKey = "";
            private String model = "Qwen/Qwen3-Embedding-0.6B";
            /** Qwen3-0.6B 原生 1024 维；dimensions() 直接返回此值，启动零 API 调用 */
            private int dimensions = 1024;
            /** 批量请求单批条数（一次 HTTP 打包多条 input，降低 429 概率） */
            private int batchSize = 16;
            private int timeoutSeconds = 30;
            /** 重试退避基数（ms），指数退避 = base * 2^attempt；暴露为配置便于单测提速 */
            private long retryBackoffBaseMs = 500;
        }
    }

    @Data
    public static class Chunk {
        /** Fixed-size 切片大小（字符） */
        private int fixedSize = 512;
        /** 相邻切片重叠（字符），保证边界语义连续 */
        private int overlap = 64;
    }
}
