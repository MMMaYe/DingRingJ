/** Chat 页共用的纯函数工具（独立文件以保证组件文件的 Fast Refresh） */

import { marked } from 'marked';
import DOMPurify from 'dompurify';

export function escapeHtml(t: string) {
  return String(t ?? '').replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;').replaceAll("'", '&#39;');
}

// GFM + 单换行转 <br>，贴近聊天场景的书写习惯
marked.setOptions({ gfm: true, breaks: true });

// 链接一律新窗口打开（sanitize 后统一补 target/rel，避免消息内跳转丢失会话）
DOMPurify.addHook('afterSanitizeAttributes', node => {
  if (node.tagName === 'A') {
    node.setAttribute('target', '_blank');
    node.setAttribute('rel', 'noopener noreferrer');
  }
});

/**
 * 消息内容渲染：Markdown 解析 + 原生 HTML 透传，DOMPurify 消毒防 XSS，
 * 最后仅在文本节点上做 @提及高亮（跳过标签，避免污染属性或代码内容）。
 *
 * ```svg 代码块会被提取为原始 SVG 标签（块内换行折叠为空格，避免 marked
 * 注入 <p>/<br> 破坏 SVG 结构），再由 DOMPurify 消毒（允许基本图形/渐变/
 * 滤镜，阻止脚本与外部资源）。
 */
export function renderMarkdown(content: string, agentNames: string[] = []): string {
  // 将 ```svg 代码块还原为原始 SVG，使 marked 识别为 HTML 块而非代码块。
  // 注意：svg 不在 marked 的块级 HTML 标签列表里，原样透传会被当作行内 HTML，
  // 块内的空白行会触发 marked 注入 <p>/<br>，浏览器解析时会跳出 SVG 命名空间，
  // 导致 DOMPurify 把 rect/line/path/marker 等绝大多数元素剥离、SVG 无法渲染。
  // 因此把 svg 块内部换行折叠为空格（SVG 对空白不敏感），使其整体作为一段行内 HTML 透传。
  const processed = String(content ?? '').replace(/```svg\s*\n([\s\S]*?)```/g, (_, svg) =>
    '\n\n' + svg.trim().replace(/\s*\n\s*/g, ' ') + '\n\n',
  );
  const raw = marked.parse(processed, { async: false }) as string;
  const safe = DOMPurify.sanitize(raw, { ADD_ATTR: ['target'] });
  if (agentNames.length === 0) return safe;
  return safe.split(/(<[^>]*>)/g).map(part => {
    if (part.startsWith('<')) return part;
    return agentNames.reduce(
      (acc, name) => acc.replaceAll('@' + escapeHtml(name), `<span class="mention">@${escapeHtml(name)}</span>`),
      part,
    );
  }).join('');
}

export function formatTime(iso: string | null) {
  if (!iso) return '';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return '';
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  const hm = `${pad(d.getHours())}:${pad(d.getMinutes())}`;
  if (d.toDateString() === now.toDateString()) return hm;
  return `${d.getMonth() + 1}/${d.getDate()} ${hm}`;
}
