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
    private final Retrieval retrieval = new Retrieval();
    private final Reconciliation reconciliation = new Reconciliation();

    /** P3 多阶段检索配置（7.3/7.4/7.7） */
    @Data
    public static class Retrieval {
        /** dense 通道（pgvector HNSW）召回数 */
        private int denseTopK = 30;
        /** lexical 通道（pg_trgm word_similarity）召回数 */
        private int lexicalTopK = 30;
        /** RRF 融合后进入重排的候选上限 */
        private int rrfTopK = 40;
        /** 重排输出候选数（MMR 前的列表长度，即目录条目上限） */
        private int rerankTopK = 15;
        /** RRF 平滑常数 K：score = Σ 1/(K + rank) */
        private int rrfK = 60;
        /** MMR λ：λ*relevance - (1-λ)*max_sim（1=纯相关性，0=纯多样性） */
        private double mmrLambda = 0.7;
        /** relevance_score 过滤阈值：低于此值的候选不进目录 */
        private double minScore = 0.25;
        /** 邻居扩展开关：RRF 融合后补前后兄弟 chunk 增加候选池完整性 */
        private boolean neighborExpansion = true;
        /** query 改写 Agent ID（复用 routeJudge 轻量模型） */
        private Long rewriteAgentId = 6L;
        /** Redis 目录缓存（rag:retrieval:{groupId}:{queryHash}）TTL 秒 */
        private long catalogTtlSeconds = 86400;
        /** Redis chunk 全文缓存（rag:chunk:{chunkId}）TTL 秒 */
        private long chunkTtlSeconds = 172800;
        /** 目录注入 token 上限（SystemMessage 注入的成本控制） */
        private int catalogMaxTokens = 500;
        /** kb_read_in_redis 单次读取 token 预算 */
        private int readBudgetTokens = 4096;
        /** Redis 缓存总开关（无 Redis 环境关闭后检索直查 PG，不降级） */
        private boolean cacheEnabled = true;
    }

    /** P3 F6-B 孤儿向量对账任务（9.2）。间隔由 dingring.rag.reconciliation.interval-ms 绑定 @Scheduled */
    @Data
    public static class Reconciliation {
        /** 对账任务开关（定时清理孤儿向量 + 一致性告警） */
        private boolean enabled = true;
        /** 清洗任务超时告警阈值（分钟）：CLEANING_* 超此时长仅告警不自动降级 */
        private long cleaningWarnThresholdMinutes = 120;
    }

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
        /** 切片策略：fixed=旧字符滑窗（legacy 回退）；md-semantic=结构感知切分 */
        private String strategy = "md-semantic";
        /** Fixed-size 切片大小（字符） */
        private int fixedSize = 512;
        /** 相邻切片重叠（字符），保证边界语义连续 */
        private int overlap = 64;
        /** 结构切分 token 目标区间 */
        private int targetTokens = 600;
        /** 碎片下限：低于该值的块必须合并 */
        private int minTokens = 120;
        /** 超长处理触发线 */
        private int maxTokens = 900;
        /** 硬上限：语法边界拆不动时的最后兜底 */
        private int absoluteMaxTokens = 1800;
        /** Qwen tokenizer 资源路径 */
        private String tokenizerPath = "./models/qwen3-embedding-tokenizer.json";
    }
}
