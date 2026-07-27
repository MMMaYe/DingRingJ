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
 */
export function renderMarkdown(content: string, agentNames: string[] = []): string {
  const raw = marked.parse(String(content ?? ''), { async: false }) as string;
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
