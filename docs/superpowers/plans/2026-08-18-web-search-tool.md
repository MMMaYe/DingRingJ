# WebSearch Tool 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 删除 3 个旧工具 Bean，新增 Tavily 联网搜索工具（SAA `methodTools()` 挂载），CHAT/DISCUSS/WORK 三场景生效。

**Architecture:** 单类 `WebSearchTools`（`@Tool` 注解方法）+ `SaaLlmFactory`/`SupervisorAgentFactory` 挂载点替换 + `SkillToolkitFactory` 注册表更新。工具输出截断防 token 溢出，失败返回提示语不抛异常防 ReAct 死循环。

**Tech Stack:** Spring AI `@Tool`/`@ToolParam`、SAA `Builder.methodTools()`、Spring `RestClient`（Jackson 反序列化）、Tavily Search API。

**对应方案:** `docs/superpowers/specs/2026-08-18-web-search-tool-design.md`

**已完成前置:** application.yml 已加 `dingring.tool.web-search.api-key`（用户已提供真实 Key）。

**重要约束:** 工作区有未提交改动（`SaaLlmFactory`/`ReactAgentLlmService`/`SystemMessageMergeHook`，对应 GroupContextMemoryHook 重构）。修改 `SaaLlmFactory` 时**只动工具相关代码**，保留 `groupContextMemoryHook` Hook 链、`@Event` 注解与 TODO 注释。

---

### Task 1: WebSearchTools 工具类（TDD）

**Files:**
- Create: `dingRing-infrastructure/src/main/java/com/dingring/infrastructure/agent/tool/WebSearchTools.java`
- Test: `dingRing-infrastructure/src/test/java/com/dingring/infrastructure/agent/tool/WebSearchToolsTest.java`

- [ ] **Step 1: 写失败的单元测试（纯函数部分：格式化/截断）**

```java
package com.dingring.infrastructure.agent.tool;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebSearchTools 纯函数单测：结果格式化与截断。
 * HTTP 调用链路由 debug 端点（/api/test/tool/web-search）手动验证，不在此 mock。
 */
class WebSearchToolsTest {

    private WebSearchTools.TavilyResult result(String title, String url, String content) {
        return new WebSearchTools.TavilyResult(title, url, content, 0.9);
    }

    @Test
    void 空结果返回提示语() {
        assertEquals("未搜索到相关结果", WebSearchTools.formatResults(null));
        assertEquals("未搜索到相关结果", WebSearchTools.formatResults(List.of()));
    }

    @Test
    void 正常结果带编号标题URL() {
        String out = WebSearchTools.formatResults(List.of(
                result("AI 新闻", "https://a.com", "今天发布了新模型")));
        assertTrue(out.startsWith("[1] AI 新闻 (https://a.com)"));
        assertTrue(out.contains("今天发布了新模型"));
    }

    @Test
    void 单条摘要超300字截断加省略号() {
        String longContent = "字".repeat(400);
        String out = WebSearchTools.formatResults(List.of(
                result("t", "https://a.com", longContent)));
        assertTrue(out.contains("字".repeat(300) + "..."));
        assertTrue(!out.contains("字".repeat(301)));
    }

    @Test
    void 总量超2000字提前停止() {
        // 10 条 × 每条约 350 字 > 2000 上限，输出必须被截停
        List<WebSearchTools.TavilyResult> many = java.util.stream.IntStream.range(0, 10)
                .mapToObj(i -> result("t" + i, "https://a.com/" + i, "字".repeat(350)))
                .toList();
        String out = WebSearchTools.formatResults(many);
        assertTrue(out.length() <= 2000 + 100); // 余量容纳编号/URL 行
    }
}
```

- [ ] **Step 2: 运行测试确认编译失败**

Run: `mvn test -pl dingRing-infrastructure -Dtest=WebSearchToolsTest -q`
Expected: 编译错误 `WebSearchTools` 不存在

- [ ] **Step 3: 实现 WebSearchTools**

```java
package com.dingring.infrastructure.agent.tool;

import com.dingring.common.util.LogHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 联网搜索工具集（Tavily Search API）。
 * <p>为什么选 Tavily：为 LLM Agent 设计的结构化搜索（url/title/content/score），
 * 支持 keyless 免费模式（api-key 留空时自动降级）；配置 Key 后走 1000 次/月免费额度。
 * <p>设计约定：
 * <ul>
 *   <li>schema 只暴露 query：max_results 等收敛在方法内，参数越少模型调用越准</li>
 *   <li>结果截断：工具输出会回流进模型上下文，单条 300 字、总量 2000 字防 token 溢出</li>
 *   <li>失败返回提示语不抛异常：沿用项目工具约定，防 ReAct 循环因报错反复重试</li>
 * </ul>
 */
@Slf4j
@Component
public class WebSearchTools {

    private static final String SEARCH_URL = "https://api.tavily.com/search";
    /** 每次搜索返回条数：学习项目默认 5 条平衡信息量与 token */
    private static final int MAX_RESULTS = 5;
    /** 单条摘要截断长度（字符） */
    static final int SNIPPET_MAX_LEN = 300;
    /** 格式化输出总量上限（字符） */
    static final int TOTAL_MAX_LEN = 2000;

    private final String apiKey;
    private final RestClient restClient;

    public WebSearchTools(@Value("${dingring.tool.web-search.api-key:}") String apiKey) {
        // trim 防 yml 缩进空格导致 Bearer 头非法；空值走 keyless 免费模式
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(15));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Tool(description = "联网搜索最新信息。当问题涉及实时资讯、新闻、近期事件或模型不确定的事实时调用")
    public String webSearch(
            @ToolParam(description = "搜索关键词，建议用简洁明确的中文或英文查询词") String query) {
        try {
            TavilyResponse resp = restClient.post()
                    .uri(SEARCH_URL)
                    .headers(this::applyAuth)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("query", query, "max_results", MAX_RESULTS))
                    .retrieve()
                    .body(TavilyResponse.class);
            String formatted = formatResults(resp == null ? null : resp.results());
            LogHelper.printLog(WebSearchTools.class, "webSearch", "TOOL_WEB_SEARCH",
                    "联网搜索完成", "query={} 结果条数={}", query,
                    resp == null || resp.results() == null ? 0 : resp.results().size());
            return formatted;
        } catch (Exception e) {
            // 网络/限流/Key 无效统一兜底：给模型一句可自纠的提示，不阻断 ReAct
            LogHelper.printWarnLog(WebSearchTools.class, "webSearch", "TOOL_WEB_SEARCH",
                    "联网搜索失败", "query={} 错误: {}", query, e.getMessage());
            return "联网搜索暂不可用，请基于已有知识回答";
        }
    }

    /** 认证头：配置 Key 走 Bearer（免费额度），否则 keyless（有速率限制） */
    private void applyAuth(HttpHeaders headers) {
        if (apiKey.isEmpty()) {
            headers.set("X-Tavily-Access-Mode", "keyless");
        } else {
            headers.setBearerAuth(apiKey);
        }
    }

    /** 搜索结果 → 编号列表文本；空结果返回提示语（包私有供单测） */
    static String formatResults(List<TavilyResult> results) {
        if (results == null || results.isEmpty()) {
            return "未搜索到相关结果";
        }
        StringBuilder sb = new StringBuilder();
        int total = 0;
        int idx = 1;
        for (TavilyResult r : results) {
            String title = r.title() == null ? "" : r.title();
            String url = r.url() == null ? "" : r.url();
            String snippet = r.content() == null ? "" : truncate(r.content(), SNIPPET_MAX_LEN);
            String entry = "[" + idx++ + "] " + title + " (" + url + ")\n" + snippet + "\n\n";
            if (total + entry.length() > TOTAL_MAX_LEN) {
                break; // 超总量上限直接停，不截半条
            }
            sb.append(entry);
            total += entry.length();
        }
        return sb.toString().trim();
    }

    static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** Tavily /search 响应（仅取用到的字段，answer 等忽略） */
    record TavilyResponse(List<TavilyResult> results) {}

    /** 单条搜索结果 */
    record TavilyResult(String title, String url, String content, Double score) {}
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -pl dingRing-infrastructure -Dtest=WebSearchToolsTest -q`
Expected: 4 tests PASS

- [ ] **Step 5: Commit**

```bash
git add dingRing-infrastructure/src/main/java/com/dingring/infrastructure/agent/tool/WebSearchTools.java dingRing-infrastructure/src/test/java/com/dingring/infrastructure/agent/tool/WebSearchToolsTest.java
git commit -m "feat(tool): 新增 Tavily 联网搜索工具（@Tool + keyless/Bearer 自适应）"
```

---

### Task 2: Debug 端点（不经 LLM 直接验证工具）

**Files:**
- Create: `dingRing-adapter/src/main/java/com/dingring/adapter/rest/ToolTestController.java`

- [ ] **Step 1: 实现 ToolTestController（复用 TestController 的开关与风格）**

```java
package com.dingring.adapter.rest;

import com.dingring.common.response.ApiResponse;
import com.dingring.infrastructure.agent.tool.WebSearchTools;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tool 调试专用 REST API。
 *
 * <p>目的：不经 LLM 直接调用工具方法，用 Postman 验证 Tavily 连通性、Key 有效性、
 * 格式化与截断输出。与 TestController（LLM 调试）同用 dingring.debug.llm.enabled 开关，
 * 生产配置必须关闭。
 */
@RestController
@RequestMapping("/api/test/tool")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "dingring.debug.llm.enabled", havingValue = "true")
public class ToolTestController {

    private final WebSearchTools webSearchTools;

    /** GET /api/test/tool/web-search?query=今天AI新闻 */
    @GetMapping("/web-search")
    public ApiResponse<Map<String, Object>> webSearch(@RequestParam String query) {
        long start = System.currentTimeMillis();
        String result = webSearchTools.webSearch(query);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("result", result);
        body.put("length", result == null ? 0 : result.length());
        body.put("durationMs", System.currentTimeMillis() - start);
        return ApiResponse.ok(body);
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `mvn compile -pl dingRing-adapter -am -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: 启动应用手动验证（真实 Tavily 调用）**

Run: 启动 `DingRingApplication`，然后请求
`curl "http://localhost:8080/api/test/tool/web-search?query=今天AI新闻"`
Expected: 返回含 `[1] 标题 (url)` 格式的真实搜索结果；用错误 Key 验证时返回兜底提示语（可选）

- [ ] **Step 4: Commit**

```bash
git add dingRing-adapter/src/main/java/com/dingring/adapter/rest/ToolTestController.java
git commit -m "feat(adapter): 新增 /api/test/tool/web-search 调试端点"
```

---

### Task 3: SaaLlmFactory 挂载点改造

**Files:**
- Modify: `dingRing-infrastructure/src/main/java/com/dingring/infrastructure/llm/SaaLlmFactory.java`

- [ ] **Step 1: 替换工具注入与 build() 挂载**

删除（L14-16 import、L74-76 字段）：
```java
import com.dingring.infrastructure.agent.tool.KnowledgeSearchTool;
import com.dingring.infrastructure.agent.tool.TopicHistoryTool;
import com.dingring.infrastructure.agent.tool.UserProfileQueryTool;
```
```java
    private final UserProfileQueryTool userProfileQueryTool;
    private final TopicHistoryTool topicHistoryTool;
    private final KnowledgeSearchTool knowledgeSearchTool;
```

删除 import `org.springframework.ai.support.ToolCallbacks` 与 `org.springframework.ai.tool.ToolCallback`。

新增 import：
```java
import com.alibaba.cloud.ai.graph.agent.Builder;
import com.dingring.infrastructure.agent.tool.WebSearchTools;
```

字段区新增（Hook 字段之后）：
```java
    private final WebSearchTools webSearchTools;
```

build() 方法整体替换（**保留** `@Event` 注解、TODO 注释、Hook 链注释块原文）：
```java
    @Event(eventCode = "SaaLlmFactory.build", eventName ="ReactAgent构建")
    private ReactAgent build(Agent domainAgent, ToolSet toolSet, int recursionLimit) {
        Builder builder = ReactAgent.builder()
                .name(domainAgent.getName())
                .description(domainAgent.getDescription() != null ? domainAgent.getDescription() : "")
                .model(buildChatModel(domainAgent, null));
        // 联网搜索：除 CONCLUDE（收束总结无需外查）外全场景挂载。
        // methodTools 由 SAA 扫描 @Tool 方法注册——后续新增工具方法零装配代码
        if (toolSet != ToolSet.CONCLUDE) {
            builder.methodTools(webSearchTools);
        }
        //TODO:这里的SystemPrompt缺失
//                .systemPrompt缺失
        ReactAgent agent = builder
                // Hook 单例共享安全：实现仅从 state 读 per-call 参数，不使用 agent 引用。
                // GroupContextMemoryHook（AgentHook）：按意图组装人设+群上下文记忆，整表替换 messages，
                // 必须注册在 InjectKbHook（append）之前，否则会吃掉 kb 注入；
                // InjectKbHook（AgentHook）：每次 ReAct 运行前按意图门控注入一次，贯穿全程模型调用；
                // 合并 Hook 必须注册在最后：Hook 按 getOrder 稳定排序、同序保持注册顺序（当前
                // 各 Hook 均未覆写 getOrder，默认 0），保证模型调用前已把所有 SystemMessage
                // 收敛为单条置顶（SystemMessageMergeHook.beforeModel）；若有 Hook 覆写为非 0
                // 需同步调整此假设
                .hooks(groupContextMemoryHook, profileInjectionHook, groupRosterHook, injectKbHook,
                        systemMessageMergeHook)
                .compileConfig(CompileConfig.builder()
                        .recursionLimit(recursionLimit)
                        .build())
                .build();

        LogHelper.printLog(SaaLlmFactory.class, "SaaLlmFactory.build", "REACT_AGENT_BUILD",
                "ReactAgent 构建完成", "agent={} toolSet={} recursionLimit={} webSearch={}",
                domainAgent.getName(), toolSet, recursionLimit, toolSet != ToolSet.CONCLUDE);
        return agent;
    }
```

删除整个 `resolveTools(ToolSet)` 方法（L196-207 含 javadoc）。

- [ ] **Step 2: 编译验证**

Run: `mvn compile -pl dingRing-infrastructure -q`
Expected: BUILD SUCCESS（旧工具类仍在但本文件已无引用）

- [ ] **Step 3: Commit**

```bash
git add dingRing-infrastructure/src/main/java/com/dingring/infrastructure/llm/SaaLlmFactory.java
git commit -m "refactor(llm): ReactAgent 工具挂载切换为 methodTools(webSearchTools)，移除 switch 装配"
```

---

### Task 4: SupervisorAgentFactory 挂载点改造

**Files:**
- Modify: `dingRing-infrastructure/src/main/java/com/dingring/infrastructure/agent/runtime/SupervisorAgentFactory.java`

- [ ] **Step 1: 替换 worker 通用工具**

删除（L15-17 import、L69-71 字段）：
```java
import com.dingring.infrastructure.agent.tool.KnowledgeSearchTool;
import com.dingring.infrastructure.agent.tool.TopicHistoryTool;
import com.dingring.infrastructure.agent.tool.UserProfileQueryTool;
```
```java
    private final KnowledgeSearchTool knowledgeSearchTool;
    private final TopicHistoryTool topicHistoryTool;
    private final UserProfileQueryTool userProfileQueryTool;
```

新增 import 与字段：
```java
import com.dingring.infrastructure.agent.tool.WebSearchTools;
```
```java
    private final WebSearchTools webSearchTools;
```

resolveWorkerTools() 替换（L138-146）：
```java
    /**
     * 子 Agent 工具集 = 通用工作工具（联网搜索）+ SKILL 声明工具。
     * <p>SKILL 工具按名称从 SkillToolkitFactory 注册表解析，未命中（名称拼错或工具未注册）由工厂 WARN 跳过。
     */
    private ToolCallback[] resolveWorkerTools(Agent agent) {
        List<ToolCallback> tools = new ArrayList<>();
        tools.addAll(Arrays.asList(ToolCallbacks.from(webSearchTools)));
        List<Skill> skills = skillLoaderService.loadAgentSkills(agent.getId());
        for (Skill skill : skills) {
            tools.addAll(Arrays.asList(skillToolkitFactory.resolveTools(skill)));
        }
        return tools.toArray(new ToolCallback[0]);
    }
```

buildWorkerPrompt() 文案更新（L155）：
`必要时调用可用工具（知识检索/历史结论/用户画像等）辅助，` → `必要时调用可用工具（如联网搜索）辅助，`

- [ ] **Step 2: 编译验证**

Run: `mvn compile -pl dingRing-infrastructure -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add dingRing-infrastructure/src/main/java/com/dingring/infrastructure/agent/runtime/SupervisorAgentFactory.java
git commit -m "refactor(supervisor): worker 通用工具切换为 WebSearchTools"
```

---

### Task 5: SkillToolkitFactory 注册表更新

**Files:**
- Modify: `dingRing-infrastructure/src/main/java/com/dingring/infrastructure/skill/SkillToolkitFactory.java`

- [ ] **Step 1: 替换注册的工具 Bean**

删除（L33-35 字段与全限定名 import）：
```java
    private final com.dingring.infrastructure.agent.tool.UserProfileQueryTool userProfileQueryTool;
    private final com.dingring.infrastructure.agent.tool.TopicHistoryTool topicHistoryTool;
    private final com.dingring.infrastructure.agent.tool.KnowledgeSearchTool knowledgeSearchTool;
```

新增 import 与字段：
```java
import com.dingring.infrastructure.agent.tool.WebSearchTools;
```
```java
    private final WebSearchTools webSearchTools;
```

init() 替换（L38-45）：
```java
    /** 初始化工具注册表（Bean 构造完成后执行，先于 SkillLoader 装配） */
    @PostConstruct
    public void init() {
        register(webSearchTools);
        LogHelper.printLog(SkillToolkitFactory.class, "init", "SKILL_TOOLKIT",
                "工具注册表初始化完成", "tools={}", toolRegistry.keySet());
    }
```

类 javadoc 第 21 行同步更新：`工具注册表是固定的 @Tool Bean（Phase D 的 UserProfileQueryTool/TopicHistoryTool/KnowledgeSearchTool）` → `工具注册表是固定的 @Tool Bean（当前为 WebSearchTools）`

- [ ] **Step 2: 编译验证**

Run: `mvn compile -pl dingRing-infrastructure -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add dingRing-infrastructure/src/main/java/com/dingring/infrastructure/skill/SkillToolkitFactory.java
git commit -m "refactor(skill): 工具注册表改注册 WebSearchTools"
```

---

### Task 6: 删除旧工具文件

**Files:**
- Delete: `dingRing-infrastructure/src/main/java/com/dingring/infrastructure/agent/tool/KnowledgeSearchTool.java`
- Delete: `dingRing-infrastructure/src/main/java/com/dingring/infrastructure/agent/tool/TopicHistoryTool.java`
- Delete: `dingRing-infrastructure/src/main/java/com/dingring/infrastructure/agent/tool/UserProfileQueryTool.java`

- [ ] **Step 1: 全仓库确认零引用**

Run: `grep -rn "KnowledgeSearchTool\|TopicHistoryTool\|UserProfileQueryTool" --include="*.java" dingRing-* | grep -v "agent/tool/KnowledgeSearchTool.java\|agent/tool/TopicHistoryTool.java\|agent/tool/UserProfileQueryTool.java"`
Expected: 空输出（引用已在 Task 3/4/5 清除；若残留按同样模式修复）

- [ ] **Step 2: 删除 3 个文件**

Run: `git rm dingRing-infrastructure/src/main/java/com/dingring/infrastructure/agent/tool/KnowledgeSearchTool.java dingRing-infrastructure/src/main/java/com/dingring/infrastructure/agent/tool/TopicHistoryTool.java dingRing-infrastructure/src/main/java/com/dingring/infrastructure/agent/tool/UserProfileQueryTool.java`

- [ ] **Step 3: 全模块编译 + 测试回归**

Run: `mvn test -q`
Expected: BUILD SUCCESS，WebSearchToolsTest 4 项通过，既有测试无回归

- [ ] **Step 4: Commit**

```bash
git commit -m "refactor(tool): 删除与 Hook 注入重复的 3 个旧工具 Bean"
```

---

### Task 7: Skill 种子与 DB 数据清理

**Files:**
- Modify: `start/src/main/resources/skill-config.json`
- Modify: `dingRing-infrastructure/src/test/resources/skill-config.json`（保持两份种子一致）

- [ ] **Step 1: 更新种子（删 3 个旧技能，新增 web-search）**

新 JSON 全文：
```json
[
  {
    "name": "web-search",
    "description": "联网搜索技能：查询实时资讯、新闻与模型不确定的事实",
    "toolNames": "webSearch",
    "systemPrompt": "你可以调用联网搜索工具查询最新信息。当问题涉及实时资讯、新闻、近期事件或你不确定的事实时，先搜索再回答，并注明信息来源。",
    "scope": "GLOBAL",
    "agentId": null,
    "status": "ACTIVE"
  },
  {
    "name": "diagram",
    "description": "画图技能：按需绘制 SVG 示意图，遵循 Excalidraw 手绘风格规范，图形不计入发言字数",
    "toolNames": "",
    "systemPrompt": "当用户要求画图/画示意图/画结构图/画流程图时，遵循以下要求：\n- 使用内联 SVG 输出图，整体贴合 Excalidraw 手绘风格（蜡笔质感笔触、手写字体、米色画布）\n- 图先出，图后用 1-2 句话点出关键要点；图形本身不计算在发言总字数内\n- 结构清晰：节点用中文标注，箭头/连线语义明确，核心概念可加粗或着色\n- 仅使用 HTML/SVG，不要引用外部图片资源",
    "scope": "GLOBAL",
    "agentId": null,
    "status": "ACTIVE"
  }
]
```

- [ ] **Step 2: DB 旧技能清理（需用户确认后经 MySQL 执行）**

种子加载是幂等插入，DB 中旧技能不会自动删除/更新。执行：
```sql
DELETE FROM skill WHERE name IN ('rag-search', 'user-intent', 'history-reference');
```
重启后种子自动插入 `web-search`。验证：
```sql
SELECT name, tool_names FROM skill;
```
Expected: 仅 `web-search`（tool_names=webSearch）与 `diagram`（tool_names 空）

- [ ] **Step 3: Commit**

```bash
git add start/src/main/resources/skill-config.json dingRing-infrastructure/src/test/resources/skill-config.json
git commit -m "chore(skill): 种子技能对齐新工具表（web-search 替代旧三技能）"
```

---

### Task 8: 端到端验证

- [ ] **Step 1: 启动应用，确认注册表与技能日志无 WARN**

启动日志应见：`SKILL_TOOLKIT ... tools=[webSearch]`；无"技能声明了未知工具"WARN

- [ ] **Step 2: 群聊端到端（真实 LLM）**

前端群聊发送："帮我查一下最近的 AI 大新闻"
Expected: Agent 发言引用实时信息；日志出现 `TOOL_WEB_SEARCH 联网搜索完成 query=... 结果条数=5`（或类似）；`REACT_AGENT_BUILD ... webSearch=true`

- [ ] **Step 3: CONCLUDE 场景回归**

触发一次话题收束（讨论后点收束或等 converge）
Expected: 收束发言正常，`REACT_AGENT_BUILD ... toolSet=CONCLUDE ... webSearch=false`

---

## Self-Review 记录

- **Spec 覆盖**：方案 §3.1 删除清单 → Task 3/4/5/6；§3.2 WebSearchTools → Task 1；§3.3 挂载矩阵 → Task 3；§3.4 连带修复 → Task 4/5/7；§3.5 配置 → 前置已完成；§3.6 debug 端点 → Task 2；§5 测试策略 → Task 1 单测 + Task 2/8 手动。无缺口。
- **占位符扫描**：无 TBD/TODO 式步骤；所有代码步骤含完整代码。
- **类型一致性**：`WebSearchTools.webSearch(String)` 在 Task 1/2 一致；`TavilyResult(title,url,content,score)` 在实现与单测一致；`Builder` 类型为 SAA `com.alibaba.cloud.ai.graph.agent.Builder`。
- **顺序安全**：每 Task 结束态均可编译（旧工具类删除前引用已全部清除）。
