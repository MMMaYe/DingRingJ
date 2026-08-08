package com.dingring.infrastructure.rag.config;

import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * PostgreSQL 向量库第二数据源配置（Phase E）。
 * <p>MySQL 是主数据源（@Primary），PostgreSQL 仅用于 PgVectorStore 向量检索。
 * <p>MyBatis 零改动，仍用 MySQL 主数据源。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "dingring.rag.enabled", havingValue = "true", matchIfMissing = true)
public class VectorDataSourceConfig {

    /**
     * PostgreSQL 向量库 DataSource（独立连接池，与 MySQL 主库隔离）。
     * <p>配置前缀 spring.ai.vectorstore.pgvector.datasource（url/username/password/driver-class-name）
     */
    @Bean("vectorDataSource")
    public DataSource vectorDataSource(
            @Qualifier("vectorDataSourceProperties") VectorDataSourceProperties props) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(props.getUrl());
        ds.setUsername(props.getUsername());
        ds.setPassword(props.getPassword());
        ds.setDriverClassName(props.getDriverClassName());
        ds.setPoolName("DingRingPgVectorPool");
        // 公网 PostgreSQL：短寿命 + 借前探活，与 MySQL 主库配置策略一致
        ds.setMaximumPoolSize(10);
        ds.setMinimumIdle(2);
        ds.setConnectionTimeout(8000);
        ds.setMaxLifetime(60000);
        ds.setIdleTimeout(60000);
        ds.setConnectionTestQuery("SELECT 1");
        log.info("PostgreSQL 向量库数据源初始化: url={}", props.getUrl());
        return ds;
    }

    /** 向量库 JdbcTemplate（注入 PgVectorStore） */
    @Bean("vectorJdbcTemplate")
    public JdbcTemplate vectorJdbcTemplate(@Qualifier("vectorDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /** 向量库连接属性（从 application.yml 读取） */
    @Bean("vectorDataSourceProperties")
    @ConfigurationProperties(prefix = "spring.ai.vectorstore.pgvector.datasource")
    public VectorDataSourceProperties vectorDataSourceProperties() {
        return new VectorDataSourceProperties();
    }
}
