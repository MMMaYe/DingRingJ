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
        // 注意单位陷阱：pgjdbc 的 connectTimeout/socketTimeout/loginTimeout 单位是「秒」，
        // 而 MySQL Connector/J 是毫秒。写 10000 会变成 10000 秒，僵死连接将占住池槽位数小时。
        // 这里统一 10 秒：建连/读/登录任一环节卡死都能快速失败，交给 Hikari 重试。
        String url = props.getUrl();
        if (!url.contains("socketTimeout")) {
            url += (url.contains("?") ? "&" : "?")
                    + "socketTimeout=10&connectTimeout=10&loginTimeout=10";
        }
        ds.setJdbcUrl(url);
        ds.setUsername(props.getUsername());
        ds.setPassword(props.getPassword());
        ds.setDriverClassName(props.getDriverClassName());
        ds.setPoolName("DingRingPgVectorPool");
        // 公网 PostgreSQL 连接池参数（与 MySQL 主库策略一致）：
        // - initializationFailTimeout=-1：HikariDataSource 构造立即返回，后台异步建连，
        //   规避 PMTUD 黑洞场景下启动期建连超时导致 Bean 创建失败（kbVectorStore 依赖注入卡死）
        // - maxLifetime(120s) > keepaliveTime(30s)：keepalive 才会生效（Hikari 要求 maxLifetime > keepalive）
        // - keepaliveTime=30s：每 30s 主动探活保活连接，对抗公网 NAT 闲置断连
        // - idleTimeout=60s：闲置 60s 后回收至 minimumIdle
        // - connectionTimeout=30s：借连接最长等待，给 PMTUD 重传足够时间
        ds.setMaximumPoolSize(10);
        ds.setMinimumIdle(2);
        ds.setConnectionTimeout(30000);
        ds.setInitializationFailTimeout(-1);
        ds.setMaxLifetime(120000);
        ds.setKeepaliveTime(30000);
        ds.setIdleTimeout(60000);
        ds.setConnectionTestQuery("SELECT 1");
        ds.setLeakDetectionThreshold(10000);
        log.info("PostgreSQL 向量库数据源初始化: url={}, pool=DingRingPgVectorPool, maxLifetime=120s, keepalive=30s, initFailTimeout=-1", url);
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
