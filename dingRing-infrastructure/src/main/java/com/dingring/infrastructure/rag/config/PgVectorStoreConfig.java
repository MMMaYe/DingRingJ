package com.dingring.infrastructure.rag.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PgVectorStore 双实例配置（P2）。
 * <p>kb_store：知识库文档切片向量；topic_id_store：话题标题向量（语义回溯）。
 * 两者共用 vectorJdbcTemplate 与同一 EmbeddingModel（Qwen3-0.6B/1024 维）。
 * <p>kbVectorStore 标 @Primary：按类型注入 VectorStore 的既有代码
 * （SaaRagService / DocumentIngestionPipeline 等）无需改动即指向 kb 库。
 * <p>initialize-schema=true：首次启动自动建表 + HNSW 索引（vectorTableName 各自独立）。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "dingring.rag.enabled", havingValue = "true", matchIfMissing = true)
public class PgVectorStoreConfig {

    @Bean
    @Primary
    public PgVectorStore kbVectorStore(
            @Qualifier("vectorJdbcTemplate") JdbcTemplate jdbcTemplate,
            EmbeddingModel embeddingModel) {
        return build("kb_store", jdbcTemplate, embeddingModel);
    }

    @Bean
    public PgVectorStore topicVectorStore(
            @Qualifier("vectorJdbcTemplate") JdbcTemplate jdbcTemplate,
            EmbeddingModel embeddingModel) {
        return build("topic_id_store", jdbcTemplate, embeddingModel);
    }

    private PgVectorStore build(String tableName, JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        log.info("PgVectorStore 初始化: table={} dimensions={} distance-type=COSINE",
                tableName, embeddingModel.dimensions());
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .vectorTableName(tableName)
                .dimensions(embeddingModel.dimensions())
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .initializeSchema(true)
                .build();
    }
}
