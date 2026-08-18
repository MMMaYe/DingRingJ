# 计划：RAG 摄入管道接入 SAA MarkdownDocumentParser

## Summary

将 RAG 文档摄入管道从"Tika 纯文本解析 + FixedSize 盲切"升级为**按 fileType 分流**：`.md` 文件走 SAA `MarkdownDocumentParser`（commonmark AST 按标题结构切分，保留 category/lang 元数据），其他格式保留 Tika 兜底。结构切分后的块仍过 `FixedSizeTextSplitter` 做长度兜底（超长章节二次滑窗），形成"结构优先 + 长度兜底"两段式切片。

**不需要升级 SAA 版本**（保持 1.1.2.3）：`MarkdownDocumentParser` 位于独立 starter `spring-ai-alibaba-starter-document-parser-markdown`，BOM 不管理该模块，需显式指定版本（与 nacos-prompt 同模式）。

## Current State Analysis（探索结论）

| 关注点 | 现状 | 位置 |
|---|---|---|
| 上传约束 | 仅接受 `.md/.markdown`（双检：扩展名 + contentType） | `KnowledgeBaseAppService.validateMarkdown()` |
| fileType 字段 | 已有 `TYPE_MARKDOWN`/`TYPE_PDF`/`TYPE_TXT` 分支，入库时写入 | `KnowledgeBaseAppService.detectFileType()` |
| 解析 | `TikaDocumentParser.parse(is)` 把 md 当纯文本，结构全丢 | `DocumentIngestionPipeline.readDocument()` |
| 切片 | FixedSize 512/64 字符滑窗，`len <= chunkSize` 整块返回，`new HashMap<>(doc.getMetadata())` 透传 metadata | `FixedSizeTextSplitter.splitOne()` |
| metadata | 管道写 kbId/fileId/fileName/docType/uploadTime/chunkIndex；parser 将写 category/lang —— **无 key 冲突** | `DocumentIngestionPipeline.buildMetadata()` |
| 检索侧 | 仅按 kbId IN 过滤，不感知 category —— **改造零影响** | `SaaRagService.retrieve()` |
| 依赖 | infrastructure 已有 tika starter + dashscope（1.1.2.3）；本地仓库已有 markdown starter 1.1.2.2 | `dingRing-infrastructure/pom.xml` |
| 测试 | `KnowledgeBaseAppServiceTest`（mock pipeline）、`FixedSizeTextSplitterTest`（纯逻辑）—— 均不触碰真实 parser | 两个测试类 |

**依赖验证事实**（已检查本地 BOM 与 jar）：
- `spring-ai-alibaba-bom:1.1.2.3` 的 dependencyManagement 中**无** markdown starter → 必须显式版本
- starter 1.1.2.2 jar 内确认类 `com.alibaba.cloud.ai.parser.markdown.MarkdownDocumentParser` 与 `config.MarkdownDocumentParserConfig`
- starter 依赖 dashscope（optional，项目已显式引入）+ commonmark（传递引入，无需手动加）

## Proposed Changes

### 1. [dingRing-infrastructure/pom.xml] — 新增 markdown starter 依赖

在 `spring-ai-alibaba-starter-document-parser-tika` 依赖之后新增：

```xml
<!-- SAA Markdown 解析器（P2 演进：md 按标题结构切分，替代 Tika 纯文本提取；
     BOM 1.1.2.3 不管理此模块，显式版本（同 nacos-prompt 模式）） -->
<dependency>
    <groupId>com.alibaba.cloud.ai</groupId>
    <artifactId>spring-ai-alibaba-starter-document-parser-markdown</artifactId>
    <version>${spring-ai-alibaba.version}</version>
</dependency>
```

**Why**：`MarkdownDocumentParser` 不在核心 jar，必须引入此 starter；commonmark 由其传递引入。
**注意**：1.1.2.3 本地无缓存，首次构建会联网下载（远端已发布，上轮 mvnrepository 确认）。

### 2. [RagIngestionConfig.java] — 注册 MarkdownDocumentParser Bean

文件：`dingRing-infrastructure/src/main/java/com/dingring/infrastructure/rag/config/RagIngestionConfig.java`

新增 Bean（保留现有 Tika Bean 不动）：

```java
import com.alibaba.cloud.ai.parser.markdown.MarkdownDocumentParser;
import com.alibaba.cloud.ai.parser.markdown.config.MarkdownDocumentParserConfig;

@Bean
public MarkdownDocumentParser markdownDocumentParser() {
    // 默认配置：保留代码块/引用块、水平分割线切分 —— 群知识库场景结构语义最大化
    log.info("SAA MarkdownDocumentParser 初始化（md 按标题结构切分）");
    return new MarkdownDocumentParser(MarkdownDocumentParserConfig.defaultConfig());
}
```

**Why**：与 Tika Bean 同模式（无状态解析器统一 @Bean 实例化，单测可 mock）。

### 3. [DocumentIngestionPipeline.java] — readDocument 按 fileType 分流

文件：`dingRing-infrastructure/src/main/java/com/dingring/infrastructure/rag/DocumentIngestionPipeline.java`

- 构造器新增 `MarkdownDocumentParser markdownDocumentParser` 参数并保存字段
- `readDocument()` 改造为分流：

```java
/**
 * 按 fileType 分流解析：
 * md → MarkdownDocumentParser（commonmark AST 按标题切分，保留 category/lang metadata）；
 * 其他 → TikaDocumentParser 兜底（纯文本提取）。
 * 解析后的块仍统一过 FixedSizeTextSplitter 长度兜底（超长章节二次滑窗，短块整块保留）。
 */
private List<Document> readDocument(File file) throws IOException {
    try (InputStream is = Files.newInputStream(Paths.get(file.getPath()))) {
        if (File.TYPE_MARKDOWN.equals(file.getFileType())) {
            return markdownDocumentParser.parse(is);
        }
        return tikaDocumentParser.parse(is);
    }
}
```

**Why**：当前上传 md-only，但 `detectFileType` 已预留 PDF/TXT 分支，Tika 保留为未来多格式兜底，删除则扩展时需回补。
**metadata 合并无需改动**：`buildMetadata()` 的 putAll 与 parser 写入的 category/lang 无 key 冲突；`FixedSizeTextSplitter.splitOne()` 已透传原 metadata。

### 4. [FixedSizeTextSplitter.java] — 更新类注释（仅注释，无代码改动）

L18 注释"md 按标题结构切片（SAA MarkdownDocumentParser + Header 切分）列为后续演进，本次严格 Fixed-size"已过时，更新为已落地事实：

```java
 * <p>上游已接入 SAA MarkdownDocumentParser（md 按标题结构切分），
 * 本切片器退化为长度兜底：超长章节（> chunkSize）二次滑窗，短块整块保留并透传 parser metadata。
```

**Why**：遵循"注释反映代码变化"的项目规则。

### 5. [新增单测] DocumentIngestionPipelineTest — 验证分流逻辑

文件：`dingRing-infrastructure/src/test/java/com/dingring/infrastructure/rag/DocumentIngestionPipelineTest.java`

- **不启动 Spring 容器**（pipeline 依赖 VectorStore/FileRepository，直接 mock 四个依赖构造注入）
- 用 `@TempDir` 写临时 md/txt 文件，验证：
  - `fileType=md` → `markdownDocumentParser.parse` 被调用、`tikaDocumentParser.parse` 不调用
  - `fileType=txt/pdf` → 走 Tika 分支
  - 摄入成功后 file status 变为 READY（mock vectorStore.add / fileRepository.update）
- **说明**：pipeline 类无既有单测，本次因新增分流行为（新代码路径）而补，符合"为新行为写测试"原则；不上传真实文件到 PgVector，全部 mock。

## Assumptions & Decisions

| # | 决策 | 理由 |
|---|---|---|
| 1 | **分流而非完全替换**（Tika 保留） | 上传层 md-only 是 P2 临时约束，`detectFileType` 已预留 PDF/TXT；删 Tika 未来要回补 |
| 2 | **Markdown 切块后仍过 FixedSizeTextSplitter** | 单个 H1/H2 章节可能数千字，超 embedding 粒度；splitter 短块整块返回逻辑天然兼容 |
| 3 | **ParserConfig 用默认配置**（includeCodeBlock/includeBlockquote=true） | 群知识库文档结构语义最大化，无需裁剪 |
| 4 | **版本用 `${spring-ai-alibaba.version}`（1.1.2.3）** | 与项目 SAA 组件版本对齐；远端已发布，本地缓存 1.1.2.2 不影响（Maven 自动下载） |
| 5 | **不升级 SAA BOM** | 该类在独立 starter，与 BOM 版本解耦；升 BOM 收益为零、回归风险大（nacos-prompt 等适配问题） |
| 6 | **检索侧、前端、数据库零改动** | 检索仅按 kbId 过滤；新增 category/lang metadata 只增不改，PgVector JSONB 自动容纳 |

## Verification steps

1. `mvn -pl dingRing-infrastructure -am compile` —— 首次下载 markdown starter 1.1.2.3，编译通过
2. `mvn -pl dingRing-infrastructure test -Dtest=DocumentIngestionPipelineTest` —— 分流逻辑验证通过
3. `mvn test`（全模块）—— 确认既有测试无回归（KnowledgeBaseAppServiceTest/FixedSizeTextSplitterTest 等不应受影响）
4. 启动应用，通过知识库页面上传一个含多级标题+代码块的 `.md` 文件：
   - 观察日志 `RAG_INGEST` 切片数（应显著多于纯滑窗方案，标题粒度更细）
   - PgVector 验证 metadata：`SELECT metadata FROM kb_vector_store ORDER BY id DESC LIMIT 5;` 应见 `category`（header_N/code_block）、`lang` 字段与既有 `kbId`/`fileId`/`chunkIndex` 并存
5. dev 检索端点（`/api/test/kb/search?query=...&groupId=...`）验证召回正常（结构切分后召回粒度更准）
