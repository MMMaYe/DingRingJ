# DingRingJ 架构总览图增强 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 增强根目录 `system-architecture.html`：修正 WsBroadcastAdapter/WsSessionRegistryImpl 的层归属（adapter → infrastructure），补充 hover 高亮与 tooltip 交互，产出面向新成员的综合总览架构图。

**Architecture:** 单文件 HTML + 内联 CSS + SVG 连线层。泳道为 HTML flex 布局（.lane），连线为绝对定位 SVG（.arrows，固定 viewBox 1320×1622）。修改分为三块：泳道内容（HTML 结构）、连线坐标（SVG path）、交互（原生 JS）。泳道高度由内容驱动，改动后需实测各泳道 offsetTop 并同步 SVG 坐标。

**Tech Stack:** HTML5 / 原生 CSS / 内联 SVG / 原生 JavaScript（无任何外部依赖）。设计规格：`docs/superpowers/specs/2026-08-09-architecture-overview-design.md`。

---

### Task 1: adapter 泳道移除"出站推送"组

**Files:**
- Modify: `system-architecture.html`（adapter 泳道，约 249-256 行）

- [ ] **Step 1: 删除 adapter 泳道中的"出站推送" group**

在 `system-architecture.html` 中找到：

```html
      <div class="group" style="--g:var(--coral)">
        <div class="group-title">出站推送</div>
        <div class="chips">
          <div class="chip"><b>WsBroadcastAdapter</b><span class="s">implements GroupBroadcastService</span></div>
          <div class="chip"><b>WsSessionRegistry</b><span class="s">会话注册表</span></div>
        </div>
      </div>
    </div>
  </section>
```

删除整个"出站推送" group（保留 `</div></section>`），即删除后 adapter 泳道只剩"REST 接入 /api/*"与"WebSocket /ws/chat"两个 group：

```html
    </div>
  </section>
```

- [ ] **Step 2: 验证删除**

Run: `grep -n "出站推送" system-architecture.html`
Expected: 无输出（已删除）

- [ ] **Step 3: 提交**

```bash
git add system-architecture.html
git commit -m "docs(arch): adapter 泳道移除出站推送组（归位到 infrastructure）"
```

---

### Task 2: infrastructure 泳道新增"WebSocket 推送"组

**Files:**
- Modify: `system-architecture.html`（infra 泳道第二 lane-body 末尾，约 457-463 行）

- [ ] **Step 1: 在 infra 第二 lane-body 的"文件存储"组之后追加新组**

找到 infra 泳道第二 lane-body 的末尾（"文件存储"组之后）：

```html
      <div class="group" style="--g:var(--violet)">
        <div class="group-title">文件存储</div>
        <div class="chips">
          <div class="chip"><b>FileStorageService</b><span class="s">./rag-files 本地</span></div>
        </div>
      </div>
    </div>
  </section>
```

替换为（追加"WebSocket 推送"组，类名沿用 chip 语义）：

```html
      <div class="group" style="--g:var(--violet)">
        <div class="group-title">文件存储</div>
        <div class="chips">
          <div class="chip"><b>FileStorageService</b><span class="s">./rag-files 本地</span></div>
        </div>
      </div>
      <div class="group" style="--g:var(--violet)">
        <div class="group-title">WebSocket 推送</div>
        <div class="chips">
          <div class="chip"><b>WsBroadcastAdapter</b><span class="s">implements GroupBroadcastService</span></div>
          <div class="chip"><b>WsSessionRegistryImpl</b><span class="s">implements WsSessionRegistry</span></div>
        </div>
      </div>
    </div>
  </section>
```

- [ ] **Step 2: 验证插入**

Run: `grep -n "WsBroadcastAdapter\|WsSessionRegistryImpl" system-architecture.html`
Expected: 两处 chip 均出现在 infra 泳道（约 457-470 行区域），且 grep "出站推送" 无结果

- [ ] **Step 3: 提交**

```bash
git add system-architecture.html
git commit -m "docs(arch): infrastructure 泳道新增 WebSocket 推送组"
```

---

### Task 3: 更新 SVG 连线层（推送起点改道 + 新增 impl 虚线 + 坐标对齐）

**Files:**
- Modify: `system-architecture.html`（SVG .arrows 层，约 530-608 行；含纵坐标注释 544-547 行）

背景：infra 泳道新增一组后高度可能增加，common/external 泳道随之整体下移。SVG 是绝对定位层（.arrows 高 1622px 固定），所有连线坐标必须与泳道实际位置对齐。

- [ ] **Step 1: 实测修改后的泳道纵坐标**

在浏览器中打开 `system-architecture.html`，按 F12 打开 DevTools，在 Console 执行：

```js
document.querySelectorAll('.lane').forEach(l => console.log(l.className, l.offsetTop, l.offsetHeight))
```

Expected: 输出 7 个泳道的实际 offsetTop/offsetHeight。记录：
- L3 domain 底部 = domain.offsetTop + domain.offsetHeight
- L4 infra 底部 = infra.offsetTop + infra.offsetHeight
- L5 common 顶部 = common.offsetTop
- L6 external 顶部 = external.offsetTop
- .arrows 元素实际需要的高度 = external.offsetTop + external.offsetHeight + 60（余量）

注意：`.diagram` 是 position:relative，`.arrows` 是 absolute 定位，因此 `.lane` 的 offsetTop 即为 SVG viewBox 中的 y 坐标基准。

- [ ] **Step 2: 更新 .arrows 高度与 viewBox**

修改 SVG 标签（约 531 行）：

```html
  <svg class="arrows" viewBox="0 0 1320 1622" xmlns="http://www.w3.org/2000/svg">
```

将 `1622` 替换为 Step 1 实测的所需高度（估算约 1660-1700）。

- [ ] **Step 3: 更新连线 #9（WS 推送）起点为 infra 泳道右缘**

找到（约 585-587 行）：

```html
    <!-- 9. 推送：WsBroadcastAdapter → 浏览器（青色，右缘绕行） -->
    <path class="push" d="M 1190 1056 L 1190 300 L 850 300 L 850 116" marker-end="url(#arr-c)"/>
    <text class="lab lab--cyan" x="1198" y="700">WS 推送 NEW_MESSAGE / MESSAGE_DELTA / TOPIC_*</text>
```

替换为（起点 y = infra 泳道中部，x=1205 靠右缘；其余路径不变；标注文字保留）：

```html
    <!-- 9. 推送：infra WsBroadcastAdapter → 浏览器（青色，右缘绕行） -->
    <path class="push" d="M 1205 {INFRA_MID} L 1205 300 L 850 300 L 850 116" marker-end="url(#arr-c)"/>
    <text class="lab lab--cyan" x="1198" y="700">WS 推送 NEW_MESSAGE / MESSAGE_DELTA / TOPIC_*</text>
```

其中 `{INFRA_MID}` = L4 infra 的 (offsetTop + offsetHeight/2)，即 Step 1 实测值。

- [ ] **Step 4: 新增连线 #15（domain 端口 ← infra 推送实现，虚线）**

在连线 #14（./rag-files）之后、`</svg>` 之前追加：

```html
    <!-- 15. 端口实现：GroupBroadcastService ← WsBroadcastAdapter（虚线） -->
    <path class="impl" d="M {DOM_PORT_X} {L3_BOTTOM} L {DOM_PORT_X} {INFRA_TOP}" marker-end="url(#arr-v)"/>
```

其中：
- `{DOM_PORT_X}` = domain 泳道"服务端口 ×9"组的水平中心（约 500，实测微调）
- `{L3_BOTTOM}` = Step 1 实测 domain 泳道底部
- `{INFRA_TOP}` = Step 1 实测 infra 泳道顶部（新增组所在第二 lane-body 的上缘，即 infra.offsetTop + 第一 lane-body 高度 + 20px 间距 + 组高的一半）

- [ ] **Step 5: 调整受影响的既有连线坐标（#10-#14）**

现有连线 #10-#14（约 589-607 行）的起点均为 `y=1336`（原 infra 底部）。将全部起点 y 替换为 Step 1 实测的 L4 infra 底部值，并同步中间折点 `y=1360` 与终点（common/external 之间）坐标，使连线视觉上从 infra 底部平滑接入 external 泳道对应组件。

同时更新文件顶部注释（544-547 行）中的泳道纵坐标表，改为实测值，保持注释与代码一致。

- [ ] **Step 6: 浏览器验证连线对齐**

Run: `open system-architecture.html`（或刷新已打开的页面）

Expected 目视检查：
- 青色推送线从 infra 泳道右缘出发、沿右缘上行至浏览器，中途无悬空段
- 新增紫色虚线从 domain 泳道"服务端口 ×9"组垂直落入 infra 的"WebSocket 推送"组
- #10-#14 五条连线均从 infra 底部对准 external 泳道对应组，无错位
- 箭头 marker 方向正确

若任一连线错位：返回 Step 1 重新实测坐标并修正，直至全部对齐。

- [ ] **Step 7: 提交**

```bash
git add system-architecture.html
git commit -m "docs(arch): SVG 连线层对齐：推送线改从 infra 出发 + 新增推送端口实现虚线"
```

---

### Task 4: 添加 hover 高亮与 tooltip 交互

**Files:**
- Modify: `system-architecture.html`（<style> 末尾追加 CSS；</body> 前追加 <script>；SVG 各 path 加 id）

- [ ] **Step 1: 为 SVG 连线 path 添加 id**

在 `system-architecture.html` 的 SVG 层中，为现有 15 条 path（#1-#15）逐个添加 id 属性（`conn1` … `conn15`），例如：

```html
    <!-- 1. 浏览器 REST → adapter -->
    <path id="conn1" class="flow" d="M 250 116 L 250 150" marker-end="url(#arr)"/>
```

- [ ] **Step 2: 在 <style> 末尾追加交互样式**

找到 `footer { ... }` 样式规则（约 163 行），在其后追加：

```css

  /* ===== hover 交互增强 ===== */
  .diagram.dim .chip { opacity: .3; }
  .diagram.dim .chip.hover-on { opacity: 1; }
  .chip { transition: opacity .15s ease, box-shadow .15s ease; }
  .chip.hover-on { box-shadow: 0 2px 10px rgba(15, 23, 42, .18); }
  .arrows path { transition: stroke-opacity .15s ease; }
  .arrows path.conn-off { stroke-opacity: .15; }
  .arrows path.conn-on { stroke-opacity: 1; stroke-width: 2.5; }
  #tip {
    position: fixed; z-index: 999; pointer-events: none;
    max-width: 260px; padding: 8px 10px;
    background: #0f172a; color: #f1f5f9;
    border-radius: 8px; font-size: 11.5px; line-height: 1.5;
    box-shadow: 0 4px 14px rgba(15, 23, 42, .25);
    opacity: 0; transform: translateY(4px);
    transition: opacity .12s ease, transform .12s ease;
  }
  #tip.show { opacity: 1; transform: translateY(0); }
  #tip .k { font-family: var(--mono); font-weight: 700; color: #7dd3fc; display: block; margin-bottom: 2px; }
```

- [ ] **Step 3: 在 </body> 前追加交互脚本**

找到文件末尾（约 652 行）：

```html
</div>
</body>
</html>
```

替换为：

```html
</div>

<script>
(function () {
  'use strict';
  var TIPS = {
    'ChatOrchestrator': '用户消息入口 + 收束域编排；接收 WS 上行消息，驱动讨论引擎。',
    'DiscussionEngine': '每群单线程虚拟线程主循环，串行推进讨论；跨群并行。',
    'SpeakerScheduler': '评分调度：按群成员 Agent 评分选人发言。',
    'Terminator': '熔断器：发言总数达 max-rounds（默认 100）强制收束。',
    'ModeratorService': '可选主持人模式（moderator.enabled）；失败回退评分调度。',
    'ContextBuilder': '组装讨论上下文：群成员名单 + 群记忆 + RAG 注入。',
    'MessageRouter': '意图路由：CHAT / DISCUSS / WORK / CONCLUDE。',
    'StreamMarkerGuard': '流式标记过滤：识别 [[PASS]] / [[CONCLUDE]] 协作协议。',
    'MessageContext': '单次消息处理的上下文载体。',
    'ChatWebSocketHandler': 'WS 会话生命周期：连接 / 断开 / 消息接收。',
    'WsMessageDispatcher': 'WS 上行消息分发到 ChatOrchestrator。',
    'WebSocketConfig': '注册 /ws/chat 端点，允许所有 Origin。',
    'GlobalExceptionHandler': '全局异常 → ApiResponse 统一返回。',
    'GroupController': '群组 REST API：建群 / 成员 / 消息查询。',
    'TopicController': '讨论主题 REST API。',
    'AgentController': 'AI Agent 配置 REST API（花名 / 人设 / LLM 端点）。',
    'CardController': '知识卡片 REST API。',
    'KbController': '知识库 REST API（文档上传 / 检索）。',
    'SkillController': '技能配置 REST API。',
    'TestController': 'dev 调试接口，仅测试环境使用。',
    'GroupAppService': '群用例编排：建群、加成员、群列表。',
    'TopicAppService': '主题用例编排。',
    'AgentAppService': 'Agent 用例编排。',
    'CardAppService': '知识卡片用例编排。',
    'KnowledgeBaseAppService': '知识库用例编排。',
    'SkillAppService': '技能用例编排。',
    'MessageAssembler': '领域消息 → DTO 组装。',
    'CardEventHandler': '监听 TopicClosed → 异步提取 Q&A 知识卡片。',
    'ProfileEventHandler': '监听消息 → 用户画像提炼。',
    'CardReconciler': '@Scheduled 定时对账补卡（10min）。',
    'ConclusionWatchdog': '卡死主题自愈：60s 未完成收束则回滚重试。',
    'Topic': '讨论主题聚合：IN_PROGRESS→CONCLUDING→CLOSED 乐观锁状态机。',
    'KnowledgeCard': 'Q&A 知识卡片，供复习沉淀。',
    'Agent': 'AI 智能体：花名 / 人设 / 独立 LLM 端点配置。',
    'KnowledgeBase / File': '知识库与文件（v1 预留后端能力）。',
    'LlmService': 'LLM 调用端口（抽象，多实现）。',
    'MemoryService': '群记忆读写端口。',
    'ProfileService': '用户画像读写端口。',
    'RagService': 'RAG 检索端口。',
    'Reranker': '检索结果重排端口。',
    'AgentSpeakerService': 'Agent 发言服务端口。',
    'DiscussionFlowService': '讨论流式推进端口。',
    'GroupBroadcastService': '群消息推送端口（出站）。',
    'DomainEventPublisher': '领域事件发布端口。',
    'SpringAiLlmService': '真实 LLM 实现（Spring AI OpenAI 协议）。',
    'MockLlmService': '离线 Mock 实现（dingring.llm.mock=true）。',
    'SaaModelFactory': '按 Agent 的 baseUrl/apiKey/modelName 动态构建模型。',
    'SimpleMemoryService': '群记忆内存实现（Simple）。',
    'SimpleProfileService': '用户画像内存实现（Simple）。',
    'SpringEventPublisher': 'DomainEventPublisher 的 Spring 事件实现。',
    'WsBroadcastAdapter': 'GroupBroadcastService 实现：经 WS 会话推送消息。',
    'WsSessionRegistryImpl': 'WsSessionRegistry 实现：WS 会话注册表。',
    'SaaWorkflow': 'SAA StateGraph 工作流：编译 + 执行 + 节点调度。',
    'PreprocessNode': '消息预处理节点。',
    'IntentClassifyNode': '意图分类：CHAT/DISCUSS/WORK/CONCLUDE。',
    'EnsureTopicNode': '追溯式建题：HIGH 立即 / LOW 连续 2 次。',
    'DiscussNode': '讨论态推进：评分调度 + [[PASS]]/[[CONCLUDE]]。',
    'ChatNode': '闲聊态：Agent 自动接续发言。',
    'WorkNode': 'WORK 意图：工作流任务执行。',
    'ConcludeNode': '收束：STAR 框架结论生成。',
    'SedimentNode': '知识卡片沉淀节点。',
    'ProfileExtractNode': '画像提炼节点。'
  };
  var LINKS = {
    'ChatOrchestrator': ['conn3', 'conn9'],
    'DiscussionEngine': ['conn5', 'conn6', 'conn9'],
    'WsMessageDispatcher': ['conn3'],
    'GroupBroadcastService': ['conn9', 'conn15'],
    'WsBroadcastAdapter': ['conn9', 'conn15'],
    'GroupController': ['conn1', 'conn4'],
    'TopicController': ['conn1', 'conn4'],
    'AgentController': ['conn1', 'conn4'],
    'CardController': ['conn1', 'conn4'],
    'SpringAiLlmService': ['conn12'],
    'MockLlmService': ['conn12'],
    'SaaModelFactory': ['conn12']
  };
  var tip = document.createElement('div');
  tip.id = 'tip';
  document.body.appendChild(tip);
  var diagram = document.querySelector('.diagram');
  var paths = Array.prototype.slice.call(document.querySelectorAll('.arrows path'));
  var chips = Array.prototype.slice.call(document.querySelectorAll('.chip'));
  function chipKey(c) {
    var b = c.querySelector('b');
    return b ? b.textContent.trim() : '';
  }
  function showTip(c, x, y) {
    var k = chipKey(c);
    var t = TIPS[k];
    if (!t) {
      var s = c.querySelector('.s');
      t = s && s.textContent.trim() ? s.textContent.trim() : '';
    }
    tip.innerHTML = t ? '<span class="k">' + k + '</span>' + t : k;
    var pad = 14;
    var tx = x + pad, ty = y + pad;
    if (tx + 270 > window.innerWidth) tx = x - 270;
    if (ty + 80 > window.innerHeight) ty = y - 80;
    tip.style.left = tx + 'px';
    tip.style.top = ty + 'px';
    tip.classList.add('show');
  }
  function setActive(c, on) {
    var key = chipKey(c);
    diagram.classList.toggle('dim', on);
    chips.forEach(function (o) { o.classList.toggle('hover-on', on && o === c); });
    paths.forEach(function (p) {
      var linked = on && LINKS[key] && LINKS[key].indexOf(p.id) !== -1;
      p.classList.toggle('conn-on', linked);
      p.classList.toggle('conn-off', on && !linked);
    });
  }
  chips.forEach(function (c) {
    c.addEventListener('mouseenter', function (e) { setActive(c, true); showTip(c, e.clientX, e.clientY); });
    c.addEventListener('mousemove', function (e) { showTip(c, e.clientX, e.clientY); });
    c.addEventListener('mouseleave', function () { setActive(c, false); tip.classList.remove('show'); });
  });
})();
</script>
</body>
</html>
```

- [ ] **Step 4: 浏览器验证交互**

Run: `open system-architecture.html`

Expected 目视检查：
- 鼠标悬停任意 chip：其余 chip 降透明度至 0.3，该 chip 高亮（阴影 + 不透明）
- 悬停 ChatOrchestrator：conn3（WS 上行）与 conn9（推送）连线加粗高亮，其余连线变淡
- tooltip 跟随鼠标显示组件职责（深色圆角卡片，含组件名 + 说明）
- 悬停无 TIPS 映射的 chip（如各类 Mapper）：tooltip 显示其 .s 子文本兜底
- 页面滚动时 tooltip 不残留（mouseleave 正常触发）

若 tooltip 内容缺失或连线高亮错位，修正 TIPS / LINKS 映射后重新验证。

- [ ] **Step 5: 提交**

```bash
git add system-architecture.html
git commit -m "docs(arch): 新增 hover 高亮与 tooltip 交互（原生 JS 零依赖）"
```

---

### Task 5: 最终验收与提交

**Files:**
- Verify: `system-architecture.html`

- [ ] **Step 1: 对照规格验收标准逐条检查**

Run: `open system-architecture.html`，逐条核对：

1. 零依赖单文件：页面无外部 <link>/<script src>（Run: `grep -n '<script src\|<link rel="stylesheet"' system-architecture.html`，Expected 无输出）
2. 组件无遗漏：对照规格组件清单，10 Mapper / 10 仓储端口 / 9 服务端口 / SAA 节点 / RAG / Skill / Nacos 均在图中
3. 三条关键链路可讲清：①用户消息全链路（浏览器 → WS → WsMessageDispatcher → ChatOrchestrator → SAA 工作流）②讨论推进（EnsureTopicNode → DiscussNode，评分调度 + [[PASS]]/[[CONCLUDE]]）③收束 → 知识卡片（ConcludeNode → SedimentNode → CardEventHandler → KnowledgeCard）
4. hover 与 tooltip 正常（Task 4 已验证）
5. 层归属正确：WsBroadcastAdapter / WsSessionRegistryImpl 在 infrastructure 泳道，grep 确认 adapter 泳道无残留

- [ ] **Step 2: 确认 git 历史干净**

Run: `git status --short && git log --oneline -6`
Expected: `system-architecture.html` 无未提交改动；最近 6 条提交含本次 4 个 docs(arch) 提交

- [ ] **Step 3: 收尾**

在浏览器中与用户确认最终效果（悬停演示 + 三条链路讲解），确认后向用户汇报交付。

---

## Self-Review 记录

- **Spec 覆盖**：修正项 1（层归属）→ Task 1+2+3；修正项 2（交互）→ Task 4；验收 1-5 → Task 5。规格"文件位置保持根目录 system-architecture.html"→ 全程原位修改。
- **占位符扫描**：Task 3 的 `{INFRA_MID}`/`{L3_BOTTOM}` 等为实测值占位，步骤内已给出测量方法与判定标准（浏览器 DevTools 实测 + 目视对齐），非悬空 TODO。
- **类型一致性**：CSS 类 `hover-on` / `conn-on` / `conn-off` / `dim` 与 JS 中 classList 操作一致；path id `conn1`-`conn15` 与 LINKS 映射一致；`WsSessionRegistryImpl` 命名与真实代码一致（非旧的 WsSessionRegistry）。
