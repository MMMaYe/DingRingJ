# Tasks

## Task 1: 实现占位符保护机制（utils.ts 核心修复）

* [x] 在 `renderMarkdown` 中、`marked.parse` 之前新增 `protectBlocks()`：用正则提取裸 `<svg\b[^>]*>[\s\S]*?<\/svg>` 与裸块级 HTML（div/table/section/article/pre/ul/ol/blockquote）为占位符 token（如 `\u0000BLOCK0\u0000`，确保不被 marked 解析为强调/代码），marked 后再还原

* [x] 保留现有处理顺序：字面 `\\n` 转换 → fence 还原 → 块保护 → viewBox 注入 → marked → 还原占位符 → DOMPurify

* [x] 占位符 token 必须保证：还原发生在 DOMPurify 之前（XSS 仍被消毒）；token 本身不进入最终 HTML

## Task 2: 建立真实浏览器验证基线

* [x] 生成浏览器验证页（引入前端同版 `dompurify/dist/purify.min.js` + marked），用例覆盖：裸 svg（含空行/注释）、fence svg（\`\`\` 与 \~\~\~）、已有 viewBox、多图、裸 div/table、提及、XSS

* [x] 用 agent-browser（真实 Chrome CDP）断言：svg 子元素完整（rect/line/path/marker/defs 均保留）、块内无 `<p>/<br>` 注入、XSS 载荷被剥离

* [x] 明确 jsdom 断言不可信（SVG 命名空间差异），回归一律走真实浏览器

* [x] 额外修复：提及高亮跳过 svg 内文本（避免插入 `<span>` 破坏 foreign-content 结构）

## Task 3: 端到端验证真实消息

* [x] 用真实消息 352/356（数据库导出）跑完整渲染管线，浏览器断言 svg 子元素数 > 10

* [x] 覆盖 420px 窄容器：viewBox 生效、图形不右裁

* [x] 构建前端 `npm run build`，确认构建产物含占位符逻辑与 `USE_PROFILES:{html:!0,svg:!0}`

# Task Dependencies

* \[Task 2] depends on \[Task 1]（用修复后管线验证）

* \[Task 3] depends on \[Task 1] 与 \[Task 2]

