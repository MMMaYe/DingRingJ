# RAG 知识库检索系统设计文档

> 日期：2026-08-03
> 状态：已批准，待写实现计划

## 背景

DingRing 是一个 AI Agent 多人群聊学习系统，当前记忆机制仅依赖 `SimpleMemoryService` 拼接同群最近 5 条已关闭主题的结论文本，无语义检索能力。前端 KB 页面是纯 UI 骨架，领域层 `KnowledgeBase`/`File` 实体标注"P2 暂缓"。

本设计实现完整的 RAG（Retrieval-Augmented Generation）知识库系统，让群聊讨论时能检索优质知识注入 Agent 上下文。

## 目标

- 用户可上传 PDF/Markdown/TXT 文档构建知识库，系统自动切片、向量化、入库
- 系统自动将已有的知识卡片和主题结论向量化，作为内部沉淀知识源
- 群聊 Agent 每次发言时，自动检索相关知识（向量召回 + LLM 重排），注入上下文
- 支持全局知识 + 群专属知识双层过滤
- 任何环节失败不阻塞群聊主流程（RAG 是增强，不是依赖）

## 设计决策

| 决策项 | 选择 | 理由 |
|---|---|---|
| 内容源 | 用户上传文档 + 内部沉淀（卡片/结论） | 统一索引，最大化知识覆盖 |
| 向量存储 | PostgreSQL + pgvector | 专用向量库，检索性能好，Spring AI 原生支持 |
| Embedding 模型 | 可插拔抽象，后续调研确定具体模型 | 通过配置切换，不改代码 |
| 检索策略 | 向量召回 Top-20 + LLM 重排 Top-5 | 两阶段检索，质量优先 |
| 注入时机 | 每次 Agent 发言时检索注入 | 与现有 MemoryService 一致，改动最小 |
| 知识库作用域 | 全局 + 群专属双层 | 全局知识共享，群专属知识隔离 |
| 架构方案 | Spring AI 全栈（ETL + VectorStore + EmbeddingModel） | 最大化复用生态，最少自研代码 |

## 第 1 节：整体架构与模块边界

### 整体数据流

```
┌─────────────── 摄入侧（异步） ───────────────┐    ┌─────────── 检索侧（同步） ──────────┐
│                                             │    │                                    │
│  用户上传文档                                 │    │  用户消息 / Agent 发言               │
│      │                                      │    │      │                             │
│      ▼                                      │    │      ▼                             │
│  DocumentReader (PDF/MD/TXT)                │    │  构建检索 query                     │
│      │                                      │    │      │                             │
│      ▼                                      │    │      ▼                             │
│  TextSplitter (语义切片+重叠)                │    │  EmbeddingModel.embed(query)       │
│      │                                      │    │      │                             │
│      ▼                                      │    │      ▼                             │
│  EmbeddingModel.embed(chunks)               │    │  PgVectorStore.similaritySearch()  │
│      │                                      │    │  + metadata filter (双层)           │
│      ▼                                      │    │      │                             │
│  PgVectorStore.add(docs + metadata)  ───────┼────┼─>   Top-20 候选                    │
│                                             │    │      │                             │
│  内部沉淀向量化（异步任务）                    │    │      ▼                             │
│  KnowledgeCard / Topic 结论 ─> Embedding ─> │    │  LlmReranker.rerank(query, top20) │
│  PgVectorStore.add(scope=INTERNAL)          │    │      │                             │
│                                             │    │      ▼                             │
└─────────────────────────────────────────────┘    │  Top-5 注入 system prompt           │
                                                   │      │                             │
                                                   │      ▼                             │
                                                   │  ContextBuilder.build()            │
                                                   │  (现有流程 + RAG 知识段)            │
                                                   └────────────────────────────────────┘
```

### DDD 分层职责

| 层 | 职责 | 新增/改动 |
|---|---|---|
| **domain** | `RagService` 端口（检索接口）、`KnowledgeBase`/`File` 实体补全 | 改动 |
| **infrastructure** | PgVectorStore 配置、EmbeddingModel 实现、LlmReranker、ETL 摄入管道、多数据源 | 新增 |
| **app** | 文档上传 AppService、内部沉淀向量化任务、检索注入 ContextBuilder | 改动 |
| **adapter** | 文档上传 REST 端点 | 新增 |
| **frontend** | KB 页面对接真实上传/状态 | 改动 |

### 核心设计原则

1. **RagService 端口**定义在 domain 层，infrastructure 实现，与现有 `MemoryService`/`LlmService` 模式一致
2. **摄入与检索分离**：摄入是异步的，检索是同步的，互不阻塞
3. **EmbeddingModel 可插拔**：通过配置切换，不改代码（解决"后续调研"需求）
4. **metadata 双层过滤**：每条向量带 `scope`（GLOBAL/GROUP/INTERNAL）和 `groupId` 元数据，检索时 SQL 层过滤

### 与现有系统的关系

- `MemoryService`（同群历史结论拼接）保留，RAG 是增强而非替代
- `ContextBuilder.buildSystemPrompt()` 拼接顺序新增一段：人设 -> 角色说明 -> RAG 知识 -> 群记忆 -> 用户画像
- `KnowledgeCard` 和 `Topic.conclusion` 的向量化是增量异步的，在卡片生成/主题关闭事件触发

## 第 2 节：多数据源与数据模型

### 多数据源配置

```yaml
spring:
  ai:
    vectorstore:
      pgvector:
        datasource:
          url: jdbc:postgresql://localhost:5432/dingring_vector
          username: dingring
          password: xxx
          driver-class-name: org.postgresql.Driver
          hikari:
            maximum-pool-size: 10
            minimum-idle: 2
            connection-timeout: 8000
            max-lifetime: 120000
            idle-timeout: 60000
            keepalive-time: 60000
            connection-test-query: SELECT 1
            pool-name: DingRingPgVectorPool
        dimensions: 1536          # 取决于 Embedding 模型，后续确定
        distance-type: COSINE_DISTANCE
        index-type: HNSW
        schema-name: public
        table-name: vector_store
        initialize-schema: true
    embedding:
      openai:
        base-url: xxx             # 后续调研确定
        api-key: xxx
        model: xxx
```

新增配置类 `VectorDataSourceConfig`：
- `@Bean vectorDataSource`（PostgreSQL）+ `@Bean vectorJdbcTemplate`
- `@Qualifier("vectorJdbcTemplate")` 注入 PgVectorStore
- MySQL DataSource 保持 `@Primary`，MyBatis 零改动

### PostgreSQL：vector_store 表（Spring AI PgVectorStore 管理）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | UUID | 主键（Spring AI 生成） |
| content | TEXT | 切片文本内容 |
| metadata | JSONB | 元数据（见下） |
| embedding | vector(dim) | 向量（dim 取决于模型） |

### metadata JSONB 结构

```json
{
  "source": "UPLOAD | INTERNAL",
  "docType": "PDF | MARKDOWN | TXT | KNOWLEDGE_CARD | TOPIC_CONCLUSION",
  "scope": "GLOBAL | GROUP",
  "groupId": 123,
  "docId": 456,
  "docName": "Redis最佳实践.pdf",
  "chunkIndex": 3,
  "sourceId": "topic:789 | card:101 | file:456",
  "uploadTime": "2026-08-03T10:00:00"
}
```

- `source=UPLOAD`：用户上传文档切片
- `source=INTERNAL`：内部沉淀（知识卡片/主题结论）
- `scope=GLOBAL`：全局知识，所有群可检索
- `scope=GROUP`：群专属，仅 `groupId` 匹配的群可检索

### MySQL：补全现有实体

**knowledge_base 表（新建）**

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | |
| name | VARCHAR(128) | 知识库名称 |
| scope | VARCHAR(16) | GLOBAL / GROUP |
| group_id | BIGINT NULL | scope=GROUP 时关联群 |
| status | VARCHAR(16) | ACTIVE / PROCESSING / FAILED |
| feature | TEXT | JSON 扩展 |
| create_time / update_time | DATETIME | |

**kb_file 表（新建）**

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | |
| knowledge_base_id | BIGINT | 所属知识库 |
| name | VARCHAR(255) | 文件名 |
| path | VARCHAR(512) | 存储路径 |
| file_type | VARCHAR(16) | PDF/MD/TXT |
| file_size | BIGINT | 字节数 |
| status | VARCHAR(16) | UPLOADED/CHUNKED/EMBEDDED/READY/FAILED |
| chunk_count | INT | 切片数 |
| error_msg | TEXT NULL | 失败原因 |
| create_time / update_time | DATETIME | |

文件内容存在 PostgreSQL vector_store 的 content 字段；文件元信息存 MySQL。内部沉淀（卡片/结论）的元信息已在现有表中，不重复存储。

## 第 3 节：摄入管道

### 文档上传摄入流程

```
用户上传文件
    │
    ▼
FileController 接收 MultipartFile
    │
    ▼
KnowledgeBaseAppService.upload(file, kbId)
    │  ├── 存储文件到本地
    │  ├── kb_file 表记录（status=UPLOADED）
    │  └── 异步触发摄入任务
    │
    ▼
同步返回 fileMeta（前端轮询状态）           IngestionTask（虚拟线程）
                                                  │
                                                  ▼
                                          DocumentReader 读取（按 fileType 分发）
                                            ├── PdfReader
                                            ├── MarkdownReader
                                            └── TextReader
                                                  │
                                                  ▼
                                          TextSplitter 切片
                                          （TokenTextSplitter，chunk=800 token，overlap=200）
                                                  │
                                                  ▼
                                          EmbeddingModel.embed(chunks)
                                                  │
                                                  ▼
                                          PgVectorStore.add(docs)
                                          metadata 带 scope/groupId/docId
                                                  │
                                                  ▼
                                          kb_file.status = READY（失败则 FAILED + error_msg）
```

### 内部沉淀摄入流程（增量、异步）

复用现有领域事件，在事件处理器中触发向量化：

| 事件 | 触发时机 | 向量化内容 | metadata |
|---|---|---|---|
| `KnowledgeCardGenerated` | 卡片生成完成 | `question + "\n" + answer` | `source=INTERNAL, docType=KNOWLEDGE_CARD, scope=GLOBAL, sourceId=card:{id}` |
| `TopicClosed` | 主题关闭 | `title + "\n" + conclusion` | `source=INTERNAL, docType=TOPIC_CONCLUSION, scope=GROUP, groupId={id}, sourceId=topic:{id}` |

- 卡片是全局知识（所有群可检索），结论是群专属（仅本群可检索）
- 向量化失败仅记日志，不影响主流程（与现有画像提炼一致的容错策略）
- 避免重复向量化：入库前按 `sourceId` 查 metadata 去重

### EmbeddingModel 抽象（可插拔）

```
domain 层：无 Embedding 依赖（仅 RagService 端口）
infrastructure 层：
  @Bean EmbeddingModel embeddingModel
  ├── 方案1: OpenAI 兼容 API（复用现有网关模式，baseUrl/apiKey 可配）
  └── 方案2: 本地 ONNX（spring-ai-onnx-embedding-spring-boot-starter）
  通过 @ConditionalOnProperty("dingring.rag.embedding.provider") 切换
```

后续调研确定具体模型后，改配置不改代码。

### 文件存储

- Phase 1：本地文件系统（`dingring.rag.storage.local-path`）
- 预留 `FileStorageService` 端口，后续可切换对象存储（OSS/COS）

## 第 4 节：检索、重排与上下文注入

### 检索流程（每次 Agent 发言时触发）

```
ContextBuilder.build(agent, members, groupId, topicId, senderNameOf)
    │
    ├── 现有逻辑：人设 + 成员名单 + 滑动窗口消息
    │
    ├── 新增：RAG 知识检索
    │   │
    │   │  构建检索 query：
    │   │   - 讨论态：topic.title + 最近 3 条消息拼接
    │   │   - 闲聊态：最近 5 条消息拼接
    │   │
    │   ▼
    │   RagService.retrieve(query, groupId)
    │   │
    │   ├── 1. EmbeddingModel.embed(query) -> queryVector
    │   │
    │   ├── 2. PgVectorStore.similaritySearch()
    │   │      metadata filter: (scope=GLOBAL) OR (scope=GROUP AND groupId={id})
    │   │      topK=20, similarityThreshold=0.7
    │   │      -> Top-20 候选
    │   │
    │   ├── 3. LlmReranker.rerank(query, candidates)
    │   │      用轻量模型（复用 Moderator model 或群首 Agent 模型）
    │   │      prompt: "根据问题对以下文档按相关性打分0-10"
    │   │      -> Top-5 重排结果
    │   │
    │   └── 4. 格式化为文本段返回
    │
    ▼
system prompt 拼接顺序：
    1. Agent 人设
    2. 群聊角色说明
    3. 【新增】RAG 检索知识（Top-5 切片）
    4. 群记忆（历史结论）
    5. 用户画像
```

### RagService 端口设计

```java
// domain 层
public interface RagService {
    /**
     * 检索与当前对话相关的知识片段。
     * @param query 检索文本（主题+近期消息）
     * @param groupId 群 ID（用于群专属知识过滤）
     * @return 格式化的知识文本段，无结果返回空字符串
     */
    String retrieve(String query, Long groupId);
}
```

### LlmReranker 设计

```java
// infrastructure 层
public interface Reranker {
    List<ScoredDocument> rerank(String query, List<Document> candidates);
}

// LLM 重排实现
@Component
public class LlmReranker implements Reranker {
    // 复用现有 LlmService（轻量模型），避免新增 API 依赖
    // prompt 让 LLM 输出 JSON: [{"index":0,"score":8.5},...]
    // 取 score 最高的 Top-5
}
```

### 容错策略

| 环节 | 失败处理 |
|---|---|
| Embedding 调用 | 跳过 RAG，返回空（发言不阻塞） |
| 向量检索 | 跳过 RAG，返回空 |
| LLM 重排 | 降级为纯向量 Top-5（跳过重排） |
| 总超时 | RAG 检索整体超时 5s，超时跳过 |

关键原则：RAG 是增强，不能阻塞群聊主流程。任何环节失败都静默降级，与现有画像提炼的容错策略一致。

### 性能考量

- 向量检索 ~200ms（pgvector HNSW 索引）
- LLM 重排 ~1-2s（轻量模型，输入仅 20 条摘要）
- 群聊 Agent 发言间隔 5-15s，2s 检索延迟可接受
- 闲聊态可降低 topK 或跳过重排（配置开关 `dingring.rag.chat-rerank-enabled`）

## 第 5 节：REST API 与前端改动

### REST API（新增）

```
# 知识库管理
POST   /api/kb                         创建知识库（name, scope, groupId?）
GET    /api/kb                         列出所有知识库
GET    /api/kb/{id}                    知识库详情（含文件列表）
DELETE /api/kb/{id}                    删除知识库（级联删除向量）

# 文档上传与管理
POST   /api/kb/{id}/files              上传文件（multipart）
GET    /api/kb/{id}/files              列出知识库下文件（含状态）
DELETE /api/kb/{id}/files/{fileId}     删除文件（级联删除向量）
POST   /api/kb/{id}/files/{fileId}/reprocess  重新处理失败文件

# 检索调试（dev only）
POST   /api/kb/search                  手动检索测试（query, groupId?）
GET    /api/kb/search?query=xxx        简单检索测试
```

### 前端改动（KB 页面）

现有 `frontend/src/pages/KB/index.tsx` 是纯骨架，改动：

1. **知识库列表/创建**：左侧 Sidebar 展示知识库列表，支持创建（选全局/群专属）
2. **文件上传**：对接 `POST /api/kb/{id}/files`，拖拽上传，显示进度
3. **文件状态轮询**：文件列表展示 `UPLOADED -> CHUNKED -> EMBEDDED -> READY` 状态流转，轮询更新
4. **统计卡片**：对接真实数据（知识库数、文件数、切片数、已向量化数）
5. **检索测试面板**：dev 环境显示，输入 query 看检索结果（含重排分数）

前端改动遵循现有 editorial 风格，复用 `renderMarkdown`、`toast` 等组件。

## 第 6 节：测试策略

| 层 | 测试重点 | 方式 |
|---|---|---|
| **domain** | `RagService` 端口契约 | Mock 检索结果，验证拼接格式 |
| **infrastructure** | 摄入管道（切片/向量化/入库）、检索（metadata filter）、重排（LLM 打分解析） | Mock EmbeddingModel + Mock LlmService，验证调用链 |
| **app** | `ContextBuilder` 集成 RAG 段、文件上传 AppService、事件触发向量化 | Mock RagService，验证拼接顺序和容错降级 |
| **adapter** | 文件上传端点、检索调试端点 | MockMvc |

关键测试用例：
- 检索结果为空时，system prompt 不含 RAG 段，不影响现有流程
- LLM 重排失败时，降级为纯向量 Top-5
- 内部沉淀向量化去重（同 sourceId 不重复入库）
- 双层过滤：群专属知识不被其他群检索到

## 不改动

- `SimpleMemoryService` 保留（RAG 是增强不是替代）
- `ChatOrchestrator`、`DiscussionEngine` 主流程不动（RAG 注入在 `ContextBuilder` 内部完成）
- 知识卡片管理、主题沉淀区页面不动
- `schema.sql` 仅新增表，不改动现有表结构
