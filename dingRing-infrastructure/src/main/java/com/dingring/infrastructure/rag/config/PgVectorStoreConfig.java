package com.dingring.infrastructure.rag.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PgVectorStore Bean 配置（Phase E）。
 * <p>手动创建 PgVectorStore，注入 PostgreSQL JdbcTemplate + EmbeddingModel。
 * <p>排除 Spring AI 的 PgVectorStoreAutoConfiguration（因为要用第二数据源，不是主 MySQL DataSource）。
 * <p>initialize-schema=true：首次启动时自动创建 vector_store 表 + HNSW 索引。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "dingring.rag.enabled", havingValue = "true", matchIfMissing = true)
public class PgVectorStoreConfig {

    /**
     * PgVectorStore Bean。
     * <p>distance-type=COSINE_DISTANCE（余弦相似度，适合文本语义检索）
     * <p>index-type=HNSW（近似最近邻索引，检索性能好）
     */
    @Bean
    public PgVectorStore pgVectorStore(
            @Qualifier("vectorJdbcTemplate") JdbcTemplate jdbcTemplate,
            EmbeddingModel embeddingModel) {
        log.info("PgVectorStore 初始化: dimensions={} distance-type=COSINE", embeddingModel.dimensions());
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .dimensions(embeddingModel.dimensions())
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .initializeSchema(true)
                .build();
    }
}
