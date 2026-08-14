# 修复前端 HTML/SVG 渲染 Spec

## Why
用户反馈"阿源"（AI Agent）在聊天中输出的 HTML/SVG 树形图在前端聊天框无法正常展示。已定位根因：`renderMarkdown` 只对 ```` ```svg ```` fence 包裹的 SVG 做保护，而真实消息是**裸 `<svg>`（无 fence）**，其内部空行会被 marked 注入 `<p>` 标签，浏览器 foreign-content 解析规则据此把 `<svg>` 内的图形元素（`rect/line/path/marker`）推出 SVG 命名空间，DOMPurify 再按 HTML 白名单将其剥离——最终只残留一个 `text` 子元素，图形整体消失。裸块级 HTML（`<div>` 等）存在同根因：内部空行被 marked 注入 `<p>`，破坏 HTML 结构。

## What Changes
- [frontend/src/pages/Chat/utils.ts](file:///Users/Zhuanz/IdeaProjects/DingRingJ/frontend/src/pages/Chat/utils.ts)：在 `marked.parse` **之前**，用占位符 token 提取所有裸 `<svg>...</svg>` 与裸块级 HTML（div/table/section/article/pre 等），marked 解析完后再还原原始标记。这样 marked 不再触碰 HTML/SVG 块内部，杜绝 `<p>/<br>` 注入。
- 保留并完善已有修复：字面 `\\n` 转真实换行、fence（```svg/~~~svg）还原、viewBox 注入、DOMPurify `USE_PROFILES {html, svg}`。
- 新增真实浏览器验证脚本（agent-browser / Chrome CDP），替换不可信的 jsdom 断言作为回归依据。

## Impact
- Affected specs：消息渲染能力（Chat 消息气泡、SYSTEM 结论、Topics 结论弹窗、Chat 结论预览——4 处共用 `renderMarkdown`）。
- Affected code：`frontend/src/pages/Chat/utils.ts`；复用方 `MessageItem.tsx`、`Topics/index.tsx`、`Chat/index.tsx`（无需改动，自动生效）。
- 测试资产：`/tmp/svgtest/`（真实消息 352/356、浏览器验证页、标记脚本）。

## ADDED Requirements
### Requirement: 裸 `<svg>`（无 fence）完整渲染
系统 SHALL 在 marked 解析前保护消息中的裸 `<svg>...</svg>` 块（不要求 ```svg fence），使 SVG 内所有图形元素（rect/line/path/marker/defs/text）经 DOMPurify 消毒后完整保留并正确显示。

#### Scenario: 含空行的裸 SVG 消息
- **WHEN** 消息内容为"说明文字 + 换行 + 裸 `<svg>...</svg>`（内部含空行与注释）+ 后续文字"
- **THEN** 聊天气泡中 SVG 完整显示，图形元素齐全（真实浏览器 DOM 中 svg 子元素数 > 10，含 rect/line/path/marker/defs），且 `<svg>` 内部不出现 `<p>/<br>` 标签

#### Scenario: fence 包裹的 SVG 不受影响
- **WHEN** 消息中 SVG 以 ```` ```svg ```` 或 `~~~svg` fence 包裹
- **THEN** 行为与裸 `<svg>` 一致，完整渲染

### Requirement: 裸块级 HTML 结构不被破坏
系统 SHALL 保护消息中的裸块级 HTML（div/table/section/article/pre 等），使其内部空行不被 marked 注入 `<p>`，保持原始结构。

#### Scenario: 含空行的裸 div
- **WHEN** 消息内容包含 `<div>...</div>` 且内部含空行
- **THEN** DOMPurify 消毒后 div 内部不出现标记注入的 `<p>`，结构保持原样

### Requirement: 安全属性不回归
系统 SHALL 保持现有安全基线：`DOMPurify.sanitize` 使用 `USE_PROFILES {html: true, svg: true}` + `ADD_ATTR ['target']`，脚本/事件属性仍被剥离。

#### Scenario: XSS 尝试
- **WHEN** 消息中 SVG/HTML 内包含 `<script>`、`onload` 等恶意载荷
- **THEN** 被 DOMPurify 剥离，页面不执行脚本

## MODIFIED Requirements
### Requirement: 字面 `\\n` 与 viewBox（保持既有修复）
系统 SHALL 继续执行：字面 `\n`（backslash + n）转为真实换行；缺 `viewBox` 的 `<svg>` 依据 width/height 注入 `viewBox="0 0 w h"`，窄容器下不裁剪。

## REMOVED Requirements
### Requirement: 仅 fence 场景的 SVG 还原（旧逻辑）
**Reason**：只匹配 ```` ```svg/~~~svg ````，无法覆盖真实消息的裸 `<svg>` 格式，是本次缺陷的直接原因。
**Migration**：改为"占位符提取 + marked 后还原"的通用方案，覆盖裸 `<svg>` 与裸块级 HTML 两类输入。
