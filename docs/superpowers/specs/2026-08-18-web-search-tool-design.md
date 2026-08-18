# Tool 简化改造技术方案（SAA 原生 methodTools + Tavily WebTools）

- 日期：2026-08-18（同日更新：用户调研确认 Tavily Search/Extract 双模式，工具集扩为 webSearch+webFetch）
- 状态：已评审（实施中）
- 影响模块：dingRing-infrastructure / dingRing-adapter / start
- 前置依赖：SAA 1.1.2.3（`Builder.methodTools()`）、Spring AI `@Tool`/`@ToolParam`、Spring RestClient（Tavily 无 Java SDK，直接 REST 调用）
- 取代：`2026-08-18-tool-mechanism-design.md`（动态装配方案，已废弃）

---

## 1. 背景与诉求

前版方案（Registry/动态装配/AgentCache 四层架构）经评审认定过度设计，用户诉求收敛为三条：

1. 删除 `infrastructure/agent/tool/` 下全部现有工具（3 个 `@Tool` Bean）
2. 新工具用 SAA 原生方式管理：一个普通类 + `@Tool` 注解方法 + `ReactAgent.Builder.methodTools()` 挂载
3. 第一个基础工具实现**网络搜索**

## 2. 供应商调研结论

| 候选 | 结论 | 依据 |
|---|---|---|
| 火山方舟 web_search | **不可用** | 它是 Responses API 的模型侧内置插件（`tools:[{"type":"web_search"}]`，豆包模型自行判断触发），非独立搜索 API；官方文档明确"开启此插件时 Function Calling 功能不生效"，与自定义 @Tool 机制互斥，且模型被绑死为豆包系 |
| **Tavily（选定）** | **采用** | 为 LLM Agent 设计的搜索 API（结构化 url/title/content/score）；支持 **keyless 模式**（`X-Tavily-Access-Mode: keyless` 头，零注册零 Key）；注册后 1000 credits/月免费额度，配置 Key 后代码零改动 |
| 智谱 web-search-pro / 博查 | 备选 | 国内直连稳定，但均需新注册账号 + 按次付费，学习项目无必要 |

## 3. 设计

### 3.1 删除清单

| 删除 | 理由 |
|---|---|
| `KnowledgeSearchTool` / `TopicHistoryTool` / `UserProfileQueryTool` | 诉求 1；且前两者与 Hook Push 注入重复（InjectKbHook/MemoryInjectionHook 已覆盖），后者调用率趋零 |
| `SaaLlmFactory.resolveTools()` switch + 3 个工具 Bean 注入 | 被 `methodTools()` 取代 |

**保持不变**：`LlmService.ToolSet` 枚举与签名（4 个 Node、MockLlmService 零改动）、全部 Hook（Push 侧照旧，群聊知识库注入不受影响）、`recursionLimit=40`。

**影响说明**：删除 `KnowledgeSearchTool` 后，知识库信息仅通过 `InjectKbHook` Push 注入（检索仍由 RAG 管道执行），Agent 失去"迭代改写 query 二次检索"能力——当前知识库规模下可接受，未来需要时再以 @Tool 形式重建。

### 3.2 新增 `WebTools`（infrastructure/agent/tool/，一个类管理全部联网工具）

两个 `@Tool` 方法构成完整 ReAct 检索链（基于用户调研的 Tavily Search/Extract 双模式）：

```java
@Slf4j
@Component
public class WebTools {

    @Value("${dingring.tool.web-search.api-key:}")
    private String apiKey;          // 空 = keyless 免费模式
    private final RestClient restClient;  // Tavily 无 Java SDK，直接 REST（同步匹配工具执行）

    @Tool(description = "联网搜索最新信息。当问题涉及实时资讯、新闻、近期事件或模型不确定的事实时调用")
    public String webSearch(@ToolParam(description = "搜索关键词") String query) {
        // POST /search  body: {query, max_results: 5, search_depth: "basic", include_answer: "basic"}
        // include_answer=basic：Tavily 合成参考答案置顶（免费 mini-RAG）
        // search_depth=basic：1 credit 低延迟（advanced 2 credits ~4.5s，群聊等待感明显）
        // 输出：【参考答案】...\n【来源】[n] 标题 (url)\n摘要（单条 300 字，总量 2000 字）
    }

    @Tool(description = "读取指定网页的正文内容。当搜索结果的摘要不足以回答问题时，用它深入阅读该网页")
    public String webFetch(@ToolParam(description = "网页 URL") String url) {
        // POST /extract  body: {urls: [url], format: "markdown"}
        // format=markdown：与 search content 格式统一（保留标题/列表结构）
        // schema 只暴露单 url：ReAct 逐步深挖模式（Extract API 支持批量 20 个但不用）
        // 响应含 results[].title/raw_content + failed_results[].url/error（用户调研确认）：
        //   成功 → 【网页】标题 (url)\n正文（截断 3000 字）
        //   失败 → 带回 url+error 供模型自纠（换来源或基于摘要回答），而非笼统提示语
    }
}
```

关键决策：

- **Java 调用方式**：Tavily 官方仅 Python/JS SDK，Java 侧 Spring `RestClient` 直调 REST——同步调用匹配工具顺序执行，Jackson record 直接映射响应
- **schema 最小化**：webSearch 只暴露 query、webFetch 只暴露 url——参数越少模型调用越准，max_results/format 等收敛在 Java 侧
- **结果截断**：工具输出回流进模型上下文——搜索单条 300 字/总量 2000 字、提取 3000 字
- **失败不抛异常**：返回提示语（"联网搜索暂不可用，请基于已有知识回答"），防 ReAct 循环因报错反复重试

### 3.3 装配改造（SaaLlmFactory）

```java
// 删除 3 个工具 Bean 字段与 resolveTools()；新增 WebTools 注入
// build() 内：
if (toolSet != ToolSet.CONCLUDE) {
    builder.methodTools(webTools);  // SAA 原生：扫描类内全部 @Tool 方法注册
}
```

挂载矩阵（用户确认）：

| 场景 | 挂载 | 说明 |
|---|---|---|
| CHAT | ✅ | 闲聊随口问实时问题（天气/新闻）合理 |
| DISCUSS | ✅ | 讨论中查证事实 |
| WORK | ✅ | 深度 ReAct 主力场景 |
| CONCLUDE | ❌ | 收束做总结，无需外查 |

### 3.4 连带修复（编译必需）

- `SkillToolkitFactory`：注入被删的 3 个 Bean 会编译失败 → 改为注册 `WebTools`（webSearch+webFetch 两方法都进注册表）
- `skill-config.json` 种子：引用的旧工具名（searchKnowledge 等）改为 `webSearch,webFetch`（未改名工具 WARN 跳过本就是设计容错，改种子是为消除启动噪音）

### 3.5 配置

```yaml
dingring:
  tool:
    web-search:
      api-key: ""   # 留空走 Tavily keyless 免费模式；填 Key 走 1000次/月免费额度
```

### 3.6 Debug 端点（adapter/rest，复用 TestController 模式）

- `GET /api/test/tool/web-search?query=...` → 调 `webTools.webSearch(query)` 验证搜索链路
- `GET /api/test/tool/web-fetch?url=...` → 调 `webTools.webFetch(url)` 验证网页提取链路

均 `@ConditionalOnProperty` 控制开关。用于不经过 LLM 单独验证工具链路。

## 4. 错误处理

| 故障点 | 策略 |
|---|---|
| Tavily 不可达/超时 | catch 后返回"联网搜索暂不可用，请基于已有知识回答"提示语 |
| keyless 限流 | 同上（keyless 有速率限制，提示语引导配 Key） |
| 结果为空 | 返回"未搜索到相关结果"提示语 |
| API Key 无效 | 401 同样落入提示语路径，日志 WARN 记录状态码 |

## 5. 测试策略

1. **Debug 端点**：`GET /api/test/tool/web-search?query=今天AI新闻` → 验证 Tavily 调用、格式化输出、截断
2. **端到端**：群聊发"帮我查下最近的 AI 大新闻" → 日志确认 ReactAgent 触发 `webSearch` 工具调用并基于结果回答
3. **回归**：CHAT/DISCUSS/WORK/CONCLUDE 四场景发言正常；Skill 加载无 WARN

## 6. 实施步骤

| # | 内容 | 验证 |
|---|---|---|
| 1 | 新建 `WebSearchTools` + yml 配置 + debug 端点 | debug 端点返回真实搜索结果 |
| 2 | `SaaLlmFactory` 删 switch/3 Bean，挂 `methodTools` | 编译通过，群聊端到端触发工具 |
| 3 | 删 3 个旧工具类；`SkillToolkitFactory` 改注册 WebSearchTools；`skill-config.json` 种子改名 | 启动无 WARN，四场景回归 |
| 4 | v2 方案文档标废弃（已随本文档提交） | — |

## 7. 风险

| 风险 | 缓解 |
|---|---|
| keyless 模式有速率限制（未披露具体值） | 学习项目可接受；限流提示语引导配免费 Key |
| 海外 API 国内访问波动 | 本机开发环境实测可达；超时 15s + 提示语兜底 |
| Tavily 响应字段变化 | 解析按官方 schema 固定字段，异常统一落提示语路径 |
