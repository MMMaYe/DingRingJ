package com.dingring.infrastructure.rag.config;

import com.alibaba.cloud.ai.parser.markdown.MarkdownDocumentParser;
import com.alibaba.cloud.ai.parser.markdown.config.MarkdownDocumentParserConfig;
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

    /**
     * md 专用解析器：commonmark AST 按标题/代码块/引用块结构切分，
     * 保留 category（header_N/code_block 等）与 lang 元数据供检索侧过滤/加权。
     * 默认配置保留代码块与引用块 —— 群知识库文档结构语义最大化。
     */
    @Bean
    public MarkdownDocumentParser markdownDocumentParser() {
        log.info("SAA MarkdownDocumentParser 初始化（md 按标题结构切分）");
        return new MarkdownDocumentParser(MarkdownDocumentParserConfig.defaultConfig());
    }
}
