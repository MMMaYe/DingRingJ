# 主题沉淀区 (Topic Sedimentation) 设计文档

## 背景

群聊中 Agent 单次发言量限制在 100-200 字以提升讨论效率，代价是失去部分深度分析。
深度分析的核心产物——讨论结论（STAR 框架 Markdown）和知识卡片——目前仅散落在
Chat 页面右侧信息面板的"历史主题"区块中，缺少独立承载页面。

## 目标

新增顶级页面"主题沉淀区"，跨群展示所有已关闭讨论主题的深度分析内容
（结论 + 知识卡片），采用左右分栏 master-detail 布局，供用户回顾沉淀的知识。

## 设计决策

- **页面范围**：跨群展示全部已关闭主题（与知识卡片管理页一致）。
- **Chat 面板**：保持不变，不删除现有"历史主题"区块。
- **布局**：左右分栏 master-detail，左侧主题列表，右侧结论详情。

## 后端改动

### 新增 DTO: `TopicDigest`

`dingRing-app/.../dto/response/TopicDigest.java`

| 字段 | 类型 | 说明 |
|------|------|------|
| id | Long | 主题 ID |
| title | String | 主题标题 |
| groupId | Long | 来源群 ID |
| groupName | String | 来源群名 |
| messageCount | long | 消息数 |
| closedAt | LocalDateTime | 关闭时间 |
| createTime | LocalDateTime | 创建时间 |

### Repository 层

- `TopicRepository.findAllClosed()`: 新增接口方法，返回所有 status='CLOSED' 的主题（按 closed_at DESC）。
- `TopicMapper`: 新增 `findAllClosed` 查询。
- `TopicMapper.xml`: 新增对应 SQL。
- `TopicRepositoryImpl`: 实现转发。

### AppService 层

`TopicAppService.listAllClosed()`:
1. `topicRepository.findAllClosed()` 取全部已关闭主题。
2. `groupRepository.findAll()` 构建 groupId->groupName 映射，批量填充群名。

### Controller 层

`TopicController` 新增端点：`GET /api/topics/closed` -> `List<TopicDigest>`

### 复用现有 API

- `GET /api/topics/{id}/conclusion`：结论 Markdown（点击左侧主题时加载右侧详情）。
- `GET /api/topics/{id}/cards`：主题生成的知识卡片。

## 前端改动

### 导航与路由

- `Sidebar.tsx`：在"群聊"与"知识卡片管理"之间新增"主题沉淀区"导航项（scroll-text 图标）。
- `App.tsx`：新增 `/topics` 路由（懒加载）。

### 类型

`types.ts` 新增 `TopicDigest` 类型。

### 新页面 `pages/Topics/`

- `index.tsx`：master-detail 页面。
- `style.css`：页面样式。

**布局结构：**
- editorial 风格页头：eyebrow + serif 标题 + 统计（主题总数、覆盖群数）。
- 左侧列表：可搜索的主题列表，每项显示标题、来源群名标签、关闭时间、消息数，选中态高亮。
- 右侧详情：选中主题后展示结论全文（Markdown 渲染，复用 `renderMarkdown`）+ 生成的知识卡片列表。
- 未选中时右侧显示空状态引导。
- 复用 Chat 页面的 `renderMarkdown` 工具函数。

## 不改动

- Chat 页面右侧信息面板的"历史主题"区块完全保留。
- 知识卡片管理、知识库管理、Agent 管理页面不动。
