/* ============================================================
   群聊页逻辑：群列表 / 消息流 / WebSocket / 讨论生命周期
   ============================================================ */

const state = {
  groups: [],
  group: null,          // 当前群详情 GroupDetail
  activeTopic: null,    // 当前进行中的 TopicSummary
  agents: [],           // 全部 Agent（建群用）
  ws: null,
  wsGroupId: null,
  replyTo: null,        // {id, senderName, content}
  typing: new Map(),    // agentId -> agentName
};

/* ================= 初始化 ================= */
window.addEventListener('DOMContentLoaded', async () => {
  await loadGroups();
  const gid = Number(queryParam('groupId'));
  if (gid && state.groups.some(g => g.id === gid)) {
    selectGroup(gid);
  } else if (state.groups.length > 0) {
    selectGroup(state.groups[0].id);
  }
  bindInput();
});

/* ================= 群列表 ================= */
async function loadGroups() {
  try {
    state.groups = await API.get('/api/groups');
  } catch (e) { toast(e.message, 'error'); return; }
  renderGroupList();
}

function renderGroupList() {
  const box = document.getElementById('groupList');
  if (!state.groups.length) {
    box.innerHTML = '<div class="empty"><div class="empty__icon">👥</div>还没有群，点击下方按钮创建</div>';
    return;
  }
  box.innerHTML = state.groups.map(g => `
    <div class="group-item ${state.group && state.group.id === g.id ? 'group-item--active' : ''}" onclick="selectGroup(${g.id})">
      ${avatarHtml(g.name, 'ds-avatar--sm')}
      <div class="group-item__content">
        <div class="group-item__top">
          <div class="group-item__name-group">
            <span class="group-item__name">${escapeHtml(g.name)}</span>
            ${g.activeTopicTitle ? '<span class="tag tag--brand">讨论中</span>' : ''}
          </div>
          <span class="group-item__time">${formatTime(g.lastMessageTime)}</span>
        </div>
        <span class="group-item__preview">${escapeHtml(g.lastMessagePreview || '暂无消息')}</span>
      </div>
    </div>`).join('');
}

/* ================= 选中群 ================= */
async function selectGroup(groupId) {
  try {
    state.group = await API.get(`/api/groups/${groupId}`);
  } catch (e) { toast(e.message, 'error'); return; }
  state.activeTopic = state.group.activeTopic || null;
  state.replyTo = null;
  state.typing.clear();
  renderGroupList();
  renderHeader();
  renderMembers();
  renderTypingBar();
  cancelReply();
  document.getElementById('chatPlaceholder').style.display = 'none';
  document.getElementById('chatArea').style.display = 'flex';
  document.getElementById('rightPanel').style.display = 'flex';
  await Promise.all([loadMessages(), loadTopics()]);
  connectWs(groupId);
}

function renderHeader() {
  const g = state.group;
  document.getElementById('chatTitle').textContent = g.name;
  document.getElementById('chatMembers').textContent = `(${g.members.length})`;
  const line = document.getElementById('chatTopicLine');
  if (state.activeTopic) {
    line.innerHTML = `当前主题：<em>${escapeHtml(state.activeTopic.title)}</em>`;
    document.getElementById('btnNewTopic').style.display = 'none';
    document.getElementById('btnConclude').style.display = '';
  } else {
    line.textContent = '暂无进行中的讨论，发起一个主题开始学习吧';
    document.getElementById('btnNewTopic').style.display = '';
    document.getElementById('btnConclude').style.display = 'none';
  }
}

function renderMembers() {
  const members = state.group.members;
  document.getElementById('memberCount').textContent = members.length;
  document.getElementById('memberList').innerHTML = members.map(m => `
    <div class="panel-member">
      ${avatarHtml(m.name, 'ds-avatar--sm')}
      <span class="panel-member__name">${escapeHtml(m.name)}</span>
      ${m.role === 'EXPERT' ? '<span class="tag tag--warning">专家</span>'
        : m.type === 'USER' ? '<span class="tag">我</span>'
        : '<span class="tag tag--brand">Agent</span>'}
    </div>`).join('');
}

/* ================= 消息 ================= */
async function loadMessages() {
  const body = document.getElementById('chatBody');
  body.innerHTML = '';
  try {
    const page = await API.get(`/api/groups/${state.group.id}/messages?page=1&pageSize=200`);
    page.items.forEach(m => appendMessage(m, false));
    scrollToBottom();
  } catch (e) { toast(e.message, 'error'); }
}

function appendMessage(m, scroll = true) {
  const body = document.getElementById('chatBody');
  const el = document.createElement('div');
  if (m.senderType === 'SYSTEM') {
    el.className = 'msg-system' + (m.content.includes('【讨论结论】') ? ' msg-system--conclusion' : '');
    el.textContent = m.content;
  } else {
    const self = m.senderType === 'USER';
    el.className = 'msg' + (self ? ' msg--self' : '');
    const expert = isExpert(m.senderId, m.senderType);
    el.innerHTML = `
      ${avatarHtml(m.senderName)}
      <div class="msg__main">
        <div class="msg__meta">
          <span class="msg__sender">${escapeHtml(m.senderName || '')}${expert ? '<span class="tag tag--warning">专家</span>' : ''}</span>
          <span class="msg__time">${formatTime(m.createTime)}</span>
        </div>
        ${m.replyToMessageId ? `<div class="msg__reply">↩ ${escapeHtml(m.replyToSenderName || '')}: ${escapeHtml(m.replyToContent || '')}</div>` : ''}
        <div class="msg__bubble">${highlightMentions(m.content)}</div>
        ${self ? '' : `<div class="msg__actions">
          <button class="msg__action-btn" onclick='startReply(${m.id}, ${JSON.stringify(String(m.senderName || ''))}, ${JSON.stringify(String(m.content).slice(0, 40))})'>引用回复</button>
        </div>`}
      </div>`;
  }
  body.appendChild(el);
  if (scroll) scrollToBottom();
}

function isExpert(senderId, senderType) {
  if (senderType !== 'AGENT' || !state.group) return false;
  return state.group.members.some(m => m.type === 'AGENT' && m.role === 'EXPERT' && m.id === senderId);
}

function highlightMentions(content) {
  let html = escapeHtml(content);
  if (state.group) {
    state.group.members.filter(m => m.type === 'AGENT').forEach(m => {
      html = html.replaceAll('@' + escapeHtml(m.name), `<span class="mention">@${escapeHtml(m.name)}</span>`);
    });
  }
  return html;
}

function scrollToBottom() {
  const body = document.getElementById('chatBody');
  body.scrollTop = body.scrollHeight;
}

/* ================= 主题 ================= */
async function loadTopics() {
  try {
    const topics = await API.get(`/api/groups/${state.group.id}/topics`);
    const box = document.getElementById('topicList');
    if (!topics.length) {
      box.innerHTML = '<div class="empty" style="padding:16px 0">暂无主题讨论</div>';
      return;
    }
    box.innerHTML = topics.map(t => `
      <div class="topic-item" onclick="onTopicClick(${t.id}, '${t.status}')">
        <div class="topic-item__title">${escapeHtml(t.title)}</div>
        <div class="topic-item__meta">
          ${t.status === 'IN_PROGRESS' ? '<span class="tag tag--brand">讨论中</span>'
            : t.status === 'CONCLUDING' ? '<span class="tag tag--warning">总结中</span>'
            : '<span class="tag tag--closed">已结束</span>'}
          <span>${t.messageCount} 条消息</span>
          <span>${formatTime(t.createTime)}</span>
        </div>
      </div>`).join('');
  } catch (e) { toast(e.message, 'error'); }
}

function onTopicClick(topicId, status) {
  if (status === 'CLOSED') showConclusion(topicId);
}

async function showConclusion(topicId) {
  try {
    const [conclusion, cards] = await Promise.all([
      API.get(`/api/topics/${topicId}/conclusion`),
      API.get(`/api/topics/${topicId}/cards`),
    ]);
    document.getElementById('clTitle').textContent = `📌 ${conclusion.title}`;
    document.getElementById('clContent').textContent = conclusion.conclusion;
    document.getElementById('clCards').innerHTML = cards.length
      ? `<div class="panel__title">生成的知识卡片（${cards.length}）</div>` + cards.map(c => `
          <div class="conclusion-card">
            <div class="conclusion-card__q">Q: ${escapeHtml(c.question)}</div>
            <div class="conclusion-card__a">A: ${escapeHtml(c.answer)}</div>
          </div>`).join('')
      : '<div class="empty" style="padding:10px 0">知识卡片生成中或暂无卡片</div>';
    openModal('conclusionModal');
  } catch (e) { toast(e.message, 'error'); }
}

function openCreateTopic() {
  document.getElementById('ctTitle').value = '';
  openModal('createTopicModal');
}

async function submitCreateTopic() {
  const title = document.getElementById('ctTitle').value.trim();
  if (!title) { toast('请输入讨论主题', 'error'); return; }
  try {
    await API.post(`/api/groups/${state.group.id}/topics`, { title });
    closeModal('createTopicModal');
  } catch (e) { toast(e.message, 'error'); }
}

async function concludeActiveTopic() {
  if (!state.activeTopic) return;
  try {
    await API.post(`/api/topics/${state.activeTopic.id}/conclude`);
    toast('已发起结束讨论，专家正在总结…', 'success');
  } catch (e) { toast(e.message, 'error'); }
}

/* ================= WebSocket ================= */
function connectWs(groupId) {
  if (state.ws && state.wsGroupId === groupId && state.ws.readyState === WebSocket.OPEN) return;
  if (state.ws) { try { state.ws.close(); } catch (e) { /* ignore */ } }
  state.wsGroupId = groupId;
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  const ws = new WebSocket(`${proto}://${location.host}/ws/chat?groupId=${groupId}`);
  state.ws = ws;
  ws.onmessage = ev => {
    let msg;
    try { msg = JSON.parse(ev.data); } catch (e) { return; }
    handleWsMessage(msg.type, msg.data || {});
  };
  ws.onclose = () => {
    // 仍停留在该群则 2s 后重连
    if (state.wsGroupId === groupId && state.group && state.group.id === groupId) {
      setTimeout(() => { if (state.group && state.group.id === groupId) connectWs(groupId); }, 2000);
    }
  };
}

function handleWsMessage(type, data) {
  switch (type) {
    case 'NEW_MESSAGE':
      appendMessage(data);
      refreshGroupPreview(data);
      break;
    case 'AGENT_TYPING':
      if (data.isTyping) state.typing.set(data.agentId, data.agentName);
      else state.typing.delete(data.agentId);
      renderTypingBar();
      break;
    case 'TOPIC_CREATED':
      state.activeTopic = { id: data.topicId, title: data.title, status: data.status };
      renderHeader();
      loadTopics();
      toast(`讨论开始：${data.title}`, 'success');
      break;
    case 'TOPIC_STATUS_CHANGED':
      if (state.activeTopic && state.activeTopic.id === data.topicId) {
        state.activeTopic.status = data.status;
        if (data.status === 'CONCLUDING') {
          document.getElementById('btnConclude').disabled = true;
          document.getElementById('chatTopicLine').innerHTML =
            `当前主题：<em>${escapeHtml(data.title)}</em>（专家总结中…）`;
        }
        if (data.status === 'IN_PROGRESS') { // 结论失败回退
          document.getElementById('btnConclude').disabled = false;
          renderHeader();
        }
      }
      loadTopics();
      break;
    case 'TOPIC_CLOSED':
      state.activeTopic = null;
      document.getElementById('btnConclude').disabled = false;
      renderHeader();
      loadTopics();
      loadGroups();
      toast(`讨论「${data.title}」已结束，结论已生成`, 'success');
      break;
    case 'CARD_GENERATED':
      toast(`✨ 已生成 ${data.cardCount} 张知识卡片，可前往「知识卡片」页复习`, 'success', 4200);
      break;
    case 'ERROR':
      toast(data.message || '服务异常', 'error');
      break;
    default:
  }
}

function renderTypingBar() {
  const bar = document.getElementById('typingBar');
  if (!state.typing.size) { bar.innerHTML = ''; return; }
  const names = [...state.typing.values()].join('、');
  bar.innerHTML = `${escapeHtml(names)} 正在输入<span class="dotting"></span>`;
}

function refreshGroupPreview(m) {
  const g = state.groups.find(x => x.id === state.wsGroupId);
  if (g) {
    g.lastMessagePreview = `${m.senderName ? m.senderName + ': ' : ''}${String(m.content).slice(0, 30)}`;
    g.lastMessageTime = m.createTime;
    renderGroupList();
  }
}

/* ================= 发送 ================= */
function bindInput() {
  const input = document.getElementById('msgInput');
  input.addEventListener('keydown', e => {
    if (e.key === 'Enter' && !e.shiftKey && !isMentionOpen()) {
      e.preventDefault();
      sendMessage();
    }
  });
  input.addEventListener('input', () => {
    input.style.height = 'auto';
    input.style.height = Math.min(input.scrollHeight, 132) + 'px';
    maybeShowMention(input);
  });
}

function sendMessage() {
  const input = document.getElementById('msgInput');
  const content = input.value.trim();
  if (!content || !state.ws || state.ws.readyState !== WebSocket.OPEN) return;
  const payload = state.replyTo
    ? { type: 'REPLY_MESSAGE', data: { groupId: state.wsGroupId, content, replyToMessageId: state.replyTo.id } }
    : { type: 'SEND_MESSAGE', data: { groupId: state.wsGroupId, content } };
  state.ws.send(JSON.stringify(payload));
  input.value = '';
  input.style.height = 'auto';
  cancelReply();
  hideMention();
}

/* ================= 引用回复 ================= */
function startReply(id, senderName, content) {
  state.replyTo = { id, senderName, content };
  document.getElementById('replyBarText').textContent = `回复 ${senderName}: ${content}`;
  document.getElementById('replyBar').classList.add('is-active');
  document.getElementById('msgInput').focus();
}

function cancelReply() {
  state.replyTo = null;
  document.getElementById('replyBar').classList.remove('is-active');
}

/* ================= @提及 ================= */
function maybeShowMention(input) {
  const pos = input.selectionStart;
  const before = input.value.slice(0, pos);
  const at = before.lastIndexOf('@');
  if (at < 0 || /\s/.test(before.slice(at + 1))) { hideMention(); return; }
  const keyword = before.slice(at + 1).toLowerCase();
  const candidates = (state.group ? state.group.members : [])
    .filter(m => m.type === 'AGENT' && m.name.toLowerCase().includes(keyword));
  if (!candidates.length) { hideMention(); return; }
  const pop = document.getElementById('mentionPop');
  pop.innerHTML = candidates.map(m => `
    <div class="mention-pop__item" onmousedown="pickMention(event, ${JSON.stringify(String(m.name)).replaceAll('"', '&quot;')})">
      ${avatarHtml(m.name, 'ds-avatar--sm')}
      <span>${escapeHtml(m.name)}</span>
      <span class="mention-pop__role">${m.role === 'EXPERT' ? '<span class="tag tag--warning">专家</span>' : '<span class="tag">成员</span>'}</span>
    </div>`).join('');
  pop.classList.add('is-open');
}

function pickMention(event, name) {
  event.preventDefault();
  const input = document.getElementById('msgInput');
  const pos = input.selectionStart;
  const before = input.value.slice(0, pos);
  const at = before.lastIndexOf('@');
  input.value = before.slice(0, at) + '@' + name + ' ' + input.value.slice(pos);
  hideMention();
  input.focus();
}

function isMentionOpen() { return document.getElementById('mentionPop').classList.contains('is-open'); }
function hideMention() { document.getElementById('mentionPop').classList.remove('is-open'); }
document.addEventListener('click', e => {
  if (!e.target.closest('.chat-input__box')) hideMention();
});

/* ================= 新建群 ================= */
async function openCreateGroup() {
  try {
    state.agents = await API.get('/api/agents');
  } catch (e) { toast(e.message, 'error'); return; }
  if (!state.agents.length) { toast('请先到「Agent 管理」创建 Agent', 'error'); return; }
  document.getElementById('cgName').value = '';
  document.getElementById('cgAgentList').innerHTML = state.agents.map(a => `
    <div class="member-pick" data-id="${a.id}" onclick="this.classList.toggle('is-checked')">
      ${avatarHtml(a.name, 'ds-avatar--sm')}
      <div class="member-pick__meta">
        <div class="member-pick__name">${escapeHtml(a.name)}</div>
        <div class="member-pick__desc">${escapeHtml(a.description || a.modelName || '')}</div>
      </div>
    </div>`).join('');
  document.getElementById('cgExpert').innerHTML =
    state.agents.map(a => `<option value="${a.id}">${escapeHtml(a.name)}</option>`).join('');
  // 默认选最后一个为专家
  document.getElementById('cgExpert').value = state.agents[state.agents.length - 1].id;
  openModal('createGroupModal');
}

async function submitCreateGroup() {
  const name = document.getElementById('cgName').value.trim();
  const agentIds = [...document.querySelectorAll('#cgAgentList .member-pick.is-checked')]
    .map(el => Number(el.dataset.id));
  const expertAgentId = Number(document.getElementById('cgExpert').value);
  if (!name) { toast('请输入群名称', 'error'); return; }
  if (!agentIds.length) { toast('请至少选择一个成员 Agent', 'error'); return; }
  if (agentIds.includes(expertAgentId)) { toast('专家不能同时是普通成员', 'error'); return; }
  try {
    const detail = await API.post('/api/groups', { name, agentIds, expertAgentId });
    closeModal('createGroupModal');
    toast('群创建成功', 'success');
    await loadGroups();
    selectGroup(detail.id);
  } catch (e) { toast(e.message, 'error'); }
}

/* ================= 弹窗 ================= */
function openModal(id) { document.getElementById(id).classList.add('is-open'); }
function closeModal(id) { document.getElementById(id).classList.remove('is-open'); }
document.querySelectorAll('.modal-mask').forEach(mask => {
  mask.addEventListener('click', e => { if (e.target === mask) mask.classList.remove('is-open'); });
});
