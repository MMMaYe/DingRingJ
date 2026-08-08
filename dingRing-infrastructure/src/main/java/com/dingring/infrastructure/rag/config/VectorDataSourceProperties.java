package com.dingring.infrastructure.rag.config;

import lombok.Data;

/**
 * PostgreSQL 向量库连接属性。
 * <p>配置前缀：spring.ai.vectorstore.pgvector.datasource
 */
@Data
public class VectorDataSourceProperties {

    private String url;
    private String username;
    private String password;
    private String driverClassName;
}
