# Checklist

- [x] `renderMarkdown` 在 marked 前提取裸 `<svg>` 与裸块级 HTML 为占位符，marked 后还原，块内无 `<p>/<br>` 注入
- [x] 裸 `<svg>`（无 fence、含空行/注释）经真实浏览器验证：svg 子元素完整（rect/line/path/marker/defs/text 均保留，子元素数 > 10）
- [x] fence 包裹（```svg / ~~~svg）与裸 `<svg>` 行为一致，完整渲染
- [x] 裸块级 HTML（div/table 等）内部结构不被 marked 注入 `<p>` 破坏
- [x] 既有修复保留：字面 `\\n` 转真实换行、viewBox 注入（窄容器不右裁）、DOMPurify `USE_PROFILES {html,svg}` + `ADD_ATTR ['target']`
- [x] XSS 载荷（`<script>`/`onload`）仍被 DOMPurify 剥离
- [x] 真实消息 352/356 端到端验证通过（浏览器 DOM 断言，非 jsdom）
- [x] 前端构建成功，产物包含占位符逻辑与 SVG profile 配置
