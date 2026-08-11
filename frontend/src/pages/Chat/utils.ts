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
 * ```svg / ~~~svg 代码块还原为原始 SVG 标记，且为缺 viewBox 的 SVG 注入
 * viewBox（窄容器自适应不裁剪），再由 DOMPurify 消毒（允许基本图形/渐变/
 * 滤镜，阻止脚本与外部资源）。
 */
export function renderMarkdown(content: string, agentNames: string[] = []): string {
  const processed = String(content ?? '')
    // LLM 输出的字面 \n（backslash + n）转为真实换行，否则 marked 会原样保留，
    // 导致用户看到 "\n\n" 而非段落分隔。
    .replace(/\\n/g, '\n')
    // ```svg / ~~~svg 代码块还原为原始 SVG 标记。svg 不在 marked 的块级 HTML
    // 标签列表里，必须用空行把 <svg> 与上下文隔开，marked 才会整体透传而非
    // 当作行内 HTML 逐行解析（否则空白行会注入 <p>/<br>，导致 SVG 结构被破坏）。
    .replace(/(```|~~~)\s*svg[ \t]*\r?\n([\s\S]*?)\1/g, (_, _fence, code) => '\n\n' + code.trim() + '\n\n')
    // 为缺少 viewBox 的 <svg> 注入 viewBox（依据 width/height 属性）。
    // 聊天框较窄时 .md-body svg 的 max-width:100%;height:auto 只缩放元素盒子，
    // 无 viewBox 的 SVG 内容不随之缩放，右侧会被整体裁剪；注入后内容等比缩放，
    // 整图在任何容器宽度下完整显示。
    .replace(/<svg\b([^>]*)>/g, (_, attrs) => {
      if (/viewBox\s*=/.test(attrs)) return '<svg' + attrs + '>';
      const w = attrs.match(/\bwidth\s*=\s*["']([\d.]+)/);
      const h = attrs.match(/\bheight\s*=\s*["']([\d.]+)/);
      if (w && h) return `<svg viewBox="0 0 ${w[1]} ${h[1]}"` + attrs + '>';
      return '<svg' + attrs + '>';
    });
  const raw = marked.parse(processed, { async: false }) as string;
  // 显式启用 SVG profile，否则 DOMPurify 默认只处理 HTML，会把 rect/line/path/marker/defs
  // 等 SVG 子元素以及 viewBox/fill/stroke/marker-end 等属性全部当作不安全标签清除。
  const safe = DOMPurify.sanitize(raw, {
    USE_PROFILES: { html: true, svg: true },
    ADD_ATTR: ['target'],
  });
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
