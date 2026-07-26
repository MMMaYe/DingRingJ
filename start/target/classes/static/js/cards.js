/* ============================================================
   知识卡片页逻辑：分类筛选 / 列表 / 复习模式（翻面卡）
   ============================================================ */

let cards = [];
let categories = [];
let currentCategory = null; // null = 全部

/* 复习模式状态 */
const review = { cards: [], index: 0, active: false };

window.addEventListener('DOMContentLoaded', async () => {
  await Promise.all([loadCategories(), loadCards()]);
});

/* ================= 分类 ================= */
async function loadCategories() {
  try {
    categories = await API.get('/api/cards/categories');
  } catch (e) { toast(e.message, 'error'); return; }
  renderCategories();
}

function renderCategories() {
  const box = document.getElementById('catList');
  const item = (label, value) => `
    <div class="cat-item ${currentCategory === value ? 'cat-item--active' : ''}"
         onclick="selectCategory(${value === null ? 'null' : JSON.stringify(String(value)).replaceAll('"', '&quot;')})">
      <span>${escapeHtml(label)}</span>
    </div>`;
  box.innerHTML = item('全部卡片', null) + categories.map(c => item(c, c)).join('');
}

async function selectCategory(category) {
  currentCategory = category;
  renderCategories();
  await loadCards();
}

/* ================= 卡片列表 ================= */
async function loadCards() {
  try {
    const query = currentCategory ? `?category=${encodeURIComponent(currentCategory)}` : '';
    cards = await API.get(`/api/cards${query}`);
  } catch (e) { toast(e.message, 'error'); return; }
  document.getElementById('pageSubtitle').textContent =
    `${currentCategory ? `分类「${currentCategory}」` : '全部'}共 ${cards.length} 张卡片 · 点击卡片查看答案`;
  renderCards();
}

function renderCards() {
  const grid = document.getElementById('cardGrid');
  if (!cards.length) {
    grid.innerHTML = '<div class="empty" style="grid-column:1/-1"><div class="empty__icon">🗂️</div>暂无知识卡片<br>在群里发起讨论并 @专家 结束后会自动生成</div>';
    return;
  }
  grid.innerHTML = cards.map(c => `
    <div class="k-card" onclick="this.classList.toggle('is-open')">
      <div class="k-card__q">Q: ${escapeHtml(c.question)}</div>
      <div class="k-card__a k-card__a--hidden">A: ${escapeHtml(c.answer)}</div>
      <div class="k-card__footer">
        ${c.category ? `<span class="tag tag--brand">${escapeHtml(c.category)}</span>` : ''}
        <span class="k-card__topic">来自「${escapeHtml(c.topicTitle || '')}」</span>
        <span style="margin-left:auto">${formatTime(c.createTime)}</span>
      </div>
    </div>`).join('');
}

/* ================= 复习模式 ================= */
async function startReview() {
  const order = document.getElementById('orderSelect').value;
  let data;
  try {
    const params = new URLSearchParams();
    if (currentCategory) params.set('category', currentCategory);
    params.set('order', order);
    data = await API.get(`/api/cards/review?${params}`);
  } catch (e) { toast(e.message, 'error'); return; }
  if (!data.cards || !data.cards.length) { toast('当前筛选下没有可复习的卡片', 'error'); return; }
  review.cards = data.cards;
  review.index = 0;
  review.active = true;
  document.getElementById('reviewMask').classList.add('is-open');
  showCard();
}

function showCard() {
  const c = review.cards[review.index];
  // 先复位到正面再填充内容，避免翻面动画残留
  document.getElementById('flipCard').classList.remove('is-flipped');
  document.getElementById('rvQuestion').textContent = c.question;
  document.getElementById('rvAnswer').textContent = c.answer;
  document.getElementById('rvMetaFront').textContent =
    `${c.category ? `#${c.category} · ` : ''}来自「${c.topicTitle || ''}」`;
  document.getElementById('rvProgress').textContent = `第 ${review.index + 1} / ${review.cards.length} 张`;
  document.getElementById('rvPrev').disabled = review.index === 0;
  document.getElementById('rvNext').textContent =
    review.index === review.cards.length - 1 ? '完成 ✓' : '下一张 →';
}

function flipCard() {
  document.getElementById('flipCard').classList.toggle('is-flipped');
}

function prevCard() {
  if (review.index > 0) { review.index--; showCard(); }
}

function nextCard() {
  if (review.index < review.cards.length - 1) {
    review.index++;
    showCard();
  } else {
    exitReview();
    toast(`🎉 本轮复习完成，共 ${review.cards.length} 张卡片`, 'success');
  }
}

function exitReview() {
  review.active = false;
  document.getElementById('reviewMask').classList.remove('is-open');
}

/* 键盘操作：空格翻面 / 左右切换 / Esc 退出 */
document.addEventListener('keydown', e => {
  if (!review.active) return;
  if (e.key === ' ') { e.preventDefault(); flipCard(); }
  else if (e.key === 'ArrowLeft') prevCard();
  else if (e.key === 'ArrowRight') nextCard();
  else if (e.key === 'Escape') exitReview();
});
