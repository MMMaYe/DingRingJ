/* ============================================================
   Agent 管理页逻辑：列表 / 新建 / 编辑
   ============================================================ */

let agents = [];
let editingId = null; // null=新建，否则为编辑中的 Agent id

window.addEventListener('DOMContentLoaded', loadAgents);

async function loadAgents() {
  try {
    agents = await API.get('/api/agents');
  } catch (e) { toast(e.message, 'error'); return; }
  renderAgents();
}

function renderAgents() {
  const grid = document.getElementById('agentGrid');
  if (!agents.length) {
    grid.innerHTML = '<div class="empty" style="grid-column:1/-1"><div class="empty__icon">🤖</div>还没有 Agent，点击右上角「新建 Agent」创建第一位 AI 同学</div>';
    return;
  }
  grid.innerHTML = agents.map(a => `
    <div class="agent-card">
      <div class="agent-card__head">
        ${avatarHtml(a.name, 'ds-avatar--lg')}
        <div style="min-width:0">
          <div class="agent-card__name">${escapeHtml(a.name)}</div>
          <div class="agent-card__model">${escapeHtml(a.modelName || '')} · ${escapeHtml(shortUrl(a.baseUrl))}</div>
        </div>
      </div>
      <div class="agent-card__desc">${escapeHtml(a.description || '暂无人设描述')}</div>
      <div class="agent-card__prompt">${escapeHtml(a.systemPrompt || '（未配置系统提示词）')}</div>
      <div class="agent-card__footer">
        <span class="agent-card__time">更新于 ${formatTime(a.updateTime || a.createTime)}</span>
        <button class="btn btn--ghost" onclick="openEdit(${a.id})">编辑</button>
      </div>
    </div>`).join('');
}

function shortUrl(url) {
  try { return new URL(url).host; } catch (e) { return url || ''; }
}

/* ================= 新建 / 编辑 ================= */
function openCreate() {
  editingId = null;
  document.getElementById('amTitle').textContent = '新建 Agent';
  document.getElementById('amKeyHint').textContent = '*';
  document.getElementById('amApiKey').placeholder = 'sk-…';
  fillForm({});
  openModal('agentModal');
}

function openEdit(id) {
  const a = agents.find(x => x.id === id);
  if (!a) return;
  editingId = id;
  document.getElementById('amTitle').textContent = `编辑 Agent · ${a.name}`;
  document.getElementById('amKeyHint').textContent = '（留空表示不修改）';
  document.getElementById('amApiKey').placeholder = '留空则保持原 Key 不变';
  fillForm(a);
  openModal('agentModal');
}

function fillForm(a) {
  document.getElementById('amName').value = a.name || '';
  document.getElementById('amModel').value = a.modelName || '';
  document.getElementById('amDesc').value = a.description || '';
  document.getElementById('amBaseUrl').value = a.baseUrl || '';
  document.getElementById('amApiKey').value = '';
  document.getElementById('amPrompt').value = a.systemPrompt || '';
}

async function submitAgent() {
  const name = document.getElementById('amName').value.trim();
  const modelName = document.getElementById('amModel').value.trim();
  const description = document.getElementById('amDesc').value.trim();
  const baseUrl = document.getElementById('amBaseUrl').value.trim();
  const apiKey = document.getElementById('amApiKey').value.trim();
  const systemPrompt = document.getElementById('amPrompt').value.trim();

  if (!name) { toast('请输入花名', 'error'); return; }
  if (!baseUrl) { toast('请输入 Base URL', 'error'); return; }
  if (!modelName) { toast('请输入模型名', 'error'); return; }
  if (editingId === null && !apiKey) { toast('请输入 API Key', 'error'); return; }

  const body = { name, description, baseUrl, modelName, systemPrompt };
  const btn = document.getElementById('amSubmit');
  btn.disabled = true;
  try {
    if (editingId === null) {
      await API.post('/api/agents', { ...body, apiKey });
      toast('Agent 创建成功', 'success');
    } else {
      // apiKey 留空 = 不修改原 Key
      await API.put(`/api/agents/${editingId}`, apiKey ? { ...body, apiKey } : body);
      toast('Agent 已更新', 'success');
    }
    closeModal('agentModal');
    await loadAgents();
  } catch (e) { toast(e.message, 'error'); }
  finally { btn.disabled = false; }
}

/* ================= 弹窗 ================= */
function openModal(id) { document.getElementById(id).classList.add('is-open'); }
function closeModal(id) { document.getElementById(id).classList.remove('is-open'); }
document.querySelectorAll('.modal-mask').forEach(mask => {
  mask.addEventListener('click', e => { if (e.target === mask) mask.classList.remove('is-open'); });
});
