# 用户问卷画像采集设计

- 日期：2026-09-02
- 状态：已确认（设计评审通过）
- 范围：DingRingJ 单用户多 Agent AI 群聊学习系统

## 1. 背景与目标

### 1.1 现状

DingRingJ 的用户画像体系目前完全依赖 LLM 从对话中**被动提炼**：

- 全局画像 `user_profile`：需闲聊累计 15 条（`ProfileExtractNode` 阈值）才触发，冷启动阶段为空白；
- 话题画像 `user_topic_profile`：需讨论收束后才有 `understandingLevel` 等评估；
- 用户实体 `User` 仅 5 字段（id/name/profilePicture/feature/createTime/updateTime）；
- 不存在任何问卷、用户偏好设置、用户标签的代码与数据模型。

### 1.2 目标

引入**静态结构化问卷**，作为用户画像的**主数据源**：

1. 主动、事前、结构化地采集 LLM 难以可靠推断的维度（背景、目标、水平、风格偏好）；
2. 填补冷启动空白：提交后即刻生成画像文本并注入所有群的 Agent 上下文；
3. LLM 提炼结果作为后续增量修正，问卷原始答案始终为可追溯的事实源。

### 1.3 需求决策记录

| 决策点 | 结论 |
|---|---|
| 问卷定位 | 画像主数据采集（LLM 提炼为补充修正） |
| 采集维度 | 6 维度：背景与经验、学习目标与动机、当前关注领域、水平自评、讨论风格偏好、知识呈现偏好 |
| 触发时机 | 仅设置页入口（无常驻引导、不强制）；已填用户可随时重填 |
| 问卷形式 | 静态结构化：固定题目 + 固定选项 + 少量可选填空 |
| 数据方案 | 方案 B：新表 `user_questionnaire` 存结构化答案（事实源）+ 模板拼接生成 `user_profile` 新版本（消费视图），复用 `ProfileInjectionHook` 注入链路 |
| 明确排除 | 目标时限维度、对话式/LLM 动态出题、强制 onboarding、多用户注册登录、问卷驱动 Agent 调度参数 |

## 2. 问卷内容设计

共 **13 题、6 维度**，约 3-5 分钟完成。填空题可选，选择题必有值。答案 key 使用英文枚举编码（如 `MID`），与中文文案解耦，问卷文案改版不影响存量数据。

### 维度 1：背景与经验（3 题）

| # | key | 题目 | 类型 | 选项（枚举编码） |
|---|---|---|---|---|
| 1 | `career` | 你的职业身份 | 单选 | STUDENT（在校学生）/ JUNIOR（初级工程师 0-2 年）/ MID（中级工程师 3-5 年）/ SENIOR（高级工程师 5 年+）/ CAREER_CHANGE（转行学习者） |
| 2 | `directions` | 主要技术方向 | 多选 | BACKEND / FRONTEND / MOBILE / DATA_AI / INFRA / TESTING / ARCHITECTURE |
| 3 | `techStack` | 主力技术栈 | 填空(可选) | 如"Java / Spring Boot / MySQL / Vue" |

### 维度 2：学习目标与动机（2 题）

| # | key | 题目 | 类型 | 选项（枚举编码） |
|---|---|---|---|---|
| 4 | `motivations` | 学习的主要目的 | 多选 | INTERVIEW（面试冲刺）/ WORK_PROBLEM（工作难题攻坚）/ INTEREST（兴趣驱动）/ SYSTEMATIC（系统性进阶）/ CAREER_SWITCH（转行准备）/ CERTIFICATION（考证认证） |
| 5 | `goalDescription` | 一句话描述你的学习目标 | 填空(可选) | 如"三个月搞定 JVM 调优" |

### 维度 3：当前关注领域（2 题）

| # | key | 题目 | 类型 | 选项（枚举编码） |
|---|---|---|---|---|
| 6 | `focusAreas` | 正在学/关注的方向 | 多选 | JVM / CONCURRENCY / DISTRIBUTED / DATABASE / REDIS / NETWORK / OS / FRAMEWORK_SOURCE / AI_LLM / FRONTEND_ENGINEERING |
| 7 | `learningNow` | 具体正在学的内容 | 填空(可选) | 如"Netty 源码、DDD 落地" |

### 维度 4：水平自评（1 题，联动）

| # | key | 题目 | 类型 | 选项（枚举编码） |
|---|---|---|---|---|
| 8 | `areaLevels` | 各关注领域水平自评 | 联动单选 | 对 `focusAreas` 选中的每个领域分别自评：BEGINNER（入门，听过概念）/ ELEMENTARY（初级，能跟着做）/ INTERMEDIATE（中级，能独立解决）/ ADVANCED（高级，能深入原理） |

值为 `Map<领域枚举, 水平枚举>`，key 集合必须与 `focusAreas` 一致。水平枚举语义对齐 `UserTopicProfile.understandingLevel`（BEGINNER/INTERMEDIATE/ADVANCED），为话题画像提供基线参照。

### 维度 5：讨论风格偏好（3 题）

| # | key | 题目 | 类型 | 选项（枚举编码） |
|---|---|---|---|---|
| 9 | `discussionDepth` | 讨论深度 | 单选 | DEEP_THEORY（深度原理派，追根究底）/ PRAGMATIC（实战落地派，快速给方案）/ BALANCED（均衡） |
| 10 | `discussionPace` | 讨论节奏 | 单选 | DIVERGE_THEN_CONVERGE（发散脑暴再收敛）/ FAST_CONVERGE（快速收敛直奔结论）/ BALANCED（均衡） |
| 11 | `interactionStyle` | 互动方式 | 单选 | SOCRATIC（喜欢被追问挑战）/ GUIDED（喜欢循循善诱）/ DIRECT（直接给答案） |

### 维度 6：知识呈现偏好（2 题）

| # | key | 题目 | 类型 | 选项（枚举编码） |
|---|---|---|---|---|
| 12 | `presentationForms` | 偏好的讲解形式 | 多选 | CODE_EXAMPLE（代码示例）/ ANALOGY（类比举例）/ SOURCE_ANALYSIS（源码分析）/ DIAGRAM（图解步骤）/ DOC_REFERENCE（官方文档引用） |
| 13 | `terminologyLanguage` | 术语语言习惯 | 单选 | CHINESE_FIRST（中文优先）/ MIXED（中英混用，术语保留英文）/ ENGLISH_FIRST（英文优先） |

### 画像文本生成示例

`QuestionnaireProfileAssembler` 模板拼接（确定性输出，零 LLM 依赖）：

```text
- 背景：中级后端工程师（3-5 年），主力技术栈 Java/Spring Boot/MySQL
- 学习目标：面试冲刺，"三个月搞定 JVM 调优"
- 关注领域：JVM（中级）、并发编程（初级）、框架源码（入门），正在学 Netty 源码
- 讨论偏好：深度原理派，发散脑暴，可被追问挑战
- 呈现偏好：偏好源码分析与图解步骤，术语中英混用
```

可选填空为空时跳过对应片段；单选默认取枚举首项。

## 3. 数据模型与架构

### 3.1 新表 `user_questionnaire`（仿 `user_profile` 版本化模式）

```sql
CREATE TABLE user_questionnaire (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id     BIGINT   NOT NULL,
    answers     JSON     NOT NULL,
    version     INT      NOT NULL DEFAULT 1,
    status      TINYINT  NOT NULL DEFAULT 1,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_user_status (user_id, status)
) COMMENT '用户问卷答案（画像事实源，版本化）';
```

- `answers`：`{"career":"MID","directions":["BACKEND"],"focusAreas":["JVM","CONCURRENCY"],"areaLevels":{"JVM":"INTERMEDIATE","CONCURRENCY":"ELEMENTARY"},...}`
- `status`：1=有效 / 0=历史版本，重填保留成长轨迹（与 `user_profile` 一致）。

### 3.2 领域层组件（遵循现有 DDD 分层）

| 组件 | 位置 | 职责 |
|---|---|---|
| `UserQuestionnaire` 实体 | `dingRing-domain/.../user/` | userId、answers(Map)、version、status |
| `UserQuestionnaireRepository` 端口 | `dingRing-domain/.../user/` | `findValidByUserId` / `saveNewVersion` |
| `QuestionnaireDefinition` | `dingRing-domain/.../user/` | 题目 schema 常量（题目 key、类型、选项枚举与中文文案），校验与拼接的唯一契约源 |
| `QuestionnaireProfileAssembler` | domain service（`dingRing-domain/.../service/`） | 答案 Map → 画像文本，纯模板拼接 |
| `QuestionnaireAppService` | `dingRing-app/.../service/` | 编排：校验 → 事务内双写 |
| `QuestionnaireController` | `dingRing-adapter/` | REST 接口 |
| `UserQuestionnaireRepositoryImpl` + Mapper XML | `dingRing-infrastructure/` | MyBatis 持久化实现 |

模块依赖方向不变：`adapter → app → domain ← infrastructure`。

### 3.3 API 设计

| 接口 | 方法 | 说明 |
|---|---|---|
| `/api/questionnaire` | GET | 返回题目 schema + 当前有效 answers（未填过返回空 answers，前端按 schema 默认值渲染） |
| `/api/questionnaire` | POST | 提交 answers；单事务完成：校验 → `user_questionnaire` 存新版本（事实源）→ 拼接画像文本 → `user_profile` 存新版本（消费视图） |

响应包裹沿用 `ApiResponse`；校验失败抛 `BizException`（新增 ErrorCode，400 类参数错误）。

### 3.4 数据流

```dot
digraph { rankdir=LR;
  form [label="设置页问卷表单(分步)"];
  ctrl [label="QuestionnaireController"];
  app  [label="QuestionnaireAppService(校验+事务)"];
  qr   [label="user_questionnaire 答案事实源(版本化)"];
  asm  [label="QuestionnaireProfileAssembler 模板拼接"];
  up   [label="user_profile 画像文本(新版本)"];
  hook [label="ProfileInjectionHook(现有,零改动)"];
  agents [label="所有群的 AI 同事"];
  form -> ctrl -> app -> qr; app -> asm -> up; up -> hook -> agents [style=dashed];
}
```

### 3.5 与 LLM 画像的融合规则

- 问卷画像作为 `user_profile` 新版本写入后，现有 `SimpleProfileService` 的 LLM 增量合并以其为基底自然演化；
- 用户重填问卷 = 主动重置基线：覆盖生成新画像版本（简单、可预期），原始答案始终保留在 `user_questionnaire`；
- 画像文本不标记来源（问卷/LLM），事实溯源依赖 `user_questionnaire` 表本身。

## 4. 前端设计

- **入口**：侧边栏新增「个人画像」页，路由注册沿用现有页面模式（如 Agents 管理页）；不做引导弹窗。
- **表单**：分步向导 4 步走完 6 维度：
  1. 背景与目标（题 1-5）
  2. 关注领域与水平（题 6-8，选完领域后展开各领域水平单选）
  3. 讨论风格（题 9-11）
  4. 呈现偏好（题 12-13）
- **已填状态**：进入页面回显当前有效 answers，修改后保存即重填（生成新版本）。
- **渲染方式**：按 GET 接口下发的 schema 动态渲染，题目迭代无需发前端版。

## 5. 错误处理

| 场景 | 处理 |
|---|---|
| 答案 key 不在 schema / 值不在选项内 / `areaLevels` 与 `focusAreas` 不一致 | `BizException` + 新 ErrorCode（参数错误）；前端表单预校验兜底 |
| 画像文本写入失败 | 与问卷答案同事务整体回滚，避免"有答案无画像"中间态 |
| 并发提交 | 单用户场景概率极低，v1 以事务保证一致；如需更强一致可复用 `SimpleProfileService` 的用户维度串行锁模式（范围外） |

## 6. 测试策略

- **单元测试**：`QuestionnaireProfileAssembler`（给定答案 → 断言画像文本片段，覆盖多选/联动/可选空值）；答案校验逻辑（合法/非法/缺省 key/联动不一致）。
- **集成测试**：Repository 版本化保存与有效版本读取（遵循现有 MyBatis 测试模式）。
- **API 测试**：GET 回显、POST 提交-校验-落库-画像生成全链路。

## 7. 范围外（v1 明确不做）

- 对话式 / LLM 动态出题问卷
- 强制 onboarding 引导流程
- 多用户注册登录体系（表结构已按 `user_id` 预留，当前仍用 `DEFAULT_USER_ID`）
- 问卷结果直接驱动 Agent 人设配置、发言调度参数（v2 候选：按"互动方式"偏好调整 Moderator 行为）
- 画像文本中英双语
