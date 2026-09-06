# P3 块类型标注细则（Block Type Annotation）

> 版本：1.2 ｜ 日期：2026-09-05
>
> 隶属：[P3 RAG 文档清洗、结构化切分与多阶段召回技术方案](p3-rag-cleaning-semantic-chunking-design.md)。本文档三部分：§7 Chunk Metadata 字段参考（v1 生效，对应方案 §6.2）、§8 Markdown 解析器状态参考（v1 生效，对应方案 §4.1）；§1～§6 块类型标注为后续迭代启用（v1 清洗为 LLM 全文模式，见方案 §3.2 后续迭代清单）。

## 1. 定位与用途

本细则定义 10 个语义类型的准确含义、识别标准和下游作用，供四类读者使用：

- **清洗 prompt / MCP 工具描述的编写者**：枚举语义必须收敛，否则内置 LLM 与外部 Agent（TRAE / Claude Code）打标口径不一致，`type` 无法驱动下游策略；
- **服务端校验实现者**：判断标注是否合理（如 code 块被标成 concept 应拒绝）；
- **切分与检索实现者**：按类型分派切分策略与重排提示；
- **评测集建设者**：按类型覆盖问题分布。

## 2. 类型定义

| 类型 | 含义 | 识别标准 | 下游作用 |
|---|---|---|---|
| `overview` | 概述/总览 | 文档或章节开篇的整体介绍，说明主题、范围与结构 | 回答"X 是什么"的高频命中区 |
| `concept` | 概念/原理 | 术语定义、机制解释、背景知识，不含操作指令 | 概念问答主力，dense 检索主要命中对象；也是打标兜底类型（见规则 2） |
| `procedure` | 操作步骤 | 有序操作指引：安装、配置、使用流程，通常伴随有序列表 | 切分保护重点：步骤组不可切断（方案 §4.3、§5.2）；回答"怎么做" |
| `api` | 接口参考 | 方法签名、参数、返回值、异常说明 | 精确词检索重点（类名/方法名/错误码，方案 §7.2）；重排时提示模型这是精确参考 |
| `configuration` | 配置项 | 配置键、默认值、取值范围 | 同 `api`；清洗保真校验重点（配置键不允许被修改） |
| `faq` | 问答对 | "Q: … A: …"结构或明确的问-答段落 | 切分绑定：问题与答案必须在同一 chunk（方案 §5.2）；直接匹配口语化提问 |
| `example` | 完整示例 | 带解释的使用演示、用例场景（区别于裸代码块） | 回答"怎么用/举个例子" |
| `code` | 代码块 | fenced code block 本身 | 原子保护（方案 §4.3 不被滑窗截断）；超长按类/方法语法边界拆分；`cleanText` 保真要求最严格（逐 token 不许动） |
| `table` | 表格 | Markdown table：参数表、版本对照、枚举值 | 按行组拆分且每块重复表头；数字/版本号精确查询来源 |
| `navigation` | 导航/噪声 | 目录、分页导航、重复页眉页脚、面包屑、无信息免责声明 | **唯一允许 `keep=false` 的类型**，清洗时丢弃 |

## 3. 语义类型与结构类型的关系

方案中存在两层类型体系，不可混淆：

- **结构类型**（方案 §4 解析器输出）：paragraph / list / code / table / quote / heading 等，靠 Markdown 语法即可确定，确定性解析，不依赖 LLM；
- **语义类型**（本细则的 10 个枚举）：表达内容的功能角色。`code`/`table` 结构上可判，`concept`/`procedure`/`faq` 等只能靠理解内容判断——这正是清洗需要 LLM 的原因。

两层结果在 chunk metadata 汇合（方案 §6.2）：

```json
{
  "contentType": "procedure",           // 语义主导类型（LLM 标注）
  "blockTypes": ["paragraph", "list"]   // 结构构成（解析器输出）
}
```

## 4. 打标规则

1. **单块单主导类型**：一个 block 只标一个主导 `type`；混合内容（如"示例说明+代码"）标主导类型，结构构成由 `blockTypes` 记录；
2. **兜底规则**：语义模棱两可且无明确主导时，回退 `concept`（最通用的正文类型），并在 `reason` 中说明不确定原因；
3. **navigation 白名单**：`keep=false` 仅允许 `navigation` 类型——这是枚举中唯一"决定要不要保留"的类型，其余 9 个只影响"怎么切、怎么检"。宁可标 `concept` 也不标 `navigation`，防止误删正文；
4. **code/table 双重身份**：解析器可从语法识别，清洗协议仍要求标注（保证枚举完整、校验可交叉验证），且这两类的 `cleanText` 保真要求最严格；
5. **不得虚构类型**：`type` 只能取 10 个枚举值之一，超出即校验失败（方案 §3.2）。

## 5. 下游消费点

| 消费点 | 使用的类型 | 说明 |
|---|---|---|
| 方案 §3.2 keep 决策 | `navigation` | 白名单去噪 |
| 方案 §5.2 切分策略分派 | `procedure`/`api`/`configuration`/`faq`/`code`/`table`/`example` | 按类型选择聚合与拆分策略 |
| 方案 §6.1 embedding 文本前缀 | 主导类型（contentType） | "内容类型：{contentType}" |
| 方案 §6.2 metadata | contentType + blockTypes | 语义与结构双记录 |
| 方案 §7.3 重排输入 | contentType | 帮助重排模型判断证据性质（精确参考 vs 概念解释） |
| 方案 §10.2 评测集 | 全部 | 问题分布按类型覆盖 |

## 6. 演进约定

新增类型时：先在本细则定义含义、识别标准与下游作用，再更新清洗协议枚举与校验白名单，最后评估存量数据是否需要重标（依赖 documentVersion 重摄入）。

## 7. Chunk Metadata 字段参考（方案 §6.2）

pgvector 每行 = (id, content, metadata, embedding)。向量只负责"语义相似"，其余一切——谁能看、是哪一版、从哪来、和谁相邻、重不重复、哪套工序产出——全靠 metadata。

### 7.1 权限与版本过滤（检索 SQL 的 WHERE 条件）

| 字段 | 含义与作用 |
|---|---|
| `kbId` | 所属知识库。检索时按 `kbId IN (群绑定集合)` 过滤——群绑定在查询时从 MySQL 解析，不入向量（方案 §6.2） |
| `active` | 生效版本标记：多版本共存时只检 active；版本原子切换（方案 §9.1）与软删除依赖它 |
| `documentVersion` | 文档版本号：对账任务据此发现非 active 版本残留（方案 §9.2） |

`tenantId` 延后引入（方案 §13.7 已定先不引入）：将来多租户时统一加字段并全量重摄入，与切分器更换的存量重刷一并做。

### 7.2 溯源与引用（"每个结果可追溯"）

| 字段 | 含义与作用 |
|---|---|
| `fileId` | 所属文件：删除文件/知识库时按它定位清理向量 |
| `chunkId` | chunk 唯一标识（`{fileId}-v{version}-{index}` 格式）：重排输入的 candidateId、引用锚点 |
| `chunkIndex` | 文件内序号：邻居扩展（±1 找前后 chunk）与引用展示 |
| `parentChunkId` | 父块（章节级）标识：父块扩展——命中子块后补全章节上下文（方案 §7.3） |
| `headingPath` | 标题栈面包屑：embedding 前缀"章节"（方案 §6.1）、重排输入、引用展示 |
| `sourceStartLine` / `sourceEndLine` / `sourceStartOffset` / `sourceEndOffset` | 清洗版中的原文位置：点击引用跳转原文区间 |

### 7.3 去重与一致性

| 字段 | 含义与作用 |
|---|---|
| `docContentHash` | 原始文件（清洗前）指纹，文档级：版本溯源、摄入幂等、外部提交过期防护（方案 §3.4.2） |
| `chunkContentHash` | chunk 文本指纹，块级：dense/lexical 双路召回去重、跨文件相同内容去重（方案 §7.3） |

两级 hash 不可混用：同文件所有 chunk 共享 `docContentHash`，文档级 hash 不能用于 chunk 去重。

### 7.4 工序版本与结构记录

| 字段 | 含义与作用 |
|---|---|
| `cleaningVersion` / `chunkingVersion` | 清洗/切分工序版本：工序参数变更后据此识别需重刷的存量向量，避免全量盲刷 |
| `embeddingModel` | 产出向量的模型：不同模型的向量不可比，换模型须按此字段识别并重刷 |
| `blockTypes` | 结构构成（解析器输出）：切分策略输入的快照；语义类型 `contentType` 为后续迭代（本文档 §1～§6） |

### 7.5 设计要点

- **为什么放向量表而不是 MySQL**：权限过滤必须与向量检索在同一条 SQL 内完成（`kbId IN (...) AND active`），跨库无法 join pgvector；溯源字段跟着向量行走，读取时一次带全；
- **对比 P2 现状**：P2 metadata 仅有 kbId/fileId/fileName/chunkIndex，只够"删除不残留"；本套 metadata 从"能删"升级到"能管版本、能溯源、能扩展、能复现"，对应方案 §1 问题 5。

## 8. Markdown 解析器状态参考（方案 §4.1）

单遍扫描状态机的状态即**结构类型的来源**：扫描结束后每个 block 携带类型，最终写入 chunk metadata 的 `blockTypes`（§7.4）。状态转移优先级：**先判 `CODE_FENCE` 与 `FRONT_MATTER`（防止围栏/YAML 内部的 `#`、`|` 被误判），再判 `HEADING` 与 `TABLE`，最后普通行落 `PLAIN` 聚合为 `PARAGRAPH`**。

| 状态 | 含义（识别什么） | 作用（下游影响） |
|---|---|---|
| `FRONT_MATTER` | 文档开头 `--- ... ---` 包裹的 YAML 块 | 整体识别为特殊 block，不进正文聚合；其中 `title` 可作 documentTitle 兜底；**必须最先判断**——否则其中的 `#` 键误判为标题、`-` 项误判为列表 |
| `PLAIN` | 扫描默认/兜底状态：尚未归入其他结构的普通行 | 连续 PLAIN 行聚合为 PARAGRAPH；任何行先落此状态，再被高优先级状态"抢走" |
| `HEADING` | ATX 标题行（`#`～`######`） | 进入 headingStack，为其后所有 block 生成 headingPath（方案 §4.2）；小块聚合的**硬边界**（§5.1 不跨越标题合并） |
| `PARAGRAPH` | 连续普通行聚合成的段落 block | §5.1 小块聚合的基本单元：相邻短段落可合并；超长走 §5.3 句子级语义切分 |
| `LIST` | 连续列表项（`-`/`*`/`1.`，含嵌套缩进） | §4.3 原子保护：有序操作步骤不可切断；超长按列表项组拆分（§5.2） |
| `QUOTE` | block quote（`>` 前缀行） | 保持引用层级；超长按引用段落拆分（§5.2） |
| `CODE_FENCE` | fenced code block（三反引号围栏） | §4.3 原子保护：内部不解析（`#`/`|` 不误判——它优先级最高的原因）、不被滑窗截断；保留 language；超长按类/方法语法边界拆分 |
| `TABLE` | Markdown 表格（`|` 分隔行 + `---` 分隔行） | 保持表头语义；超长按行组拆分且每块重复表头（§5.2） |
| `HTML_BLOCK` | HTML 残片（`<div>` 等标签块） | 整体保留、内部不解析（防 HTML 内标记误判）；不与 Markdown 段落混合；典型来源是网页导出的格式噪声，正是清洗要处理的对象 |
| `THEMATIC_BREAK` | 分割线（`---`/`***`/`___`） | 不产生内容 block；注意与 front matter、setext 标题下划线的三重歧义——文档首行的 `---` 归 FRONT_MATTER 判定 |

状态与 `blockTypes` 的对应：`PARAGRAPH`→`paragraph`、`LIST`→`list`、`CODE_FENCE`→`code`、`TABLE`→`table`、`QUOTE`→`quote`；`FRONT_MATTER` / `HTML_BLOCK` 产出特殊块，`THEMATIC_BREAK` / `PLAIN` 仅作扫描过程状态，均不直接进入 metadata。
