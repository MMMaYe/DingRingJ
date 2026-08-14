# DingRing RAG 技术方案

> 日期：2026-08-12
> 状态：草案，待评审
> 依据：[Spring AI Alibaba RAG 文档](https://java2ai.com/docs/frameworks/agent-framework/advanced/rag/)

---

## 目录

1. [系统架构设计](#1-系统架构设计)
2. [数据库迁移详细步骤及验证方案](#2-数据库迁移详细步骤及验证方案)
3. [向量模型选型报告及集成方案](#3-向量模型选型报告及集成方案)
4. [核心功能实现流程](#4-核心功能实现流程)
5. [性能优化策略](#5-性能优化策略)
6. [测试计划](#6-测试计划)
7. [部署方案](#7-部署方案)
8. [风险评估及应对措施](#8-风险评估及应对措施)
9. [项目实施时间表](#9-项目实施时间表)

---

## 1. 系统架构设计

### 1.1 整体架构

DingRing RAG 系统采用 **DDD 分层 + Spring AI 全栈** 架构，分为摄入侧（异步）和检索侧（同步）两条链路：

```
┌─────────────── 摄入侧（异步） ───────────────┐    ┌─────────── 检索侧（同步） ──────────┐
│                                             │    │                                    │
│  用户上传文档                                 │    │  用户消息 / Agent 发言               │
│      │                                      │    │      │                             │
│      ▼                                      │    │      ▼                             │
│  TikaDocumentReader (PDF/MD/TXT)            │    │  构建检索 query                     │
│      │                                      │    │      │                             │
│      ▼                                      │    │      ▼                             │
│  TokenTextSplitter (800 token, 200 overlap) │    │  EmbeddingModel.embed(query)       │
│      │                                      │    │      │                             │
│      ▼                                      │    │      ▼                             │
│  EmbeddingModel.embed(chunks)               │    │  PgVectorStore.similaritySearch()  │
│      │                                      │    │  + metadata filter (双层)           │
│      ▼                                      │    │      │                             │
│  PgVectorStore.add(docs + metadata)  ──────┼────┼─>   Top-20 候选                    │
│      │                                      │    │      │                             │
│  内部沉淀向量化（异步事件）                    │    │      ▼                             │
│  KnowledgeCard / Topic 结论 ─> Embedding ─> │    │  LlmReranker.rerank(query, top20) │
│  PgVectorStore.add(scope=INTERNAL)          │    │      │                             │
│                                             │    │      ▼                             │
└─────────────────────────────────────────────┘    │  Top-5 注入 system prompt           │
                                                   │      │                             │
                                                   │      ▼                             │
                                                   │  ContextBuilder.build()            │
                                                   │  (人设→角色→RAG知识→记忆→画像)      │
                                                   └────────────────────────────────────┘
```

### 1.2 DDD 分层职责

| 层 | 职责 | 现有模块 | 本方案变更 |
|---|---|---|---|
| **domain** | `RagService` 端口、`Reranker` 端口、`KnowledgeBase`/`File` 实体 | 已实现 | 无变更 |
| **infrastructure** | PgVectorStore 配置、EmbeddingModel 实现、LlmReranker、ETL 摄入管道、多数据源 | 已实现 | Embedding 模型从 Mock 切换为真实模型 |
| **app** | 文档上传 AppService、检索注入 ContextBuilder | 已实现 | 无变更 |
| **adapter** | 文件上传 REST 端点（KbController）、检索调试端点 | 已实现 | 无变更 |

### 1.3 核心设计原则

1. **RagService 端口**定义在 domain 层，infrastructure 实现，与 `MemoryService`/`LlmService` 模式一致
2. **摄入与检索分离**：摄入异步执行，检索同步执行，互不阻塞
3. **EmbeddingModel 可插拔**：通过 `@ConditionalOnProperty` 切换，不改代码（解决模型选型需求）
4. **metadata 双层过滤**：每条向量带 `scope`（GLOBAL/GROUP）和 `groupId` 元数据，检索时 SQL 层过滤
5. **容错降级**：RAG 是增强不是依赖，任何环节失败静默降级为空，不阻塞群聊主流程

### 1.4 与 Spring AI Alibaba Agent Framework 的集成

当前系统通过 `RagInjectionHook`（继承 `ModelHook`）实现两步 RAG 架构，与 SAA Agent Framework 的集成方式如下：

```java
// RagInjectionHook 在 Agent 发言前自动检索知识库，注入 system prompt
@HookPositions({HookPosition.BEFORE_MODEL})
public class RagInjectionHook extends ModelHook {
    @Override
    public CompletableFuture<Map<String, Object>> beforeModel(OverAllState state, RunnableConfig config) {
        // 1. 从 state 读取 groupId 和 ragQuery
        // 2. 调用 RagService.retrieve(query, groupId) 检索
        // 3. 返回 SystemMessage 注入知识段落
    }
}
```

此外，`KnowledgeSearchTool` 作为 Agent 工具注册，支持 Agentic RAG 模式——Agent 可在推理过程中主动调用知识检索。

### 1.5 关键源码索引

| 组件 | 源码位置 |
|---|---|
| RAG 端口 | [RagService.java](dingRing-domain/src/main/java/com/dingring/domain/service/RagService.java) |
| RAG 实现 | [SaaRagService.java](dingRing-infrastructure/src/main/java/com/dingring/infrastructure/rag/SaaRagService.java) |
| 重排端口 | [Reranker.java](dingRing-domain/src/main/java/com/dingring/domain/service/Reranker.java) |
| 重排实现 | [LlmReranker.java](dingRing-infrastructure/src/main/java/com/dingring/infrastructure/rag/LlmReranker.java) |
| 摄入管道 | [DocumentIngestionPipeline.java](dingRing-infrastructure/src/main/java/com/dingring/infrastructure/rag/DocumentIngestionPipeline.java) |
| 向量存储配置 | [PgVectorStoreConfig.java](dingRing-infrastructure/src/main/java/com/dingring/infrastructure/rag/config/PgVectorStoreConfig.java) |
| 向量数据源配置 | [VectorDataSourceConfig.java](dingRing-infrastructure/src/main/java/com/dingring/infrastructure/rag/config/VectorDataSourceConfig.java) |
| Mock Embedding | [MockEmbeddingModel.java](dingRing-infrastructure/src/main/java/com/dingring/infrastructure/rag/embedding/MockEmbeddingModel.java) |
| RAG 注入 Hook | [RagInjectionHook.java](dingRing-infrastructure/src/main/java/com/dingring/infrastructure/agent/hook/RagInjectionHook.java) |
| 知识检索工具 | [KnowledgeSearchTool.java](dingRing-infrastructure/src/main/java/com/dingring/infrastructure/agent/tool/KnowledgeSearchTool.java) |
| 文件存储 | [FileStorageService.java](dingRing-infrastructure/src/main/java/com/dingring/infrastructure/rag/FileStorageService.java) |
| 知识库 App 服务 | [KnowledgeBaseAppService.java](dingRing-app/src/main/java/com/dingring/app/service/KnowledgeBaseAppService.java) |

---

## 2. 数据库迁移详细步骤及验证方案

### 2.1 迁移背景

当前 RAG 系统使用 PostgreSQL `my_rag` 数据库作为向量存储。需迁移至新建的 `ring_rag` 数据库，与项目命名 `DingRing` 保持一致。MySQL 主库 `ring_chat` 不受影响。

### 2.2 当前配置（已变更）

[application.yml](start/src/main/resources/application.yml) 中向量库数据源配置已从 `my_rag` 更新为 `ring_rag`：

```yaml
spring:
  ai:
    vectorstore:
      pgvector:
        datasource:
          # 已从 my_rag 改为 ring_rag
          url: jdbc:postgresql://101.33.227.80:5432/ring_rag
          username: postgres
          password: postgres123
          driver-class-name: org.postgresql.Driver
```

### 2.3 迁移步骤

#### 步骤 1：创建 ring_rag 数据库

```sql
-- 连接 PostgreSQL 服务器
-- psql -h 101.33.227.80 -U postgres -d postgres

-- 创建 ring_rag 数据库
CREATE DATABASE ring_rag
  WITH ENCODING 'UTF8'
  LC_COLLATE 'C.UTF8'
  LC_CTYPE 'C.UTF8'
  TEMPLATE template0;

-- 确认创建
SELECT datname FROM pg_database WHERE datname = 'ring_rag';
```

#### 步骤 2：安装 pgvector 扩展

```sql
-- 连接到 ring_rag 数据库
-- psql -h 101.33.227.80 -U postgres -d ring_rag

-- 安装 pgvector 扩展
CREATE EXTENSION IF NOT EXISTS vector;

-- 确认扩展安装
SELECT extname, extversion FROM pg_extension WHERE extname = 'vector';
```

#### 步骤 3：启动应用自动建表

`PgVectorStoreConfig` 已配置 `initializeSchema(true)`，首次启动应用时会自动在 `ring_rag` 库中创建 `vector_store` 表和 HNSW 索引：

```java
// PgVectorStoreConfig.java — 自动建表
return PgVectorStore.builder(jdbcTemplate, embeddingModel)
        .dimensions(embeddingModel.dimensions())
        .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
        .indexType(PgVectorStore.PgIndexType.HNSW)
        .initializeSchema(true)  // ← 首次启动自动建表
        .build();
```

启动日志确认：
```
PgVectorStore 初始化: dimensions=1536 distance-type=COSINE
PostgreSQL 向量库数据源初始化: url=jdbc:postgresql://101.33.227.80:5432/ring_rag
```

#### 步骤 4（可选）：从 my_rag 迁移存量数据

如果 `my_rag` 库中已有向量数据，需迁移到 `ring_rag`：

```bash
# 导出 my_rag 向量数据
pg_dump -h 101.33.227.80 -U postgres -d my_rag \
  --table=vector_store \
  --data-only \
  --column-inserts \
  -f /tmp/vector_store_dump.sql

# 导入到 ring_rag（确保表已通过应用启动创建）
psql -h 101.33.227.80 -U postgres -d ring_rag -f /tmp/vector_store_dump.sql
```

#### 步骤 5：验证

```sql
-- 验证表结构
SELECT column_name, data_type FROM information_schema.columns
  WHERE table_name = 'vector_store' ORDER BY ordinal_position;

-- 验证 HNSW 索引
SELECT indexname, indexdef FROM pg_indexes WHERE tablename = 'vector_store';

-- 验证数据行数（迁移后）
SELECT COUNT(*) FROM vector_store;

-- 验证向量维度（取一条记录）
SELECT id, content, metadata, vector_dims(embedding) AS dim FROM vector_store LIMIT 1;
```

### 2.4 验证方案

| 验证项 | 方法 | 预期结果 |
|---|---|---|
| 数据库连接 | 启动应用，观察日志 | `PostgreSQL 向量库数据源初始化: url=...ring_rag` |
| 表自动创建 | `\dt vector_store` | 表存在，含 id/content/metadata/embedding 列 |
| HNSW 索引 | `pg_indexes` 查询 | 存在 HNSW 索引 |
| 文档上传 | `POST /api/kb/{id}/files` 上传 PDF | 文件状态流转 UPLOADED→CHUNKED→EMBEDDED→READY |
| 向量检索 | `POST /api/kb/search` | 返回相关知识段落 |
| 旧库隔离 | `my_rag` 库无新数据写入 | 新数据仅出现在 `ring_rag` |

---

## 3. 向量模型选型报告及集成方案

### 3.1 选型背景

当前系统使用 `MockEmbeddingModel`（1536 维确定性向量），仅用于框架联调。需选型真实 Embedding 模型替换 Mock，以获得语义检索能力。

选型约束：
- **Spring AI 兼容**：必须实现 `EmbeddingModel` 接口，支持 PgVectorStore
- **中文场景优先**：知识库内容以中文为主
- **部署成本低**：当前服务器为公网 PostgreSQL，无 GPU 环境
- **维度匹配**：PgVectorStore 维度需与模型一致

### 3.2 主流向量模型性能对比

#### 3.2.1 API 类模型

| 模型 | 提供方 | 维度 | 最大输入 | MTEB 分数 | CMTEB 分数 | 语言支持 | 单价（每 1K tokens） | Spring AI 集成 |
|---|---|---|---|---|---|---|---|---|
| **text-embedding-v4** | DashScope/阿里 | 64-2048（默认 1024） | 8K tokens | 68.36 | 70.14 | 100+ 语言 | ¥0.0005 | DashScope Starter 原生支持 |
| **text-embedding-v3** | DashScope/阿里 | 64-1024（默认 1024） | 8K tokens | 63.39 | 68.92 | 50+ 语言 | ¥0.0005 | DashScope Starter 原生支持 |
| **text-embedding-3-small** | OpenAI | 任意（默认 1536） | 8K tokens | 62.3 | 中等 | 多语言 | ~¥0.001 | spring-ai-openai 原生支持 |
| **text-embedding-3-large** | OpenAI | 任意（默认 3072） | 8K tokens | 64.6 | 中等 | 多语言 | ~¥0.013 | spring-ai-openai 原生支持 |

#### 3.2.2 开源本地部署模型

| 模型 | 参数量 | 维度 | 最大输入 | MTEB/CMTEB | 中文能力 | 部署方式 | Spring AI 集成 |
|---|---|---|---|---|---|---|---|
| **BGE-M3** | 568M | 1024 | 8192 tokens | 62.2 / 强 | 极强（100+ 语言） | Ollama / ONNX / Hugging Face | Ollama Starter |
| **Qwen3-Embedding-0.6B** | 600M | 1024-4096 | 32K tokens | 64.3 / 76.3 | 极强 | Ollama / Hugging Face | DashScope Starter |
| **jina-embeddings-v5-text-small** | 677M | 1024 | 32K tokens | 67.0 / 73.7 | 强 | Ollama / Hugging Face | Ollama Starter |
| **nomic-embed-text** | 137M | 768 | 8192 tokens | 62.0 / 中等 | 一般 | Ollama | Ollama Starter |

#### 3.2.3 关键性能指标详解

**检索准确率**（基于 MTEB/CMTEB Retrieval 子项）:

| 模型 | MTEB Retrieval | CMTEB Retrieval | 特点 |
|---|---|---|---|
| text-embedding-v4 (1024维) | 59.30 | 73.98 | 中文检索最优的 API 模型 |
| Qwen3-Embedding-0.6B | 60.1 | 76.3 | 中文检索最优的开源模型 |
| BGE-M3 | 64.8 (BEIR) | ~72 | 多向量检索能力强，长文档优势 |
| jina-embeddings-v5-text-small | 63.28 | 73.7 | 1B 以下检索最优，LoRA 适配器 |

**推理速度对比**（单条文本向量化延迟，参考值）:

| 部署方式 | 模型 | 延迟 | 吞吐 |
|---|---|---|---|
| DashScope API | text-embedding-v4 | ~10ms | 高（API 弹性扩容） |
| Ollama 本地 (CPU) | BGE-M3 | ~50-200ms | ~50 tokens/s |
| Ollama 本地 (GPU) | BGE-M3 | ~5-15ms | ~1000 tokens/s |
| Ollama 本地 (CPU) | nomic-embed-text | ~20-80ms | ~120 tokens/s |

### 3.3 模型与 Spring AI 框架兼容性评估

| 接入方式 | Spring AI 支持 | 配置复杂度 | 需新增依赖 | 维度匹配 |
|---|---|---|---|---|
| **DashScope Starter** | 原生支持（`spring-ai-alibaba-starter`） | 低（仅需 api-key） | `spring-ai-alibaba-starter-dashscope` | 模型维度可配，PgVectorStore 自动适配 |
| **OpenAI Starter** | 原生支持（`spring-ai-openai`，已有） | 低 | 已有依赖 | 同上 |
| **Ollama Starter** | 原生支持（`spring-ai-ollama-spring-boot-starter`） | 中（需部署 Ollama） | `spring-ai-ollama-spring-boot-starter` | 同上 |

> 项目已引入 `spring-ai-openai` 依赖，DashScope 通过 OpenAI 兼容模式也可直接复用现有依赖。

### 3.4 模型部署资源需求分析

| 模型 | 部署方式 | CPU | GPU | 内存 | 磁盘 | 年成本估算 |
|---|---|---|---|---|---|---|
| DashScope text-embedding-v4 | 云 API | 0 | 0 | 0 | 0 | ~¥50（按 10M tokens/月用量） |
| BGE-M3 (Ollama) | 本地 GPU | 4 核 | 1× RTX 3060 (12GB) | 8GB | 2.4GB | ~¥3,000（GPU 服务器） |
| BGE-M3 (Ollama CPU) | 本地 CPU | 8 核 | 0 | 8GB | 2.4GB | ~¥0（复用现有服务器） |
| Qwen3-Embedding-0.6B (Ollama) | 本地 GPU | 4 核 | 1× T4 (16GB) | 8GB | 1.2GB | ~¥3,000 |

### 3.5 推荐向量模型（3 选 1）

#### 推荐 1（首选）：DashScope text-embedding-v4

**推荐理由：**
- **中文检索最优**：CMTEB Retrieval 73.98，远超 OpenAI text-embedding-3-small
- **部署成本最低**：无需 GPU，调用云 API 即可，当前服务器无需改动
- **Spring AI 原生支持**：通过 `spring-ai-alibaba-starter` 接入，配置 `spring.ai.dashscope.api-key` 即可
- **维度灵活**：支持 64-2048 维可选，默认 1024 维与 PgVectorStore 兼容
- **长上下文**：8K tokens 输入，满足大部分文档切片需求
- **多语言支持**：100+ 语言，中英混合知识库无压力

**适用场景**：快速上线、低运维成本、中文为主的知识库

#### 推荐 2（备选）：BGE-M3 本地部署（Ollama）

**推荐理由：**
- **零 API 成本**：本地部署，无 token 计费
- **多向量检索**：支持密集+稀疏+多向量三模态检索，长文档检索精度高
- **多语言强**：100+ 语言原生支持，中英跨语言检索效果优于同量级模型
- **长上下文**：8192 tokens 输入
- **生态完善**：Ollama 一键部署，Spring AI Ollama Starter 原生支持
- **数据隐私**：向量化不离开本地，适合敏感数据场景

**适用场景**：数据敏感、高 API 用量、有 GPU 资源或 CPU 资源充足

#### 推荐 3（轻量）：DashScope text-embedding-v3

**推荐理由：**
- **成本低**：同价格 0.0005 元/千 tokens
- **维度固定 1024**：与当前 PgVectorStore 配置兼容（如已建表可复用）
- **50+ 语言**：覆盖主要语言

**适用场景**：需要稳定 1024 维输出、已基于 v3 构建索引的项目

### 3.6 向量模型测试方案

#### 测试数据集

使用项目实际知识库文档构建测试集：
1. 选取 50 篇中文文档（技术文档、产品说明、FAQ）
2. 每篇文档标注 3 个查询-答案对（共计 150 个测试对）
3. 查询覆盖：精确匹配、语义改写、跨语言、长文档定位

#### 评估指标

| 指标 | 计算方式 | 目标值 |
|---|---|---|
| **Recall@5** | Top-5 结果包含正确文档的比例 | ≥ 85% |
| **Recall@20** | Top-20 结果包含正确文档的比例 | ≥ 95% |
| **MRR@10** | 正确文档在 Top-10 的平均倒数排名 | ≥ 0.75 |
| **nDCG@10** | 考虑排序位置的归一化折损累计增益 | ≥ 0.70 |
| **查询延迟 P99** | 99 分位查询延迟 | ≤ 200ms |
| **摄入吞吐** | 每秒处理文档切片数 | ≥ 20 chunks/s |

#### 测试流程

```java
// 评估测试代码示例
@SpringBootTest
class EmbeddingModelEvaluationTest {

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private VectorStore vectorStore;

    private List<TestCase> testCases; // 150 个测试对

    @Test
    void evaluateRetrieval() {
        int recallAt5 = 0, recallAt20 = 0;
        double mrrSum = 0;

        for (TestCase tc : testCases) {
            List<Document> results = vectorStore.similaritySearch(
                SearchRequest.builder()
                    .query(tc.query())
                    .topK(20)
                    .build()
            );

            Set<String> resultIds = results.stream()
                .map(d -> d.getMetadata().get("docId").toString())
                .collect(Collectors.toSet());

            if (resultIds.contains(tc.expectedDocId())) recallAt20++;

            List<String> top5Ids = results.stream().limit(5)
                .map(d -> d.getMetadata().get("docId").toString())
                .toList();
            if (top5Ids.contains(tc.expectedDocId())) recallAt5++;

            // MRR
            for (int i = 0; i < results.size(); i++) {
                if (results.get(i).getMetadata().get("docId").equals(tc.expectedDocId())) {
                    mrrSum += 1.0 / (i + 1);
                    break;
                }
            }
        }

        double recall5 = (double) recallAt5 / testCases.size();
        double recall20 = (double) recallAt20 / testCases.size();
        double mrr = mrrSum / testCases.size();

        // 输出评估报告
        log.info("Recall@5={}, Recall@20={}, MRR@10={}", recall5, recall20, mrr);
    }
}
```

### 3.7 集成方案

#### 推荐方案 A：DashScope text-embedding-v4 集成

**步骤 1：添加 Maven 依赖**

在 [dingRing-infrastructure/pom.xml](dingRing-infrastructure/pom.xml) 中添加：

```xml
<!-- Spring AI Alibaba DashScope（Embedding + Chat） -->
<dependency>
    <groupId>com.alibaba.cloud.ai</groupId>
    <artifactId>spring-ai-alibaba-starter-dashscope</artifactId>
    <version>${spring-ai-alibaba.version}</version>
</dependency>
```

**步骤 2：配置 application.yml**

```yaml
spring:
  ai:
    dashscope:
      api-key: ${DASHSCOPE_API_KEY}
      embedding:
        options:
          model: text-embedding-v4
          dimensions: 1024  # 平衡精度与存储成本

dingring:
  rag:
    embedding:
      provider: dashscope  # 从 mock 切换为 dashscope
```

**步骤 3：新建 DashScope Embedding 配置类**

```java
package com.dingring.infrastructure.rag.embedding;

import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * DashScope Embedding 模型配置。
 * <p>当 dingring.rag.embedding.provider=dashscope 时激活，替代 MockEmbeddingModel。
 * <p>模型：text-embedding-v4（Qwen3-Embedding 系列），支持 100+ 语言，默认 1024 维。
 */
@Configuration
@ConditionalOnProperty(name = "dingring.rag.embedding.provider", havingValue = "dashscope")
public class DashScopeEmbeddingConfig {

    @Bean
    public DashScopeEmbeddingModel dashScopeEmbeddingModel(
            com.alibaba.cloud.ai.dashscope.api.DashScopeApi dashScopeApi) {
        return new DashScopeEmbeddingModel(dashScopeApi);
    }
}
```

**步骤 4：调整 MockEmbeddingModel 条件**

```java
// MockEmbeddingModel.java — 仅在 provider=mock 时激活
@ConditionalOnProperty(name = "dingring.rag.embedding.provider",
    havingValue = "mock", matchIfMissing = true)  // matchIfMissing 保留为默认降级
public class MockEmbeddingModel implements EmbeddingModel { ... }
```

> **注意**：切换 Embedding 模型后维度可能变化（Mock 1536 → DashScope 1024），`PgVectorStore` 的 `initializeSchema(true)` 会在首次启动时自动创建匹配维度的表。如果是切换已建表的线上库，需手动执行 `DROP TABLE vector_store` 重建表。

#### 推荐方案 B：BGE-M3 本地部署集成

```xml
<!-- Ollama（本地模型） -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-ollama-spring-boot-starter</artifactId>
</dependency>
```

```yaml
spring:
  ai:
    ollama:
      base-url: http://localhost:11434
      embedding:
        model: bge-m3  # 或 nomic-embed-text

dingring:
  rag:
    embedding:
      provider: ollama
```

```java
@Configuration
@ConditionalOnProperty(name = "dingring.rag.embedding.provider", havingValue = "ollama")
public class OllamaEmbeddingConfig {
    @Bean
    public OllamaEmbeddingModel ollamaEmbeddingModel(
            OllamaApi ollamaApi,
            @Value("${spring.ai.ollama.embedding.model}") String modelName) {
        return new OllamaEmbeddingModel(ollamaApi,
            OllamaEmbeddingOptions.builder().model(modelName).build());
    }
}
```

---

## 4. 核心功能实现流程

### 4.1 文档摄入流程

当前实现位于 [DocumentIngestionPipeline.java](dingRing-infrastructure/src/main/java/com/dingring/infrastructure/rag/DocumentIngestionPipeline.java)：

```
用户上传文件
    │
    ▼
KbController 接收 MultipartFile
    │
    ▼
KnowledgeBaseAppService.upload(kbId, file)
    │  ├── FileStorageService.store() 落盘（按日期分目录 + UUID 文件名）
    │  ├── kb_file 表记录（status=UPLOADED）
    │  └── 异步触发 ingest()
    │
    ▼  @Async
DocumentIngestionPipeline.ingest(file, scope, groupId)
    │
    ├── 1. TikaDocumentReader 读取文件（支持 PDF/MD/TXT）
    │
    ├── 2. 更新状态 CHUNKED
    │
    ├── 3. TokenTextSplitter 切片（chunk=800 token, overlap=200 token）
    │
    ├── 4. 构建 metadata（source/docType/scope/groupId/docId/docName/uploadTime）
    │
    ├── 5. 分批向量入库（BATCH_SIZE=50），PgVectorStore.add(chunks)
    │      → EmbeddingModel.embed() 逐批调用
    │
    └── 6. 更新状态 READY（失败则 FAILED + errorMsg）
```

### 4.2 检索流程

当前实现位于 [SaaRagService.java](dingRing-infrastructure/src/main/java/com/dingring/infrastructure/rag/SaaRagService.java)：

```
用户消息 / Agent 发言触发检索
    │
    ▼
构建检索 query
    │  ├── 讨论态：topic.title + 最近 3 条消息拼接
    │  └── 闲聊态：最近 5 条消息拼接
    │
    ▼
RagService.retrieve(query, groupId)
    │
    ├── 1. 构建 metadata 过滤表达式
    │      scope=GLOBAL OR (scope=GROUP AND groupId={id})
    │
    ├── 2. 向量召回 Top-20
    │      PgVectorStore.similaritySearch()
    │      topK=20, similarityThreshold=0.7
    │      → 候选文档列表
    │
    ├── 3. LLM 重排 Top-5
    │      LlmReranker.rerank(query, candidates)
    │      复用 routeJudge Agent（DeepSeek-V4-Flash）
    │      Prompt: "根据查询对文档按相关性打分 0-10"
    │      输出 JSON: [{"index":0,"score":8.5},...]
    │      → 按分数降序取 Top-5
    │
    └── 4. 格式化为文本段返回
           "知识库检索结果（按相关性排序）：\n[1] (相关性: 8.5)\n..."
```

### 4.3 知识注入流程

当前实现位于 [RagInjectionHook.java](dingRing-infrastructure/src/main/java/com/dingring/infrastructure/agent/hook/RagInjectionHook.java)：

```
Agent 发言前
    │
    ▼
RagInjectionHook.beforeModel(state, config)
    │
    ├── 从 state 读取 groupId 和 ragQuery
    │
    ├── 调用 ragService.retrieve(query, groupId)
    │
    ├── 知识段落存在？
    │   ├── 是 → 返回 SystemMessage("知识库参考：\n" + knowledge)
    │   └── 否 → 返回空 Map（不注入）
    │
    ▼
system prompt 拼接顺序：
    1. Agent 人设
    2. 群聊角色说明
    3. RAG 检索知识（Top-5 切片）
    4. 群记忆（历史结论）
    5. 用户画像
```

### 4.4 双层过滤机制

```java
// SaaRagService.java — metadata 双层过滤
FilterExpressionBuilder b = new FilterExpressionBuilder();
FilterExpressionBuilder.Op filter;
if (groupId != null) {
    // 全局知识 OR 群专属知识
    filter = b.or(
        b.eq("scope", "GLOBAL"),
        b.and(b.eq("scope", "GROUP"), b.eq("groupId", groupId))
    );
} else {
    // 无群上下文时仅检索全局知识
    filter = b.eq("scope", "GLOBAL");
}
```

### 4.5 容错降级链路

| 环节 | 失败处理 | 实现 |
|---|---|---|
| Embedding 调用 | 跳过 RAG，返回空字符串 | `SaaRagService.retrieve()` try-catch |
| 向量检索 | 跳过 RAG，返回空字符串 | 同上 |
| LLM 重排 | 降级为纯向量 Top-5（原始顺序） | `LlmReranker.fallbackOrder()` |
| RAG 注入 | 跳过注入，不阻塞 Agent 发言 | `RagInjectionHook` catch 返回空 Map |

---

## 5. 性能优化策略

### 5.1 摄入侧优化

| 策略 | 现状 | 优化方案 |
|---|---|---|
| 批量向量化 | `BATCH_SIZE=50` 已分批 | 可按 API 限流动态调整批次大小 |
| 异步摄入 | `@Async` 已异步 | 引入 Spring 虚拟线程（`spring.threads.virtual.enabled=true`） |
| 文件解析 | Tika 同步读取 | 大文件（>10MB）拆分为子任务并行解析 |
| 切片策略 | `TokenTextSplitter(800, 200)` | 针对中文文档可改用 `TokenTextSplitter(1200, 300)`，减少片段数 |
| 重复摄入 | 无去重 | 入库前按 `docId + chunkHash` 查 metadata 去重 |

### 5.2 检索侧优化

| 策略 | 现状 | 优化方案 |
|---|---|---|
| 向量索引 | HNSW（已是最优近似索引） | 调优 HNSW 参数：`m=16, ef_construction=64` |
| 检索降级 | 闲聊态也全量重排 | 闲聊态跳过重排，仅讨论态重排（配置开关） |
| 查询缓存 | 无 | 相同 query 5 分钟内复用检索结果（Caffeine 缓存） |
| 候选数控制 | 固定 Top-20 | 按相似度分标准动态调整 topK（高置信 10，低置信 30） |
| 混合检索 | 纯向量检索 | BGE-M3 支持稠密+稀疏混合检索，可提升关键词匹配精度 |

### 5.3 存储优化

| 策略 | 效果 |
|---|---|
| 维度选择 1024（DashScope 默认） | 比 1536 维减少 33% 存储 |
| metadata JSONB 精简 | 移除冗余字段，仅保留检索过滤所需 |
| 向量量化 | PgVectorStore 支持 `HALF_PRECISION`（float16），存储减半 |
| 过期数据清理 | 定期删除 `scope=INTERNAL` 且超 90 天的沉淀知识 |

### 5.4 系统级优化

```yaml
# application.yml — 虚拟线程支持（Spring Boot 3.2+）
spring:
  threads:
    virtual:
      enabled: true

# HikariCP 连接池调优（向量库数据源）
# 当前配置已针对公网优化：短寿命 + 借前探活
spring:
  ai:
    vectorstore:
      pgvector:
        datasource:
          hikari:
            maximum-pool-size: 10  # 可按并发量调整为 20
            minimum-idle: 2
            connection-timeout: 8000
            max-lifetime: 60000
            idle-timeout: 60000
            keepalive-time: 60000
            connection-test-query: SELECT 1
```

---

## 6. 测试计划

### 6.1 单元测试

| 模块 | 测试类 | 测试重点 |
|---|---|---|
| SaaRagService | `SaaRagServiceTest` | 向量召回+重排调用链、双层过滤表达式、空结果降级 |
| LlmReranker | `LlmRerankerTest` | LLM 打分 JSON 解析、降级原始顺序、候选数 ≤5 跳过 |
| DocumentIngestionPipeline | 新建 Test | Tika 读取、切片参数、metadata 构建、状态流转、失败回写 |
| MockEmbeddingModel | `MockEmbeddingModelTest` | 确定性向量生成、L2 归一化、维度一致性 |
| DashScopeEmbeddingModel | 新建 Test | API 调用、维度匹配、批量嵌入、超时处理 |

**示例单元测试：**

```java
@Test
void retrieve_emptyResult_returnsEmptyString() {
    // Given
    when(vectorStore.similaritySearch(any(SearchRequest.class)))
        .thenReturn(List.of());
    // When
    String result = ragService.retrieve("测试查询", 1L);
    // Then
    assertThat(result).isEmpty();
}

@Test
void retrieve_exception_returnsEmptyString() {
    // Given — Embedding 调用失败
    when(vectorStore.similaritySearch(any(SearchRequest.class)))
        .thenThrow(new RuntimeException("Embedding API 超时"));
    // When
    String result = ragService.retrieve("测试查询", 1L);
    // Then — 静默降级为空，不抛异常
    assertThat(result).isEmpty();
}

@Test
void rerank_llmFailure_fallbackToOriginalOrder() {
    // Given
    when(llmService.chat(any(), any(), any(), any()))
        .thenThrow(new RuntimeException("LLM 不可用"));
    // When
    List<ScoredDocument> result = reranker.rerank("查询", List.of("文档A", "文档B"));
    // Then — 降级为原始顺序，默认分数 5.0
    assertThat(result).hasSize(2);
    assertThat(result.get(0).score()).isEqualTo(5.0);
}
```

### 6.2 集成测试

| 测试场景 | 验证内容 |
|---|---|
| 文档上传端到端 | 上传 PDF → 解析 → 切片 → 向量化 → 入库 → `vector_store` 表有记录 |
| 检索端到端 | 写入测试文档 → `ragService.retrieve()` → 返回正确知识段落 |
| 双层过滤隔离 | 群 A 的群专属知识不被群 B 检索到 |
| 重排集成 | 向量召回 20 条 → LLM 重排 → 返回 Top-5 |
| 知识注入集成 | `RagInjectionHook.beforeModel()` 返回 SystemMessage |
| 数据库迁移验证 | `ring_rag` 库表创建、索引存在、数据写入正常 |

### 6.3 性能测试

| 测试项 | 工具 | 目标指标 |
|---|---|---|
| 向量检索延迟 | JMeter | P99 ≤ 200ms（Top-20 检索） |
| 重排延迟（LLM） | 日志统计 | P99 ≤ 2s |
| 检索端到端 | JMeter | P99 ≤ 3s（检索+重排+注入） |
| 文档摄入吞吐 | 日志统计 | ≥ 20 chunks/s |
| 并发检索 | JMeter（50 并发） | 错误率 = 0%，P99 ≤ 500ms |
| 数据库连接池 | HikariCP metrics | 无连接泄漏，池利用率 < 80% |

### 6.4 评估测试

参见 [3.6 向量模型测试方案](#36-向量模型测试方案)。

---

## 7. 部署方案

### 7.1 环境要求

| 组件 | 环境要求 | 备注 |
|---|---|---|
| PostgreSQL 15+ | 安装 pgvector 扩展 | 当前部署于 `101.33.227.80:5432` |
| ring_rag 数据库 | UTF8 编码 | 详见迁移步骤 |
| 应用服务器 | JDK 17+ | 已部署 |
| DashScope API | 需开通阿里云百炼，获取 API Key | 推荐 model: text-embedding-v4 |

### 7.2 配置变更清单

```yaml
# application.yml — 推荐配置变更（当前 Mock → DashScope）

spring:
  ai:
    vectorstore:
      pgvector:
        datasource:
          url: jdbc:postgresql://101.33.227.80:5432/ring_rag  # ← 已变更
          username: postgres
          password: postgres123
          driver-class-name: org.postgresql.Driver
    dashscope:                     # ← 新增
      api-key: ${DASHSCOPE_API_KEY}
      embedding:
        options:
          model: text-embedding-v4
          dimensions: 1024

dingring:
  rag:
    enabled: true
    embedding:
      provider: dashscope          # ← 从 mock 改为 dashscope
```

### 7.3 环境变量

```bash
# 生产环境需配置（通过环境变量注入，不硬编码到 yml）
export DASHSCOPE_API_KEY=sk-xxxxxxxxxxxx
```

### 7.4 部署步骤

1. **数据库准备**：创建 `ring_rag` 库 + 安装 pgvector 扩展
2. **依赖更新**：pom.xml 添加 DashScope Starter 依赖
3. **配置更新**：application.yml 更新 embedding provider + DashScope 配置
4. **代码更新**：新建 DashScopeEmbeddingConfig 配置类
5. **首次启动**：应用自动建表 → PgVectorStore `initializeSchema(true)`
6. **验证**：上传测试文档 → 检索测试 → 确认 RAG 注入

### 7.5 环境隔离

| 环境 | 数据库 | Embedding 模型 | 说明 |
|---|---|---|---|
| dev | ring_rag（共享） | DashScope text-embedding-v4 | 开发联调 |
| prod | ring_rag（独立实例） | DashScope text-embedding-v4 | 生产环境 |

- `application-dev.yml`：dev 环境配置
- `application-prod.yml`：prod 环境配置（需关闭 `dingring.debug.llm.enabled`）

---

## 8. 风险评估及应对措施

| 风险 | 影响 | 概率 | 应对措施 |
|---|---|---|---|
| **Embedding API 超时/限流** | 检索降级为空，RAG 不生效 | 中 | 1）DashScope API 有 SLA 保障；2）检索超时 5s 静默降级；3）可切换为本地 BGE-M3 |
| **向量维度变更需重建表** | 数据中断 | 高 | `DROP TABLE vector_store` → 应用重启自动建表 → 重新摄入文档 |
| **检索延迟过高** | Agent 发言卡顿 | 中 | 1）闲聊态跳过 LLM 重排；2）查询缓存 5 分钟；3）pgvector HNSW 索引调优 |
| **公网 PostgreSQL 连接断连** | 检索失败 | 中 | 已有 HikariCP 短寿命+探活配置（max-lifetime 60s, connection-test-query SELECT 1） |
| **DashScope API Key 泄露** | 数据安全风险 | 低 | 通过环境变量注入，不硬编码到 yml；生产环境不提交到 Git |
| **文档摄入积压** | 知识不及时 | 低 | 1）@Async 异步不阻塞；2）监控 kb_file 表 FAILED 记录数；3）支持重新处理 |
| **向量数据库磁盘满** | 写入失败 | 低 | 1）1024 维比 1536 维省 33% 空间；2）定期清理过期向量 |
| **中文检索质量不达标** | RAG 体验差 | 低 | text-embedding-v4 CMTEB Retrieval 73.98，中文检索最优；有评估方案验证 |

---

## 9. 项目实施时间表

### 阶段 1：数据库迁移 + 配置更新

| 任务 | 依赖 | 交付物 |
|---|---|---|
| 创建 ring_rag 数据库 + pgvector 扩展 | 无 | PG 中 ring_rag 库就绪 |
| 验证 application.yml 配置 | 数据库就绪 | yml 配置已指向 ring_rag |
| 应用启动自动建表 | yml 配置 | vector_store 表 + HNSW 索引 |
| 迁移存量向量数据（如有） | 建表完成 | 数据一致 |
| 验证数据库迁移 | 全部完成 | 检索测试通过 |

### 阶段 2：向量模型集成

| 任务 | 依赖 | 交付物 |
|---|---|---|
| 申请 DashScope API Key | 无 | API Key 就绪 |
| 引入 spring-ai-alibaba-starter 依赖 | 无 | pom.xml 更新 |
| 创建 DashScopeEmbeddingConfig 配置类 | 依赖就绪 | 配置类代码 |
| 调整 MockEmbeddingModel 条件 | 配置类完成 | Mock 仅在 provider=mock 时激活 |
| 切换 embedding provider 为 dashscope | 代码完成 | yml 配置变更 |
| 维度变更处理（1536→1024） | 切换完成 | 重建 vector_store 表 |
| 模型评估测试 | 集成完成 | 评估指标报告 |

### 阶段 3：性能优化与测试

| 任务 | 依赖 | 交付物 |
|---|---|---|
| 单元测试补充 | 阶段 2 完成 | 测试报告 |
| 集成测试（端到端） | 阶段 2 完成 | 端到端测试通过 |
| 性能测试 | 集成测试通过 | 性能指标报告 |
| 查询缓存实现 | 性能测试结果 | Caffeine 缓存集成 |
| 闲聊态降级策略 | 性能测试结果 | 闲聊态跳过重排 |

### 阶段 4：上线

| 任务 | 依赖 | 交付物 |
|---|---|---|
| 生产环境 DashScope Key 配置 | 阶段 3 完成 | 生产环境变量 |
| 生产环境数据库迁移 | 阶段 3 完成 | prod ring_rag 就绪 |
| 生产环境验证 | 全部完成 | 检索功能正常 |
| 监控告警配置 | 上线完成 | RAG 检索延迟/失败率监控 |

---

## 附录 A：关键配置文件索引

| 文件 | 位置 |
|---|---|
| 应用配置 | [application.yml](start/src/main/resources/application.yml) |
| 基础设施 pom | [dingRing-infrastructure/pom.xml](dingRing-infrastructure/pom.xml) |
| RAG 原始设计文档 | [2026-08-03-rag-knowledge-base-design.md](docs/superpowers/specs/2026-08-03-rag-knowledge-base-design.md) |

## 附录 B：参考文档

- [Spring AI Alibaba RAG 官方文档](https://java2ai.com/docs/frameworks/agent-framework/advanced/rag/)
- [Spring AI Alibaba Embedding Model 教程](https://www.java2ai.com/docs/1.0.0-M3.2/tutorials/embedding/)
- [DashScope Text Embedding API 文档](https://help.aliyun.com/en/model-studio/text-embedding-synchronous-api)
- [MTEB Leaderboard](https://huggingface.co/spaces/mteb/leaderboard)
- [BGE-M3 技术报告](https://github.com/FlagOpen/FlagEmbedding)
- [Qwen3-Embedding 技术博客](https://qwenlm.github.io/blog/qwen3-embedding/)