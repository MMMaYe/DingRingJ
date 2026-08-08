package com.dingring.infrastructure.persistence.config;

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * 主数据源配置（修复 Phase E 回归）。
 * <p>背景：RAG 引入 {@code vectorDataSource}（PostgreSQL 第二数据源）后，
 * Spring Boot 的 {@code DataSourceAutoConfiguration} 因「用户已自定义 DataSource」整体回退，
 * 主 MySQL 数据源不再自动创建，导致 MyBatis 全部 Mapper 绑定到 PostgreSQL 向量库
 * （表现为业务表报 {@code relation "xxx" does not exist}）。
 * <p>修复：显式声明 {@code @Primary} 主数据源，复用 {@link DataSourceProperties}
 * 绑定 {@code spring.datasource.*}（含嵌套 hikari 连接池配置，行为与自动配置完全一致）；
 * vectorDataSource 保持非 Primary，MyBatis 按 @Primary 取主库。
 */
@Configuration
public class PrimaryDataSourceConfig {

    /**
     * MySQL 主数据源（@Primary）。
     *
     * @param properties spring.datasource 绑定属性（含 hikari 嵌套池配置）
     * @return Hikari 主数据源
     */
    @Bean
    @Primary
    public DataSource primaryDataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().build();
    }
}
