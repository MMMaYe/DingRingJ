package com.dingring.infrastructure.rag.config;

import com.alibaba.cloud.ai.parser.tika.TikaDocumentParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 摄入组件装配（P2）。
 * <p>TikaDocumentParser 用 @Bean 而非组件扫描：它是无状态解析器，统一在此处实例化
 * 便于 DocumentIngestionPipeline 构造注入（单测可 mock）。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "dingring.rag.enabled", havingValue = "true", matchIfMissing = true)
public class RagIngestionConfig {

    @Bean
    public TikaDocumentParser tikaDocumentParser() {
        log.info("SAA TikaDocumentParser 初始化（替换 spring-ai TikaDocumentReader）");
        return new TikaDocumentParser();
    }
}
