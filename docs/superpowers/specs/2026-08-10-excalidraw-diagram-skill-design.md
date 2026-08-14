# excalidraw-diagram Skill 设计（手绘风 + 标准组件图标）

- 日期：2026-08-10
- 状态：待用户评审
- 相关文件：[architecture-diagram/SKILL.md](file:///Users/Zhuanz/.trae-cn/skills/architecture-diagram/SKILL.md)（将被替换）

## 1. 背景与问题

当前 `architecture-diagram` skill（源自 Cocoon AI 的 architecture-diagram-generator）存在两个问题：

1. **视觉风格不被认可**：深色 slate-950 底 + 40px 网格 + JetBrains Mono 等宽字体 + 半透明彩色描边 + 脉冲圆点，用户明确不喜欢深色背景、不喜欢该字体，且组件之间没有图标区分（数据库等没有标准图标）。
2. **技能文件损坏**：SKILL.md 引用了 `templates/template.html`，但该目录/文件实际不存在，技能无法完整执行。

用户诉求（已确认）：
- 浅色背景
- 不同组件用不同图标表示（如数据库用标准圆柱图标）
- 整体气质偏好手绘草图风

## 2. 方案决策（用户已批准）

- **底子**：fork [aref-vc/excalidraw-skill](https://github.com/aref-vc/excalidraw-skill)（MIT）。理由：本地 node 脚本渲染（roughjs + xmldom + resvg），输出 SVG + PNG 打开即看，无需浏览器/MCP；Virgil 手写字体 + Cascadia 等宽；机器已具备 Node v23.11.0 / npm 10.9.2。
- **旧 skill 处理**：直接替换。删除 `~/.trae-cn/skills/architecture-diagram/`，新建 `~/.trae-cn/skills/excalidraw-diagram/`。
- **视觉**：白底画布、深灰描边、柔和语义色填充、roughness 手绘质感。
- **核心增强**：在底子之上增加"标准组件图标库"，用 rough 原语绘制手绘版标准符号（数据库圆柱等），非 emoji。

## 3. 交付物结构

新建目录 `~/.trae-cn/skills/excalidraw-diagram/`：

```
excalidraw-diagram/
├── SKILL.md                     # 重写工作流（分析 → 规划 → 生成 JSON → 渲染 → 汇报）
├── scripts/
│   ├── render.mjs               # fork 自底子，扩展 symbol 图标绘制
│   └── package.json             # 依赖：roughjs / xmldom / resvg（与底子一致，无新增）
└── references/
    ├── element-format.md        # fork 自底子，扩展 symbol 字段说明
    └── component-icons.md       # 新增：标准组件图标目录 + 绘制配方
```

安装步骤：`git clone` 仓库到 `~/.trae-cn/skills/excalidraw-diagram`。保留 `SKILL.md`、`scripts/`（render.mjs + package.json）、`fonts/`（Virgil/Cascadia 字体）、`references/`、`LICENSE`；删除 `assets/` 下的示例图片（运行时不需要）。`cd scripts && npm install` 安装依赖。

## 4. 标准组件图标系统（核心增强）

### 4.1 元素格式扩展

形状元素新增可选字段：

```json
{
  "type": "rectangle",
  "id": "rect_db",
  "symbol": "database",
  "label": "PostgreSQL",
  "annotation": "Primary · replica ×2"
}
```

合法取值：`database | server | cloud | cache | queue | loadbalancer | client | security | container | api | generic`

- 渲染器读取 `symbol`，根据形状边界（x/y/width/height）在框内**居中自动缩放**绘制图标。
- 图标绘制使用 rough.js 原语（ellipse / line / path / polygon），保持手绘质感。
- 无 `symbol` 或取值为 `generic` 时不画图标（纯文字框），避免画错。

### 4.2 图标配方（手绘版标准符号）

| symbol | 手绘配方 | 说明 |
|---|---|---|
| `database` | 顶部椭圆 + 两条竖线 + 底部弧线 + 2~3 条横线 | 标准数据库圆柱 |
| `server` | 圆角矩形 + 2~3 条横向槽线 + 底部小圆点 | 机架服务器 |
| `cloud` | 经典云路径（4 段圆弧 + 底部直线） | 云服务 |
| `cache` | 小圆柱 + 圆柱内闪电折线 | 缓存（Redis 等） |
| `queue` | 上下堆叠的 3 条短横条 + 右侧箭头 | 消息队列 |
| `loadbalancer` | 圆形 + 左入右出箭头 | 负载均衡 |
| `client` | 显示器矩形 + 底座线条（或人形剪影） | 客户端/用户 |
| `security` | 锁形（圆弧 + 锁体 + 钥匙孔）或盾牌 | 安全组件 |
| `container` | 2 个堆叠的小圆角矩形 | 容器 |
| `api` | 左右花括号 + 中间横线 | API 网关 |

图标只占形状上部约 1/3~1/2 高度区域，标签与注解在图标下方，互不重叠。

### 4.3 语义配色（柔和浅色系）

| 组件类型 | 填充 | 描边/文字 |
|---|---|---|
| 前端 | `#dbeafe` | `#2563eb` |
| 后端 | `#dcfce7` | `#16a34a` |
| 数据库 | `#ede9fe` | `#7c3aed` |
| 云 | `#fef3c7` | `#d97706` |
| 消息队列 | `#ffedd5` | `#ea580c` |
| 安全 | `#ffe4e6` | `#e11d48` |
| 外部/通用 | `#f1f5f9` | `#475569` |

画布背景 `#ffffff`，描边默认 `#1e1e1e`。

## 5. SKILL.md 工作流

1. **分析请求**：提取组件、关系、流向（自上而下 / 自左而右）。
2. **读取参考**：`references/element-format.md` + `references/component-icons.md`。
3. **生成 JSON**：形状（含 `symbol`、`label`、`annotation`）+ 箭头（带 `startBinding`/`endBinding`）+ 分区（`sectionLabel`）。
4. **渲染**：`node <SKILL_DIR>/scripts/render.mjs <json> "<输出目录>"`，输出 SVG + PNG(2x)。
5. **汇报**：给出两个文件路径，打开 PNG 供检查。

输出目录默认 `~/Downloads/Excalidraw/`（可在 SKILL.md 顶部配置区修改）。

### 常见错误规避（继承底子并补充）
- 禁止文字重叠：用 `subtitle` / `annotation` / `sectionLabel`，不另建悬浮 text。
- 图标溢出：带 `symbol` 的形状最小尺寸 80×80，图标自动缩放不超过形状内边距。
- `roughness` 保持 1，不要改成 0。
- 中文标签按 ~20px/字 估算宽度，形状宽度留足。

## 6. 测试与验收

1. `cd scripts && npm install` 无报错。
2. 样例 1（电商架构）：客户端 → API 网关 → 后端服务 → PostgreSQL（`database` 圆柱图标）+ Redis（`cache`）+ 消息队列 + 云分区，验证：白底、手绘感、图标在框内、无文字重叠。
3. 样例 2（微服务部署）：服务器 + 容器 + 负载均衡 + 云。
4. 样例 3（中文标签图）：验证中文渲染不溢出。
5. 用旧 `architecture-diagram` skill 的同一"电商系统"描述生成对比图，确认新风格满足：浅色、标准图标、手绘风。

## 7. 不在范围内

- MCP / 实时画布集成（robonuggets 方案）
- 动图 GIF、PDF 导出
- 架构图以外的图表类型（时序图、类图、ER 图等）——底子支持流程图/架构草图即可
- 多风格切换（fireworks-tech-graph 那类多主题不在本次范围）
