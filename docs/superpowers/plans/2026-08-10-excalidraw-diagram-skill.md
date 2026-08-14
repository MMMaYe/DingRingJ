# excalidraw-diagram Skill 实现计划（手绘风 + 标准组件图标）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用 aref-vc/excalidraw-skill 作底子，在 `~/.trae-cn/skills/excalidraw-diagram/` 搭建一个"手绘风 + 标准组件图标"的架构图 skill，并删除旧的 architecture-diagram。

**Architecture:** fork aref-vc/excalidraw-skill（本地 roughjs 渲染 SVG+PNG，Virgil 手写字体），在渲染器 `render.mjs` 中新增 `symbol` 图标系统：形状元素声明 `symbol: "database"` 等，渲染器用 rough 原语在框内上部自动绘制手绘版标准图标（数据库圆柱等）。SKILL.md 与 references 文档同步重写/扩展。

**Tech Stack:** Node.js ≥18（本机 v23.11.0）、roughjs、@xmldom/xmldom、@resvg/resvg-js、SVG。

**Spec:** `docs/superpowers/specs/2026-08-10-excalidraw-diagram-skill-design.md`

**安装目标（不在本 git 仓库内）:** `~/.trae-cn/skills/excalidraw-diagram/`

---

## 文件结构

```
~/.trae-cn/skills/excalidraw-diagram/
├── SKILL.md                        # 重写：触发词、工作流、配置、图标规则（Task 5）
├── scripts/
│   ├── render.mjs                  # fork 自底子 + symbol 图标系统（Task 2）
│   ├── package.json                # 原样保留（deps: roughjs/xmldom/resvg）
│   └── package-lock.json           # npm install 后更新
├── references/
│   ├── element-format.md           # fork + symbol 字段说明（Task 4）
│   └── component-icons.md          # 新增：图标目录 + 配色 + 尺寸规则（Task 3）
├── examples/
│   ├── ecommerce.json              # 样例1：电商架构（Task 6）
│   ├── microservices.json          # 样例2：微服务部署（Task 6）
│   └── chinese.json                # 样例3：中文标签（Task 6）
├── fonts/                          # Virgil.woff2 + Cascadia.woff2（原样保留）
└── LICENSE                         # MIT 保留
```

删除项：
- `~/.trae-cn/skills/architecture-diagram/`（整个目录，Task 7）
- 底子仓库的 `assets/` 示例图片、`.git/`（Task 1 不拷贝）

---

### Task 1: 安装底子 skill 到目标目录

**Files:**
- Create: `~/.trae-cn/skills/excalidraw-diagram/`（SKILL.md / scripts / references / fonts / LICENSE）

- [ ] **Step 1: 建目录并拷贝底子文件**

```bash
mkdir -p ~/.trae-cn/skills/excalidraw-diagram
cp -R /tmp/excalidraw-skill-inspect/SKILL.md \
      /tmp/excalidraw-skill-inspect/scripts \
      /tmp/excalidraw-skill-inspect/references \
      /tmp/excalidraw-skill-inspect/fonts \
      /tmp/excalidraw-skill-inspect/LICENSE \
      ~/.trae-cn/skills/excalidraw-diagram/
```

（`assets/` 与 `.git/` 不拷贝。若 `/tmp/excalidraw-skill-inspect` 不存在，先执行 `git clone --depth 1 https://github.com/aref-vc/excalidraw-skill.git /tmp/excalidraw-skill-inspect`。）

- [ ] **Step 2: 验证目录结构**

Run: `ls ~/.trae-cn/skills/excalidraw-diagram`
Expected: 包含 `SKILL.md scripts references fonts LICENSE` 五个条目，无 `assets`、无 `.git`。

- [ ] **Step 3: 安装渲染依赖**

```bash
cd ~/.trae-cn/skills/excalidraw-diagram/scripts && npm install
```

Expected: 生成 `node_modules/`，输出 "added N packages"，无 error。`package-lock.json` 可能被更新（正常）。

- [ ] **Step 4: 冒烟测试渲染器**

```bash
mkdir -p /tmp/excalidraw-out
cat > /tmp/smoke.json << 'EOF'
{
  "title": "Smoke Test",
  "width": 400,
  "height": 200,
  "elements": [
    { "type": "rectangle", "id": "a", "x": 40, "y": 80, "width": 140, "height": 70, "label": "Client", "fill": "#a5d8ff", "rounded": true },
    { "type": "rectangle", "id": "b", "x": 240, "y": 80, "width": 140, "height": 70, "label": "DB", "fill": "#b2f2bb", "rounded": true },
    { "type": "arrow", "from": "a", "to": "b", "label": "SQL" }
  ]
}
EOF
node ~/.trae-cn/skills/excalidraw-diagram/scripts/render.mjs /tmp/smoke.json /tmp/excalidraw-out
```

Expected: 输出 `SVG saved: /tmp/excalidraw-out/smoke-test.svg` 和 `PNG saved: /tmp/excalidraw-out/smoke-test.png`，无报错。`ls -la /tmp/excalidraw-out` 确认两个文件存在且非 0 字节。

---

### Task 2: 渲染器扩展 —— symbol 图标系统

**Files:**
- Modify: `~/.trae-cn/skills/excalidraw-diagram/scripts/render.mjs`

- [ ] **Step 1: 插入图标绘制函数与注册表**

在 `render.mjs` 的 `function roughOpts(el) { ... }` 定义之后（约第 207 行闭合 `}` 之后的空行处），插入以下完整代码：

```js
// ── Component Icon System ────────────────────────────────────────────────────
// Standard component symbols drawn with rough primitives so icons keep the
// hand-drawn aesthetic while staying recognizable (e.g. database cylinder).

function iconStyle(el, fill) {
  return {
    stroke: el.stroke || STROKE,
    strokeWidth: 1.8,
    roughness: 1.2,
    fill,
    fillStyle: "solid",
  };
}

function drawDatabase(rc, cx, cy, s, el) {
  const w = s * 1.6, h = s * 0.8;
  svg.appendChild(rc.ellipse(cx, cy - s * 0.6, w, h, iconStyle(el, "#ffffff")));
  svg.appendChild(rc.line(cx - w / 2, cy - s * 0.6, cx - w / 2, cy + s * 0.5, iconStyle(el)));
  svg.appendChild(rc.line(cx + w / 2, cy - s * 0.6, cx + w / 2, cy + s * 0.5, iconStyle(el)));
  svg.appendChild(rc.arc(cx, cy + s * 0.5, w, h, 0, Math.PI, iconStyle(el)));
  svg.appendChild(rc.line(cx - w / 2, cy - s * 0.1, cx + w / 2, cy - s * 0.1, iconStyle(el)));
  svg.appendChild(rc.line(cx - w / 2, cy + s * 0.2, cx + w / 2, cy + s * 0.2, iconStyle(el)));
}

function drawServer(rc, cx, cy, s, el) {
  svg.appendChild(rc.rectangle(cx - s * 1.3, cy - s * 1.0, s * 2.6, s * 2.0, iconStyle(el)));
  svg.appendChild(rc.line(cx - s * 1.3, cy - s * 0.4, cx + s * 1.3, cy - s * 0.4, iconStyle(el)));
  svg.appendChild(rc.line(cx - s * 1.3, cy + s * 0.2, cx + s * 1.3, cy + s * 0.2, iconStyle(el)));
  svg.appendChild(rc.line(cx - s * 1.3, cy + s * 0.8, cx + s * 1.3, cy + s * 0.8, iconStyle(el)));
}

function drawCloud(rc, cx, cy, s, el) {
  const d = `M ${cx - s * 1.3} ${cy + s * 0.4} C ${cx - s * 2.0} ${cy + s * 0.4}, ${cx - s * 2.0} ${cy - s * 0.4}, ${cx - s * 1.3} ${cy - s * 0.4} C ${cx - s * 1.2} ${cy - s * 1.1}, ${cx - s * 0.3} ${cy - s * 1.2}, ${cx + s * 0.2} ${cy - s * 0.7} C ${cx + s * 0.7} ${cy - s * 1.1}, ${cx + s * 1.4} ${cy - s * 0.9}, ${cx + s * 1.4} ${cy - s * 0.2} C ${cx + s * 2.0} ${cy - s * 0.2}, ${cx + s * 2.0} ${cy + s * 0.4}, ${cx + s * 1.3} ${cy + s * 0.4} Z`;
  svg.appendChild(rc.path(d, iconStyle(el, "#ffffff")));
}

function drawCache(rc, cx, cy, s, el) {
  const w = s * 1.2, h = s * 0.6;
  svg.appendChild(rc.ellipse(cx, cy - s * 0.55, w, h, iconStyle(el, "#ffffff")));
  svg.appendChild(rc.line(cx - w / 2, cy - s * 0.55, cx - w / 2, cy + s * 0.45, iconStyle(el)));
  svg.appendChild(rc.line(cx + w / 2, cy - s * 0.55, cx + w / 2, cy + s * 0.45, iconStyle(el)));
  svg.appendChild(rc.arc(cx, cy + s * 0.45, w, h, 0, Math.PI, iconStyle(el)));
  svg.appendChild(rc.path(`M ${cx + s * 0.25} ${cy - s * 0.3} L ${cx - s * 0.2} ${cy} L ${cx + s * 0.05} ${cy} L ${cx - s * 0.25} ${cy + s * 0.3}`, iconStyle(el)));
}

function drawQueue(rc, cx, cy, s, el) {
  const y1 = cy - s * 0.9, y2 = cy - s * 0.2, y3 = cy + s * 0.5;
  const x0 = cx - s * 1.2, x1 = cx + s * 0.6;
  svg.appendChild(rc.line(x0, y1, x1, y1, iconStyle(el)));
  svg.appendChild(rc.line(x0, y2, x1, y2, iconStyle(el)));
  svg.appendChild(rc.line(x0, y3, x1, y3, iconStyle(el)));
  const tip = cx + s * 1.2;
  svg.appendChild(rc.polygon([[x1, y3], [tip, y3], [x1 + (tip - x1) * 0.5, y3 - s * 0.25]], iconStyle(el)));
}

function drawLoadBalancer(rc, cx, cy, s, el) {
  svg.appendChild(rc.circle(cx, cy, s * 2.2, iconStyle(el)));
  svg.appendChild(rc.line(cx - s * 1.6, cy, cx - s * 0.9, cy, iconStyle(el)));
  svg.appendChild(rc.line(cx + s * 0.9, cy, cx + s * 1.6, cy, iconStyle(el)));
  svg.appendChild(rc.polygon([[cx - s * 1.6, cy], [cx - s * 0.9, cy], [cx - s * 1.25, cy - s * 0.25]], iconStyle(el)));
  svg.appendChild(rc.polygon([[cx + s * 0.9, cy], [cx + s * 1.6, cy], [cx + s * 1.25, cy - s * 0.25]], iconStyle(el)));
}

function drawClient(rc, cx, cy, s, el) {
  svg.appendChild(rc.rectangle(cx - s * 1.1, cy - s * 0.85, s * 2.2, s * 1.4, iconStyle(el, "#ffffff")));
  svg.appendChild(rc.line(cx - s * 0.3, cy + s * 0.7, cx + s * 0.3, cy + s * 0.7, iconStyle(el)));
  svg.appendChild(rc.line(cx, cy + s * 0.7, cx, cy + s * 1.0, iconStyle(el)));
  svg.appendChild(rc.line(cx - s * 0.7, cy + s * 1.0, cx + s * 0.7, cy + s * 1.0, iconStyle(el)));
}

function drawSecurity(rc, cx, cy, s, el) {
  svg.appendChild(rc.rectangle(cx - s * 0.9, cy - s * 0.1, s * 1.8, s * 1.6, iconStyle(el, "#ffffff")));
  svg.appendChild(rc.arc(cx, cy - s * 0.1, s * 1.2, s * 0.9, Math.PI, 2 * Math.PI, iconStyle(el)));
  svg.appendChild(rc.circle(cx, cy + s * 0.45, s * 0.4, iconStyle(el)));
  svg.appendChild(rc.line(cx, cy + s * 0.5, cx, cy + s * 0.9, iconStyle(el)));
}

function drawContainer(rc, cx, cy, s, el) {
  svg.appendChild(rc.rectangle(cx - s * 1.25, cy - s * 1.0, s * 2.5, s * 0.9, iconStyle(el)));
  svg.appendChild(rc.rectangle(cx - s * 1.25, cy + s * 0.1, s * 2.5, s * 0.9, iconStyle(el)));
}

function drawApi(rc, cx, cy, s, el) {
  const lb = `M ${cx - s * 0.9} ${cy - s * 1.0} C ${cx - s * 0.1} ${cy - s * 1.0}, ${cx - s * 0.35} ${cy}, ${cx - s * 0.9} ${cy} C ${cx - s * 0.1} ${cy}, ${cx - s * 0.35} ${cy + s * 1.0}, ${cx - s * 0.9} ${cy + s * 1.0}`;
  const rb = `M ${cx + s * 0.9} ${cy - s * 1.0} C ${cx + s * 0.1} ${cy - s * 1.0}, ${cx + s * 0.35} ${cy}, ${cx + s * 0.9} ${cy} C ${cx + s * 0.1} ${cy}, ${cx + s * 0.35} ${cy + s * 1.0}, ${cx + s * 0.9} ${cy + s * 1.0}`;
  svg.appendChild(rc.path(lb, iconStyle(el)));
  svg.appendChild(rc.path(rb, iconStyle(el)));
  svg.appendChild(rc.line(cx - s * 0.25, cy, cx + s * 0.25, cy, iconStyle(el)));
}

const ICONS = {
  database: drawDatabase,
  server: drawServer,
  cloud: drawCloud,
  cache: drawCache,
  queue: drawQueue,
  loadbalancer: drawLoadBalancer,
  client: drawClient,
  security: drawSecurity,
  container: drawContainer,
  api: drawApi,
};

/** Draw the component icon into the upper region of a shape (if any). */
function renderShapeIcon(el) {
  if (!el.symbol) return;
  const draw = ICONS[el.symbol];
  if (!draw) {
    console.warn(`Unknown symbol: ${el.symbol}`);
    return;
  }
  const b = shapeBounds(el);
  if (b.w < 90 || b.h < 70) return; // too small to host an icon
  const cx = b.x + b.w / 2;
  const cy = b.y + b.h * 0.24;
  const s = Math.min(b.w / 5, b.h * 0.15);
  draw(rc, cx, cy, s, el);
}
```

- [ ] **Step 2: 修改 `renderShapeLabels` 让图标存在时文字下移**

把 `renderShapeLabels` 开头替换为（新增 `blockCy` 计算 + 先画图标）：

```js
function renderShapeLabels(el) {
  const b = shapeBounds(el);
  const cx = b.x + b.w / 2;
  const maxW = innerTextWidth(el);
  const maxH = innerTextHeight(el);
  // When a component icon is present, the label block moves to the lower
  // part of the shape so the icon (top region) never overlaps the text.
  const blockCy = el.symbol ? b.y + b.h * 0.68 : b.y + b.h / 2;
  renderShapeIcon(el);
```

然后把 annotation 分支里的 `const cy = b.y + b.h / 2;` 改为 `const cy = blockCy;`，并把 label-only 分支里的 `renderCentered(cx, b.y + b.h / 2, el.label, labelSize, maxW);` 改为 `renderCentered(cx, blockCy, el.label, labelSize, maxW);`。

- [ ] **Step 3: 自测 —— 渲染带图标的图**

```bash
cat > /tmp/icon-test.json << 'EOF'
{
  "title": "Icon Test",
  "width": 960,
  "height": 420,
  "background": "#FAF8F5",
  "elements": [
    { "type": "text", "x": 480, "y": 30, "text": "Icon Test", "fontSize": 24 },
    { "type": "rectangle", "id": "db", "x": 50, "y": 90, "width": 180, "height": 110, "label": "PostgreSQL", "annotation": "primary · replica", "symbol": "database", "fill": "#d0bfff", "rounded": true },
    { "type": "rectangle", "id": "srv", "x": 240, "y": 90, "width": 180, "height": 110, "label": "Order Service", "annotation": "Java", "symbol": "server", "fill": "#b2f2bb", "rounded": true },
    { "type": "rectangle", "id": "cl", "x": 430, "y": 90, "width": 160, "height": 110, "label": "Web Client", "annotation": "React", "symbol": "client", "fill": "#a5d8ff", "rounded": true },
    { "type": "rectangle", "id": "cache", "x": 610, "y": 90, "width": 170, "height": 110, "label": "Redis Cache", "annotation": "hot reads", "symbol": "cache", "fill": "#99e9f2", "rounded": true },
    { "type": "rectangle", "id": "cls", "x": 810, "y": 90, "width": 120, "height": 110, "label": "Cloud", "symbol": "cloud", "fill": "#99e9f2", "rounded": true },
    { "type": "rectangle", "id": "api", "x": 50, "y": 260, "width": 180, "height": 110, "label": "API Gateway", "annotation": "auth", "symbol": "api", "fill": "#a5d8ff", "rounded": true },
    { "type": "rectangle", "id": "lb", "x": 240, "y": 260, "width": 180, "height": 110, "label": "Load Balancer", "annotation": "ALB", "symbol": "loadbalancer", "fill": "#dee2e6", "rounded": true },
    { "type": "rectangle", "id": "c", "x": 430, "y": 260, "width": 160, "height": 110, "label": "Container", "annotation": "3 replicas", "symbol": "container", "fill": "#a5d8ff", "rounded": true },
    { "type": "rectangle", "id": "q", "x": 610, "y": 260, "width": 170, "height": 110, "label": "SQS Queue", "annotation": "jobs", "symbol": "queue", "fill": "#fcc2d7", "rounded": true },
    { "type": "rectangle", "id": "sec", "x": 810, "y": 260, "width": 120, "height": 110, "label": "Security", "symbol": "security", "fill": "#fcc2d7", "rounded": true }
  ]
}
EOF
node ~/.trae-cn/skills/excalidraw-diagram/scripts/render.mjs /tmp/icon-test.json /tmp/excalidraw-out
```

Expected: 输出 SVG/PNG 两行 saved，无 "Unknown symbol" 警告（说明所有 10 个 symbol 都被识别）。
Run: `grep -c "<path" /tmp/excalidraw-out/icon-test.svg`
Expected: 数字 ≥ 40（rough 为每个原语生成多条 path，图标系统显著增加了 path 数量）。
Run: `open /tmp/excalidraw-out/icon-test.png`
Expected: 视觉检查 —— 白底、手绘线条、每个框内上部有对应图标（数据库圆柱 / 机架 / 显示器 / 云 / 圆形 LB / 队列条 / 双层容器 / 锁 / 花括号），文字在图标下方且不重叠。

---

### Task 3: 新增 references/component-icons.md

**Files:**
- Create: `~/.trae-cn/skills/excalidraw-diagram/references/component-icons.md`

- [ ] **Step 1: 写入图标目录文档**

````markdown
# Component Icons Reference

每个矩形/椭圆组件可通过 `symbol` 字段声明一个**手绘版标准图标**。渲染器会根据形状尺寸自动把图标画在框内上部（约 24% 高度处），标签与注解自动下移，互不重叠。

## 可用 symbol 及含义

| symbol | 含义 | 建议填充色 |
|--------|------|-----------|
| `database` | 数据库（圆柱体） | `#d0bfff` |
| `server` | 服务器 / 服务节点（机架） | `#b2f2bb` |
| `cloud` | 云 / CDN / 外部云服务 | `#99e9f2` |
| `cache` | 缓存（圆柱 + 闪电） | `#99e9f2` |
| `queue` | 消息队列 | `#fcc2d7` |
| `loadbalancer` | 负载均衡（圆 + 箭头） | `#dee2e6` |
| `client` | 客户端 / 用户（显示器） | `#a5d8ff` |
| `security` | 安全 / 鉴权（锁） | `#fcc2d7` |
| `container` | 容器 / Pod（堆叠盒） | `#a5d8ff` |
| `api` | API 网关（花括号） | `#a5d8ff` |

## 使用规则

1. **形状最小尺寸**：带 `symbol` 的矩形宽度 ≥ 90、高度 ≥ 70。低于此值时渲染器会跳过图标，只显示文字。
2. **推荐尺寸**：160-200 × 90-120，图标与文字都不会拥挤。
3. **带 annotation 时**：高度用 90-120；图标在顶部，标签在 68% 高度处，注解紧跟标签。
4. **不适用场景**：分区矩形（zone，用 `sectionLabel`）、箭头标签、独立文字 —— 不要加 `symbol`。
5. **语义配色**（与图标类别一致的柔和浅色填充）：

| 类别 | 填充 | 描边（默认 #1e1e1e） |
|------|------|------|
| 前端 / 接口 / API | `#a5d8ff` | 默认 |
| 后端 / 服务 / 存储 | `#b2f2bb` | 默认 |
| 数据库 | `#d0bfff` | 默认 |
| 缓存 / CDN / 网络 | `#99e9f2` | 默认 |
| 消息 / 事件 / 队列 | `#fcc2d7` | 默认 |
| 安全 / 鉴权 | `#fcc2d7` | 默认 |
| 基础设施 / LB | `#dee2e6` | 默认 |
| 外部 / 用户 / 角色 | `#a5d8ff` | 默认 |

6. **zone 背景色**（很淡，用于分组）：`#e7f5ff` 服务组 / `#ebfbee` 数据层 / `#f1f3f5` 外部 / `#fff9db` 安全边界。

## 示例

```json
{
  "type": "rectangle",
  "id": "db",
  "x": 60, "y": 300,
  "width": 180, "height": 110,
  "label": "PostgreSQL",
  "annotation": "primary · replica ×2",
  "symbol": "database",
  "fill": "#d0bfff",
  "rounded": true
}
```

## 自定义图标

如果某种组件不在上表中，去掉 `symbol` 用普通形状即可；或在渲染器 `render.mjs` 的 `ICONS` 注册表里新增一个绘制函数。
````

- [ ] **Step 2: 校验文档存在**

Run: `ls -la ~/.trae-cn/skills/excalidraw-diagram/references/`
Expected: 出现 `component-icons.md` 与 `element-format.md` 两个文件。

---

### Task 4: 更新 references/element-format.md（新增 symbol 字段）

**Files:**
- Modify: `~/.trae-cn/skills/excalidraw-diagram/references/element-format.md`

- [ ] **Step 1: 在 Rectangle 表格中追加 symbol 行**

找到 Rectangle 表格（`| sectionLabel | no | — | Top-left inset label for zone/group rectangles |` 行），在它后面追加一行：

```markdown
| `symbol` | no | — | 组件图标：database/server/cloud/cache/queue/loadbalancer/client/security/container/api。渲染器在框内上部自动绘制手绘图标，最小形状 90×70。详见 `component-icons.md` |
```

- [ ] **Step 2: 在文档末尾追加"组件图标"小节**

在文件末尾追加：

````markdown
---

## Component Icons (symbol)

给形状加 `symbol` 字段即可获得手绘版标准图标（数据库圆柱、机架服务器、云、缓存闪电、消息队列、负载均衡、客户端显示器、安全锁、容器、API 花括号）。

```json
{
  "type": "rectangle",
  "id": "db",
  "x": 60, "y": 300,
  "width": 180, "height": 110,
  "label": "PostgreSQL",
  "annotation": "primary · replica ×2",
  "symbol": "database",
  "fill": "#d0bfff",
  "rounded": true
}
```

规则：
- 最小形状 90×70，推荐 160-200 × 90-120。
- 图标画在框内上部，标签/注解自动下移到 68% 高度处，不会重叠。
- 分区矩形、箭头、独立文字不加 `symbol`。
- 完整目录、配色与示例见 `component-icons.md`。
````

- [ ] **Step 3: 校验**

Run: `grep -n "symbol" ~/.trae-cn/skills/excalidraw-diagram/references/element-format.md`
Expected: 至少出现 3 处（表格行 + 小节标题 + 示例 JSON）。

---

### Task 5: 重写 SKILL.md

**Files:**
- Modify: `~/.trae-cn/skills/excalidraw-diagram/SKILL.md`（整文件覆盖）

- [ ] **Step 1: 写入新 SKILL.md**

````markdown
---
name: excalidraw-diagram
description: 手绘草图风的系统架构图生成器。当用户要求画"系统架构图 / 架构示意 / 部署图 / 组件图 / 拓扑图"时使用。输出白底手绘风格的 SVG 与 PNG，组件使用标准图标（数据库圆柱、机架服务器、云、缓存、消息队列、负载均衡、客户端、安全、容器、API 网关），浅色语义配色，无需浏览器或外部服务。
user_invocable: true
---

# Excalidraw Diagram（手绘风架构图）

## 何时使用
- 用户请求系统架构图、微服务架构、部署拓扑、组件关系图、数据流图。
- 用户提到"手绘 / 草图 / excalidraw / 示意图"风格。

## 输出
- 本地渲染：`node <SKILL_DIR>/scripts/render.mjs <input.json> <输出目录>`
- 产出两个文件：`<slug>.svg` 与 `<slug>.png`（2x）。
- 默认输出目录：`~/Downloads/Excalidraw/`（可改下方配置）。

## 配置
```
OUTPUT_DIR = ~/Downloads/Excalidraw
```

## 工作流
1. **分析请求**：提取组件、关系、流向。流向决定布局方向（自上而下用于层级，自左而右用于数据流）。
2. **读取参考**：先读 `references/element-format.md`，再读 `references/component-icons.md`。
3. **规划布局**：计算画布尺寸（列数×形状宽 + 间距 + 边距，向上取整到 50）。分区用带 `sectionLabel` 的矩形，先画 zone 再画内部组件。
4. **生成 JSON**：形状加 `symbol`（见组件图标目录）、`label`、`annotation`；箭头用 `from`/`to` 引用 id，必要时指定 `fromSide`/`toSide`。
5. **渲染**：执行上面的 node 命令，确保输出目录存在（`mkdir -p`）。
6. **汇报**：给出 SVG 与 PNG 两个路径，并 `open` PNG 供用户查看。

## 关键规则（违反会出图错误）
- **图标**：数据库/缓存等组件必须用 `symbol` 标准图标，禁止用 emoji 或文字符号代替。带 `symbol` 的形状最小 90×70。
- **文字重叠**：禁止把描述文字放成独立 text 覆盖在形状上。标题描述用 `subtitle`，组件详情用 `annotation`，分组用 `sectionLabel`。
- **分区顺序**：zone 矩形必须排在它所包含组件的前面（先画的在底层）。
- **配色**：使用 component-icons.md 中的浅色语义填充；画布 `background` 用 `#FAF8F5`。
- **中文**：中文标签保持 2-6 字，形状宽度留足（约 20px/字），必要时用 `annotation` 分行。
- **roughness**：保持默认，不要改成 0（失去手绘感）。

## 常见场景模板
- **分层架构**：EDGE zone（client/cdn/api 网关）→ SERVICE zone（各服务）→ DATA zone（数据库/缓存/队列）。
- **微服务部署**：VPC zone + EKS 集群 zone（container 组件）+ 托管数据库 + 队列，箭头表示调用方向。
- **数据流**：client → gateway → service → cache → database，箭头标注协议（HTTPS/REST/SQL）。
````

- [ ] **Step 2: 校验 frontmatter**

Run: `head -8 ~/.trae-cn/skills/excalidraw-diagram/SKILL.md`
Expected: YAML frontmatter，`name: excalidraw-diagram`，description 以"手绘草图风的系统架构图生成器"开头。

---

### Task 6: 添加 examples 样例（兼作回归测试）

**Files:**
- Create: `~/.trae-cn/skills/excalidraw-diagram/examples/ecommerce.json`
- Create: `~/.trae-cn/skills/excalidraw-diagram/examples/microservices.json`
- Create: `~/.trae-cn/skills/excalidraw-diagram/examples/chinese.json`

- [ ] **Step 1: ecommerce.json**

```json
{
  "title": "E-Commerce System Architecture",
  "width": 980,
  "height": 590,
  "background": "#FAF8F5",
  "elements": [
    { "type": "text", "x": 490, "y": 35, "text": "E-Commerce Architecture", "fontSize": 26, "subtitle": "web -> gateway -> services -> data layer" },
    { "type": "rectangle", "x": 40, "y": 90, "width": 900, "height": 150, "fill": "#e7f5ff", "sectionLabel": "EDGE / FRONTEND" },
    { "type": "rectangle", "id": "client", "x": 70, "y": 130, "width": 160, "height": 90, "label": "Web Client", "annotation": "React · mobile", "symbol": "client", "fill": "#a5d8ff", "rounded": true },
    { "type": "rectangle", "id": "cdn", "x": 330, "y": 130, "width": 160, "height": 90, "label": "CDN", "annotation": "static assets", "symbol": "cloud", "fill": "#99e9f2", "rounded": true },
    { "type": "rectangle", "id": "gateway", "x": 590, "y": 130, "width": 200, "height": 90, "label": "API Gateway", "annotation": "rate-limit · auth", "symbol": "api", "fill": "#a5d8ff", "rounded": true },
    { "type": "rectangle", "x": 40, "y": 290, "width": 900, "height": 260, "fill": "#ebfbee", "sectionLabel": "SERVICE LAYER" },
    { "type": "rectangle", "id": "order", "x": 70, "y": 340, "width": 170, "height": 110, "label": "Order Service", "annotation": "Java · Spring", "symbol": "server", "fill": "#b2f2bb", "rounded": true },
    { "type": "rectangle", "id": "inv", "x": 280, "y": 340, "width": 170, "height": 110, "label": "Inventory Svc", "annotation": "Go · gRPC", "symbol": "server", "fill": "#b2f2bb", "rounded": true },
    { "type": "rectangle", "id": "redis", "x": 490, "y": 340, "width": 170, "height": 110, "label": "Redis Cache", "annotation": "hot reads", "symbol": "cache", "fill": "#99e9f2", "rounded": true },
    { "type": "rectangle", "id": "pg", "x": 700, "y": 340, "width": 190, "height": 110, "label": "PostgreSQL", "annotation": "primary · replica ×2", "symbol": "database", "fill": "#d0bfff", "rounded": true },
    { "type": "rectangle", "id": "mq", "x": 70, "y": 470, "width": 220, "height": 80, "label": "Message Queue", "annotation": "orders topic", "symbol": "queue", "fill": "#fcc2d7", "rounded": true },
    { "type": "arrow", "from": "client", "to": "cdn", "label": "HTTPS" },
    { "type": "arrow", "from": "client", "to": "gateway", "fromSide": "bottom", "toSide": "bottom", "label": "REST" },
    { "type": "arrow", "from": "gateway", "to": "order", "fromSide": "bottom", "toSide": "top" },
    { "type": "arrow", "from": "gateway", "to": "inv", "fromSide": "bottom", "toSide": "top" },
    { "type": "arrow", "from": "order", "to": "inv", "fromSide": "right", "toSide": "left" },
    { "type": "arrow", "from": "inv", "to": "redis", "fromSide": "right", "toSide": "left" },
    { "type": "arrow", "from": "redis", "to": "pg", "fromSide": "right", "toSide": "left" },
    { "type": "arrow", "from": "order", "to": "mq", "fromSide": "bottom", "toSide": "top", "label": "publish" }
  ]
}
```

- [ ] **Step 2: microservices.json**

```json
{
  "title": "Microservices Deployment",
  "width": 960,
  "height": 520,
  "background": "#FAF8F5",
  "elements": [
    { "type": "text", "x": 480, "y": 35, "text": "Microservices Deployment", "fontSize": 26, "subtitle": "LB -> containers -> DB, inside a VPC" },
    { "type": "rectangle", "x": 40, "y": 60, "width": 880, "height": 330, "fill": "#fff9db", "sectionLabel": "AWS VPC" },
    { "type": "rectangle", "x": 360, "y": 70, "width": 220, "height": 300, "fill": "#e7f5ff", "sectionLabel": "EKS CLUSTER" },
    { "type": "rectangle", "id": "lb", "x": 60, "y": 130, "width": 240, "height": 90, "label": "Load Balancer", "annotation": "ALB · TLS", "symbol": "loadbalancer", "fill": "#dee2e6", "rounded": true },
    { "type": "rectangle", "id": "api", "x": 380, "y": 90, "width": 180, "height": 100, "label": "API Container", "annotation": "3 replicas", "symbol": "container", "fill": "#a5d8ff", "rounded": true },
    { "type": "rectangle", "id": "worker", "x": 380, "y": 220, "width": 180, "height": 100, "label": "Worker Pod", "annotation": "async tasks", "symbol": "container", "fill": "#a5d8ff", "rounded": true },
    { "type": "rectangle", "id": "db", "x": 660, "y": 150, "width": 200, "height": 110, "label": "PostgreSQL", "annotation": "managed RDS", "symbol": "database", "fill": "#d0bfff", "rounded": true },
    { "type": "rectangle", "id": "mq", "x": 660, "y": 280, "width": 200, "height": 80, "label": "SQS Queue", "annotation": "jobs", "symbol": "queue", "fill": "#fcc2d7", "rounded": true },
    { "type": "arrow", "from": "lb", "to": "api", "fromSide": "right", "toSide": "left" },
    { "type": "arrow", "from": "lb", "to": "worker", "fromSide": "right", "toSide": "left" },
    { "type": "arrow", "from": "api", "to": "db", "fromSide": "right", "toSide": "left" },
    { "type": "arrow", "from": "worker", "to": "mq", "fromSide": "right", "toSide": "left" },
    { "type": "arrow", "from": "mq", "to": "db", "fromSide": "top", "toSide": "bottom", "label": "consume" }
  ]
}
```

- [ ] **Step 3: chinese.json**

```json
{
  "title": "中文标签示例",
  "width": 820,
  "height": 400,
  "background": "#FAF8F5",
  "elements": [
    { "type": "text", "x": 410, "y": 35, "text": "订单处理流程", "fontSize": 26, "subtitle": "用户下单到订单落库" },
    { "type": "rectangle", "id": "user", "x": 50, "y": 120, "width": 160, "height": 90, "label": "用户客户端", "annotation": "App · 小程序", "symbol": "client", "fill": "#a5d8ff", "rounded": true },
    { "type": "rectangle", "id": "order", "x": 300, "y": 120, "width": 180, "height": 90, "label": "订单服务", "annotation": "校验 · 幂等", "symbol": "server", "fill": "#b2f2bb", "rounded": true },
    { "type": "rectangle", "id": "db", "x": 580, "y": 120, "width": 180, "height": 90, "label": "订单数据库", "annotation": "MySQL · 主从", "symbol": "database", "fill": "#d0bfff", "rounded": true },
    { "type": "rectangle", "id": "lock", "x": 300, "y": 260, "width": 180, "height": 80, "label": "风控校验", "annotation": "登录态", "symbol": "security", "fill": "#fcc2d7", "rounded": true },
    { "type": "arrow", "from": "user", "to": "order", "label": "提交订单" },
    { "type": "arrow", "from": "order", "to": "db", "label": "INSERT" },
    { "type": "arrow", "from": "order", "to": "lock", "fromSide": "bottom", "toSide": "top" }
  ]
}
```

- [ ] **Step 4: 渲染三个样例并核验**

```bash
mkdir -p /tmp/excalidraw-examples
for f in ecommerce microservices chinese; do
  node ~/.trae-cn/skills/excalidraw-diagram/scripts/render.mjs \
    ~/.trae-cn/skills/excalidraw-diagram/examples/$f.json /tmp/excalidraw-examples
done
```

Expected: 6 行 saved 输出（3 个 svg + 3 个 png），无 "Unknown symbol"、无 "Arrow references missing element" 警告。
Run: `open /tmp/excalidraw-examples/ecommerce.png /tmp/excalidraw-examples/microservices.png /tmp/excalidraw-examples/chinese.png`
Expected: 视觉检查 —— 白底手绘风；数据库/缓存/队列等图标正确出现在框内上部；中文标签无溢出；箭头无穿越文字。

---

### Task 7: 删除旧 skill + 端到端验收

**Files:**
- Delete: `~/.trae-cn/skills/architecture-diagram/`（整个目录）

- [ ] **Step 1: 删除旧 skill**

```bash
rm -rf ~/.trae-cn/skills/architecture-diagram
```

Run: `ls ~/.trae-cn/skills | grep architecture`
Expected: 无输出（已删除）。`ls ~/.trae-cn/skills | grep excalidraw` 应输出 `excalidraw-diagram`。

- [ ] **Step 2: 新旧同描述对比**

用旧 skill 的设计文档中"电商系统"同一组组件（客户端 / API 网关 / 后端服务 / 数据库 / 缓存 / 消息队列 / 云分区），用新 skill 渲染：

```bash
node ~/.trae-cn/skills/excalidraw-diagram/scripts/render.mjs \
  ~/.trae-cn/skills/excalidraw-diagram/examples/ecommerce.json /tmp/excalidraw-examples
open /tmp/excalidraw-examples/ecommerce.png
```

Expected: 视觉确认 —— 浅色白底 ✓、手绘线条 ✓、组件用标准图标（数据库圆柱、缓存闪电、队列条）而非 emoji ✓、无文字重叠 ✓。

- [ ] **Step 3: 清理临时文件**

```bash
rm -rf /tmp/excalidraw-out /tmp/excalidraw-examples /tmp/smoke.json /tmp/icon-test.json
```

---

### Task 8: 提交设计文档与计划（需用户明确同意后才执行）

**Files:**
- Add: `docs/superpowers/specs/2026-08-10-excalidraw-diagram-skill-design.md`
- Add: `docs/superpowers/plans/2026-08-10-excalidraw-diagram-skill.md`

- [ ] **Step 1: 提交（git 安全规则：必须先征得用户同意）**

```bash
git add docs/superpowers/specs/2026-08-10-excalidraw-diagram-skill-design.md \
        docs/superpowers/plans/2026-08-10-excalidraw-diagram-skill.md
git commit -m "docs: add design spec and implementation plan for excalidraw-diagram skill"
```

Expected: 提交成功，`git status` 干净。
