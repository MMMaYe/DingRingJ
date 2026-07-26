/* ============================================================
   DingRing 公共工具：REST 封装 / Toast / 头像 / 转义 / 时间
   ============================================================ */

const API = {
  async request(path, options = {}) {
    const resp = await fetch(path, {
      headers: { 'Content-Type': 'application/json' },
      ...options,
    });
    let body = null;
    try { body = await resp.json(); } catch (e) { /* 非 JSON 响应 */ }
    if (!body) throw new Error('网络异常，请稍后重试');
    if (!body.success) {
      const err = new Error(body.message || '请求失败');
      err.errorCode = body.errorCode;
      throw err;
    }
    return body.data;
  },
  get(path) { return this.request(path); },
  post(path, data) { return this.request(path, { method: 'POST', body: data === undefined ? undefined : JSON.stringify(data) }); },
  put(path, data) { return this.request(path, { method: 'PUT', body: JSON.stringify(data) }); },
  del(path) { return this.request(path, { method: 'DELETE' }); },
};

/* ---------- Toast ---------- */
function toast(message, type = 'info', duration = 2600) {
  let wrap = document.querySelector('.toast-wrap');
  if (!wrap) {
    wrap = document.createElement('div');
    wrap.className = 'toast-wrap';
    document.body.appendChild(wrap);
  }
  const el = document.createElement('div');
  el.className = 'toast' + (type === 'error' ? ' toast--error' : type === 'success' ? ' toast--success' : '');
  el.textContent = message;
  wrap.appendChild(el);
  setTimeout(() => el.remove(), duration);
}

/* ---------- 头像 ---------- */
const AVATAR_COLORS = ['#2DD288', '#7BB8FF', '#EC93FF', '#DCB364', '#04CBE5', '#BFA5FF', '#FF9392', '#8ACB3A'];

function avatarColor(name) {
  let hash = 0;
  for (const ch of String(name || '?')) hash = (hash * 31 + ch.codePointAt(0)) >>> 0;
  return AVATAR_COLORS[hash % AVATAR_COLORS.length];
}

/** 生成头像 HTML（取名字首字符，按名字散列配色） */
function avatarHtml(name, extraClass = '') {
  const ch = (name || '?').trim().charAt(0) || '?';
  const color = avatarColor(name);
  return `<div class="ds-avatar ${extraClass}" style="background:${color}22;color:${color}">${escapeHtml(ch)}</div>`;
}

/* ---------- 文本 ---------- */
function escapeHtml(text) {
  return String(text ?? '')
    .replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;').replaceAll("'", '&#39;');
}

/* ---------- 时间 ---------- */
function formatTime(isoString) {
  if (!isoString) return '';
  const d = new Date(isoString);
  if (Number.isNaN(d.getTime())) return '';
  const now = new Date();
  const pad = n => String(n).padStart(2, '0');
  const hm = `${pad(d.getHours())}:${pad(d.getMinutes())}`;
  if (d.toDateString() === now.toDateString()) return hm;
  return `${d.getMonth() + 1}/${d.getDate()} ${hm}`;
}

/* ---------- URL 参数 ---------- */
function queryParam(name) {
  return new URLSearchParams(location.search).get(name);
}
