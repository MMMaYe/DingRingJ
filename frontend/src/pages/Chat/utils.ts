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
 * 在 marked 解析前，把裸 <svg> 与裸块级 HTML（div/table/section/article/pre/ul/ol/blockquote）
 * 提取为占位符 token，marked 解析后再还原。
 * 原因：svg 不在 marked 的块级 HTML 标签列表里，内部空行会被 marked 注入 <p>，
 * 浏览器 foreign-content 规则据此把图形元素推出 SVG 命名空间，DOMPurify 按 HTML 白名单剥离；
 * 占位符保护从源头杜绝这种注入，裸块级 HTML 同样受益（避免内部被注入 <p>）。
 */
function protectBlocks(content: string): { text: string; blocks: string[] } {
  const blocks: string[] = [];
  const TOKEN = (i: number) => '\u0000BLOCK' + i + '\u0000';
  let t = content;
  t = t.replace(/<svg\b[^>]*>[\s\S]*?<\/svg>/g, m => TOKEN(blocks.push(m) - 1));
  t = t.replace(/<(div|table|section|article|pre|ul|ol|blockquote)\b[^>]*>[\s\S]*?<\/\1>/g, m => TOKEN(blocks.push(m) - 1));
  return { text: t, blocks };
}

/** 还原 protectBlocks 提取的占位符（必须在 DOMPurify.sanitize 之前，XSS 仍被消毒） */
function restoreBlocks(text: string, blocks: string[]): string {
  return text.replace(/\u0000BLOCK(\d+)\u0000/g, (_, i) => blocks[+i] ?? '');
}

/**
 * 消息内容渲染：Markdown 解析 + 原生 HTML 透传，DOMPurify 消毒防 XSS，
 * 最后仅在文本节点上做 @提及高亮（跳过标签，避免污染属性或代码内容）。
 *
 * ```svg / ~~~svg 代码块还原为原始 SVG 标记，且为缺 viewBox 的 SVG 注入
 * viewBox（窄容器自适应不裁剪），再由 DOMPurify 消毒（允许基本图形/渐变/
 * 滤镜，阻止脚本与外部资源）。
 *
 * 裸 <svg> 与裸块级 HTML 在 marked 解析前先被提取为占位符、解析后还原：
 * svg 不在 marked 的块级 HTML 标签列表里，内部空行会被注入 <p>，浏览器
 * foreign-content 规则据此把图形元素推出 SVG 命名空间，DOMPurify 按 HTML
 * 白名单剥离导致图形消失；占位符保护从源头杜绝这类结构破坏。
 */
export function renderMarkdown(content: string, agentNames: string[] = []): string {
  const { text, blocks } = protectBlocks(
    String(content ?? '')
      // LLM 输出的字面 \n（backslash + n）转为真实换行，否则 marked 会原样保留，
      // 导致用户看到 "\n\n" 而非段落分隔。
      .replace(/\\n/g, '\n')
      // ```svg / ~~~svg 代码块还原为原始 SVG 标记。svg 不在 marked 的块级 HTML
      // 标签列表里，必须用空行把 <svg> 与上下文隔开，marked 才会整体透传。
      .replace(/(```|~~~)\s*svg[ \t]*\r?\n([\s\S]*?)\1/g, (_, _fence, code) => '\n\n' + code.trim() + '\n\n')
      // 为缺少 viewBox 的 <svg> 注入 viewBox（依据 width/height 属性），
      // 窄容器下 .md-body svg 的 max-width:100%;height:auto 才能等比缩放内容不裁剪。
      .replace(/<svg\b([^>]*)>/g, (_, attrs) => {
        if (/viewBox\s*=/.test(attrs)) return '<svg' + attrs + '>';
        const w = attrs.match(/\bwidth\s*=\s*["']([\d.]+)/);
        const h = attrs.match(/\bheight\s*=\s*["']([\d.]+)/);
        if (w && h) return `<svg viewBox="0 0 ${w[1]} ${h[1]}"` + attrs + '>';
        return '<svg' + attrs + '>';
      }),
  );
  const raw = marked.parse(text, { async: false }) as string;
  // 还原占位符（在消毒之前），让裸 <svg> / 裸块级 HTML 保持原始结构参与消毒
  const restored = restoreBlocks(raw, blocks);
  // 显式启用 SVG profile，否则 DOMPurify 默认只处理 HTML，会把 rect/line/path/marker/defs
  // 等 SVG 子元素以及 viewBox/fill/stroke/marker-end 等属性全部当作不安全标签清除。
  const safe = DOMPurify.sanitize(restored, {
    USE_PROFILES: { html: true, svg: true },
    ADD_ATTR: ['target'],
  });
  if (agentNames.length === 0) return safe;
  // 提及高亮：仅在普通文本上替换。用标签切分后逐段处理，
  // 同时维护 <svg> 嵌套深度——svg 内部的 <text> 是 SVG 元素，若插入 HTML <span>
  // 会触发浏览器 foreign-content 规则、破坏 SVG 结构，因此 svg 内文本跳过替换。
  let svgDepth = 0;
  return safe.split(/(<[^>]*>)/g).map(part => {
    if (part.startsWith('<')) {
      if (/^<svg[\s>]/i.test(part)) svgDepth++;
      if (/^<\/svg\s*>/i.test(part) && svgDepth > 0) svgDepth--;
      return part;
    }
    if (svgDepth > 0) return part;
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
