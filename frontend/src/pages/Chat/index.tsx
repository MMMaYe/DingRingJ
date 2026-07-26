import { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';
import Sidebar, { IconPlus } from '../../components/Sidebar';
import Avatar from '../../components/Avatar';
import Modal from '../../components/Modal';
import { toast } from '../../components/Toast';
import useWebSocket from '../../hooks/useWebSocket';
import { API } from '../../api';
import type {
  GroupSummary, GroupDetail, TopicSummary, MemberInfo,
  MessageDTO, AgentDTO, ConclusionDTO, KnowledgeCardDTO, PageResult,
} from '../../types';
import './style.css';

export default function ChatPage() {
  // ---- state ----
  const [groups, setGroups] = useState<GroupSummary[]>([]);
  const [group, setGroup] = useState<GroupDetail | null>(null);
  const [activeTopic, setActiveTopic] = useState<TopicSummary | null>(null);
  const [agents, setAgents] = useState<AgentDTO[]>([]);
  const [messages, setMessages] = useState<MessageDTO[]>([]);
  const [topics, setTopics] = useState<TopicSummary[]>([]);
  const [replyTo, setReplyTo] = useState<{ id: number; senderName: string; content: string } | null>(null);
  const [typing, setTyping] = useState<Map<number, string>>(new Map());
  const [inputText, setInputText] = useState('');
  const [mentionState, setMentionState] = useState<{ open: boolean; keyword: string; candidates: MemberInfo[] }>({ open: false, keyword: '', candidates: [] });
  const [searchParams, setSearchParams] = useSearchParams();

  // 聊天内搜索
  const [searchOpen, setSearchOpen] = useState(false);
  const [searchKw, setSearchKw] = useState('');
  const [searchIdx, setSearchIdx] = useState(0);
  const searchInputRef = useRef<HTMLInputElement>(null);
  // 群列表搜索
  const [groupKw, setGroupKw] = useState('');

  // modals
  const [showCreateGroup, setShowCreateGroup] = useState(false);
  const [showCreateTopic, setShowCreateTopic] = useState(false);
  const [showConclusion, setShowConclusion] = useState<ConclusionDTO | null>(null);
  const [conclusionCards, setConclusionCards] = useState<KnowledgeCardDTO[]>([]);

  // create group form
  const [cgName, setCgName] = useState('');
  const [cgSelectedAgents, setCgSelectedAgents] = useState<Set<number>>(new Set());
  const [cgExpert, setCgExpert] = useState<number>(0);
  // create topic form
  const [ctTitle, setCtTitle] = useState('');

  const chatBodyRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);

  const groupId = group?.id ?? null;
  const { send, onMessage } = useWebSocket(groupId);

  // 群列表过滤
  const filteredGroups = useMemo(() => {
    const kw = groupKw.trim().toLowerCase();
    if (!kw) return groups;
    return groups.filter(g => g.name.toLowerCase().includes(kw));
  }, [groups, groupKw]);

  // ---- 搜索匹配 ----
  const searchMatches = useMemo(() => {
    const kw = searchKw.trim().toLowerCase();
    if (!kw) return [] as number[];
    return messages
      .filter(m => m.senderType !== 'SYSTEM')
      .filter(m => m.content.toLowerCase().includes(kw) || m.senderName.toLowerCase().includes(kw))
      .map(m => m.id);
  }, [messages, searchKw]);

  // 滚动到当前匹配项
  useEffect(() => {
    if (!searchMatches.length) return;
    const matchId = searchMatches[Math.min(searchIdx, searchMatches.length - 1)];
    const el = chatBodyRef.current?.querySelector(`[data-msg-id="${matchId}"]`);
    if (el) el.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }, [searchIdx, searchMatches]);

  const toggleSearch = useCallback(() => {
    setSearchOpen(prev => {
      const next = !prev;
      if (next) setTimeout(() => searchInputRef.current?.focus(), 50);
      else { setSearchKw(''); setSearchIdx(0); }
      return next;
    });
  }, []);

  const navSearch = useCallback((dir: 1 | -1) => {
    if (!searchMatches.length) return;
    setSearchIdx(i => (i + dir + searchMatches.length) % searchMatches.length);
  }, [searchMatches.length]);

  // Ctrl+F 打开搜索
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 'f' && group) {
        e.preventDefault();
        setSearchOpen(true);
        setTimeout(() => searchInputRef.current?.focus(), 50);
      }
      if (e.key === 'Escape' && searchOpen) setSearchOpen(false);
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [group, searchOpen]);

  // ---- 加载群列表 ----
  const loadGroups = useCallback(async () => {
    try { setGroups(await API.get<GroupSummary[]>('/api/groups')); } catch (e: any) { toast(e.message, 'error'); }
  }, []);

  useEffect(() => { loadGroups(); }, [loadGroups]);

  // ---- 自动选群 ----
  useEffect(() => {
    if (group || !groups.length) return;
    const gid = Number(searchParams.get('groupId'));
    const target = gid && groups.find(g => g.id === gid) ? gid : groups[0].id;
    selectGroup(target);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [groups]);

  // ---- 选群 ----
  const selectGroup = useCallback(async (groupId: number) => {
    try {
      const detail = await API.get<GroupDetail>(`/api/groups/${groupId}`);
      setGroup(detail);
      setActiveTopic(detail.activeTopic ?? null);
      setReplyTo(null);
      setTyping(new Map());
      setSearchParams({ groupId: String(groupId) }, { replace: true });
    } catch (e: any) { toast(e.message, 'error'); }
  }, [setSearchParams]);

  // ---- 加载消息 ----
  useEffect(() => {
    if (!group) return;
    (async () => {
      try {
        const page = await API.get<PageResult<MessageDTO>>(`/api/groups/${group.id}/messages?page=1&pageSize=200`);
        setMessages(page.items);
      } catch (e: any) { toast(e.message, 'error'); }
    })();
  }, [group]);

  // ---- 加载主题 ----
  useEffect(() => {
    if (!group) return;
    (async () => {
      try { setTopics(await API.get<TopicSummary[]>(`/api/groups/${group.id}/topics`)); } catch { /* ignore */ }
    })();
  }, [group]);

  // ---- 滚动到底 ----
  useEffect(() => {
    const el = chatBodyRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [messages]);

  // ---- WebSocket 消息处理 ----
  useEffect(() => {
    onMessage((msg) => {
      const d = msg.data;
      switch (msg.type) {
        case 'NEW_MESSAGE':
          setMessages(prev => [...prev, d as unknown as MessageDTO]);
          // 更新群列表预览
          setGroups(prev => prev.map(g =>
            g.id === groupId
              ? { ...g, lastMessagePreview: `${(d as any).senderName || ''}: ${String((d as any).content).slice(0, 30)}`, lastMessageTime: (d as any).createTime }
              : g
          ));
          break;
        case 'AGENT_TYPING':
          setTyping(prev => {
            const next = new Map(prev);
            if ((d as any).isTyping) next.set((d as any).agentId, (d as any).agentName);
            else next.delete((d as any).agentId);
            return next;
          });
          break;
        case 'TOPIC_CREATED':
          setActiveTopic({ id: (d as any).topicId, title: (d as any).title, status: (d as any).status, messageCount: 0, createTime: '' });
          loadTopics();
          toast(`讨论开始：${(d as any).title}`, 'success');
          break;
        case 'TOPIC_STATUS_CHANGED':
          if (activeTopic && activeTopic.id === (d as any).topicId) {
            setActiveTopic(prev => prev ? { ...prev, status: (d as any).status } : prev);
          }
          loadTopics();
          break;
        case 'TOPIC_CLOSED':
          setActiveTopic(null);
          loadTopics();
          loadGroups();
          toast(`讨论「${(d as any).title}」已结束，结论已生成`, 'success');
          break;
        case 'CARD_GENERATED':
          toast(`✨ 已生成 ${(d as any).cardCount} 张知识卡片`, 'success');
          break;
        case 'ERROR':
          toast((d as any).message || '服务异常', 'error');
          break;
      }
    });
  }, [onMessage, groupId, activeTopic, loadGroups]);

  const loadTopics = useCallback(async () => {
    if (!group) return;
    try { setTopics(await API.get<TopicSummary[]>(`/api/groups/${group.id}/topics`)); } catch { /* ignore */ }
  }, [group]);

  // ---- 发消息 ----
  const sendMessage = useCallback(() => {
    const content = inputText.trim();
    if (!content || !group) return;
    const payload = replyTo
      ? { type: 'REPLY_MESSAGE', data: { groupId: group.id, content, replyToMessageId: replyTo.id } }
      : { type: 'SEND_MESSAGE', data: { groupId: group.id, content } };
    send(payload);
    setInputText('');
    setReplyTo(null);
    hideMention();
  }, [inputText, group, replyTo, send]);

  // ---- @mention ----
  const maybeShowMention = useCallback((text: string, cursorPos: number) => {
    if (!group) return;
    const before = text.slice(0, cursorPos);
    const at = before.lastIndexOf('@');
    if (at < 0 || /\s/.test(before.slice(at + 1))) { hideMention(); return; }
    const keyword = before.slice(at + 1).toLowerCase();
    const candidates = group.members.filter(m => m.type === 'AGENT' && m.name.toLowerCase().includes(keyword));
    if (!candidates.length) { hideMention(); return; }
    setMentionState({ open: true, keyword, candidates });
  }, [group]);

  const hideMention = useCallback(() => setMentionState(s => ({ ...s, open: false })), []);

  const pickMention = useCallback((name: string) => {
    const el = inputRef.current;
    if (!el) return;
    const pos = el.selectionStart ?? inputText.length;
    const before = inputText.slice(0, pos);
    const at = before.lastIndexOf('@');
    setInputText(before.slice(0, at) + '@' + name + ' ' + inputText.slice(pos));
    hideMention();
    setTimeout(() => el.focus(), 0);
  }, [inputText, hideMention]);

  // ---- 键盘 ----
  const handleKeyDown = useCallback((e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey && !mentionState.open) {
      e.preventDefault();
      sendMessage();
    }
  }, [sendMessage, mentionState.open]);

  // ---- 查看结论 ----
  const viewConclusion = useCallback(async (topicId: number) => {
    try {
      const [conclusion, cards] = await Promise.all([
        API.get<ConclusionDTO>(`/api/topics/${topicId}/conclusion`),
        API.get<KnowledgeCardDTO[]>(`/api/topics/${topicId}/cards`),
      ]);
      setShowConclusion(conclusion);
      setConclusionCards(cards);
    } catch (e: any) { toast(e.message, 'error'); }
  }, []);

  // ---- 结束讨论 ----
  const concludeTopic = useCallback(async () => {
    if (!activeTopic) return;
    try {
      await API.post(`/api/topics/${activeTopic.id}/conclude`);
      toast('已发起结束讨论，专家正在总结…', 'success');
    } catch (e: any) { toast(e.message, 'error'); }
  }, [activeTopic]);

  const restartTopic = useCallback(async () => {
    if (!activeTopic) return;
    try {
      await API.post(`/api/topics/${activeTopic.id}/restart`);
      toast('讨论已重新开始', 'success');
    } catch (e: any) { toast(e.message, 'error'); }
  }, [activeTopic]);

  // ---- 新建群 ----
  const openCreateGroup = useCallback(async () => {
    try {
      const list = await API.get<AgentDTO[]>('/api/agents');
      if (!list.length) { toast('请先到「Agent 管理」创建 Agent', 'error'); return; }
      setAgents(list);
      setCgName('');
      setCgSelectedAgents(new Set());
      setCgExpert(list[list.length - 1].id);
      setShowCreateGroup(true);
    } catch (e: any) { toast(e.message, 'error'); }
  }, []);

  const submitCreateGroup = useCallback(async () => {
    const agentIds = [...cgSelectedAgents];
    if (!cgName.trim()) { toast('请输入群名称', 'error'); return; }
    if (!agentIds.length) { toast('请至少选择一个成员 Agent', 'error'); return; }
    if (agentIds.includes(cgExpert)) { toast('专家不能同时是普通成员', 'error'); return; }
    try {
      const detail = await API.post<GroupDetail>('/api/groups', { name: cgName.trim(), agentIds, expertAgentId: cgExpert });
      setShowCreateGroup(false);
      toast('群创建成功', 'success');
      await loadGroups();
      selectGroup(detail.id);
    } catch (e: any) { toast(e.message, 'error'); }
  }, [cgName, cgSelectedAgents, cgExpert, loadGroups, selectGroup]);

  // ---- 发起讨论 ----
  const submitCreateTopic = useCallback(async () => {
    if (!group || !ctTitle.trim()) { toast('请输入讨论主题', 'error'); return; }
    try {
      await API.post(`/api/groups/${group.id}/topics`, { title: ctTitle.trim() });
      setShowCreateTopic(false);
    } catch (e: any) { toast(e.message, 'error'); }
  }, [group, ctTitle]);

  // ---- 辅助 ----
  function isExpert(senderId: number, senderType: string) {
    if (senderType !== 'AGENT' || !group) return false;
    return group.members.some(m => m.type === 'AGENT' && m.role === 'EXPERT' && m.id === senderId);
  }

  function highlightMentions(content: string) {
    let html = escapeHtml(content);
    if (group) {
      group.members.filter(m => m.type === 'AGENT').forEach(m => {
        html = html.replaceAll('@' + escapeHtml(m.name), `<span class="mention">@${escapeHtml(m.name)}</span>`);
      });
    }
    return html;
  }

  function escapeHtml(t: string) {
    return String(t ?? '').replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;').replaceAll("'", '&#39;');
  }

  function formatTime(iso: string | null) {
    if (!iso) return '';
    const d = new Date(iso);
    if (isNaN(d.getTime())) return '';
    const now = new Date();
    const pad = (n: number) => String(n).padStart(2, '0');
    const hm = `${pad(d.getHours())}:${pad(d.getMinutes())}`;
    if (d.toDateString() === now.toDateString()) return hm;
    return `${d.getMonth() + 1}/${d.getDate()} ${hm}`;
  }

  // ---- Render ----
  return (
    <div className="app-shell">
      {/* 左侧：导航 + 群列表 */}
      <Sidebar showGroupLabel onSearch={setGroupKw} footer={<button className="sidebar__new-group" onClick={openCreateGroup}><IconPlus /> 新建群组</button>}>
        <div className="group-list">
          {!groups.length ? (
            <div className="empty"><div className="empty__icon">👥</div>还没有群，点击下方按钮创建</div>
          ) : !filteredGroups.length ? (
            <div className="empty"><div className="empty__icon">🔍</div>没有匹配「{groupKw}」的群组</div>
          ) : filteredGroups.map(g => (
            <div key={g.id} className={`group-item${group?.id === g.id ? ' group-item--active' : ''}`} onClick={() => selectGroup(g.id)}>
              <Avatar name={g.name} size="sm" />
              <div className="group-item__content">
                <div className="group-item__top">
                  <div className="group-item__name-group">
                    <span className="group-item__name">{g.name}</span>
                    {g.activeTopicTitle && <span className="tag tag--brand">讨论中</span>}
                  </div>
                  <span className="group-item__time">{formatTime(g.lastMessageTime)}</span>
                </div>
                <span className="group-item__preview">{g.lastMessagePreview || '暂无消息'}</span>
              </div>
            </div>
          ))}
        </div>
      </Sidebar>

      {/* 中间：聊天窗口 */}
      <section className="chat-window">
        {!group ? (
          <div className="chat-placeholder">
            <div className="chat-placeholder__logo">D</div>
            <div>选择或创建一个群，开始与 AI 同学们讨论吧</div>
          </div>
        ) : (
          <>
            <header className="chat-header">
              <div className="chat-header__left">
                <div className="chat-header__name-row">
                  <span className="chat-header__title">{group.name}</span>
                  <span className="chat-header__members">({group.members.length})</span>
                </div>
                <span className="chat-header__topic">
                  {activeTopic
                    ? <>当前主题：<em>{activeTopic.title}</em></>
                    : '暂无进行中的讨论，发起一个主题开始学习吧'}
                </span>
              </div>
              <div className="chat-header__right">
                <button className="chat-header__icon-btn" title="搜索消息 (Ctrl+F)" onClick={toggleSearch}>
                  <svg width="16" height="16" viewBox="0 0 16 16" fill="none"><circle cx="7" cy="7" r="4.5" stroke="currentColor" strokeWidth="1.2"/><path d="M10.5 10.5L14 14" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round"/></svg>
                </button>
              </div>
            </header>

            {/* 聊天内搜索栏 */}
            {searchOpen && (
              <div className="chat-search">
                <div className="chat-search__input-wrap">
                  <svg className="chat-search__icon" width="14" height="14" viewBox="0 0 14 14" fill="none"><circle cx="6" cy="6" r="4" stroke="currentColor" strokeWidth="1.2"/><path d="M9.5 9.5L12.5 12.5" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round"/></svg>
                  <input
                    ref={searchInputRef}
                    type="text"
                    className="chat-search__input"
                    placeholder="搜索消息内容或发送人..."
                    value={searchKw}
                    onChange={e => { setSearchKw(e.target.value); setSearchIdx(0); }}
                    onKeyDown={e => {
                      if (e.key === 'Enter') { e.preventDefault(); navSearch(e.shiftKey ? -1 : 1); }
                    }}
                  />
                </div>
                <span className="chat-search__count">
                  {searchKw.trim() ? (searchMatches.length ? `${Math.min(searchIdx + 1, searchMatches.length)}/${searchMatches.length}` : '0/0') : ''}
                </span>
                <div className="chat-search__nav">
                  <button className="chat-search__nav-btn" title="上一个" disabled={!searchMatches.length} onClick={() => navSearch(-1)}>
                    <svg width="14" height="14" viewBox="0 0 14 14" fill="none"><path d="M7 3v8M3 7l4-4 4 4" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" strokeLinejoin="round"/></svg>
                  </button>
                  <button className="chat-search__nav-btn" title="下一个" disabled={!searchMatches.length} onClick={() => navSearch(1)}>
                    <svg width="14" height="14" viewBox="0 0 14 14" fill="none"><path d="M7 11V3M3 7l4 4 4-4" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" strokeLinejoin="round"/></svg>
                  </button>
                </div>
                <button className="chat-search__close" title="关闭 (Esc)" onClick={toggleSearch}>
                  <svg width="14" height="14" viewBox="0 0 14 14" fill="none"><path d="M3 3l8 8M11 3l-8 8" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round"/></svg>
                </button>
              </div>
            )}

            <div className="chat-body" ref={chatBodyRef}>
              {messages.map(m => {
                if (m.senderType === 'SYSTEM') {
                  return (
                    <div key={m.id} className={`msg-system${m.content.includes('【讨论结论】') ? ' msg-system--conclusion' : ''}`}>
                      {m.content}
                    </div>
                  );
                }
                const self = m.senderType === 'USER';
                const expert = isExpert(m.senderId, m.senderType);
                const isMatch = searchMatches.includes(m.id);
                const isCurrent = searchMatches.length > 0 && searchMatches[Math.min(searchIdx, searchMatches.length - 1)] === m.id;
                return (
                  <div key={m.id} data-msg-id={m.id} className={`msg${self ? ' msg--self' : ''}${isCurrent ? ' msg--search-current' : isMatch ? ' msg--search-match' : ''}`}>
                    <Avatar name={m.senderName} />
                    <div className="msg__main">
                      <div className="msg__meta">
                        <span className="msg__sender">
                          {m.senderName}
                          {expert && <span className="tag tag--warning">专家</span>}
                        </span>
                        <span className="msg__time">{formatTime(m.createTime)}</span>
                      </div>
                      {m.replyToMessageId && (
                        <div className="msg__reply">↩ {m.replyToSenderName}: {m.replyToContent}</div>
                      )}
                      <div className="msg__bubble" dangerouslySetInnerHTML={{ __html: highlightMentions(m.content) }} />
                      {!self && (
                        <div className="msg__actions">
                          <button className="msg__action-btn" onClick={() => setReplyTo({ id: m.id, senderName: m.senderName, content: m.content.slice(0, 40) })}>引用回复</button>
                        </div>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>

            <div className="typing-bar">
              {typing.size > 0 && <>{[...typing.values()].join('、')} 正在输入<span className="dotting" /></>}
            </div>

            <div className="chat-input">
              {replyTo && (
                <div className="chat-input__replying is-active">
                  <span>回复 {replyTo.senderName}: {replyTo.content}</span>
                  <button onClick={() => setReplyTo(null)}>✕</button>
                </div>
              )}
              <div className="chat-input__row">
                <div className="chat-input__box">
                  {mentionState.open && (
                    <div className="mention-pop is-open">
                      {mentionState.candidates.map(m => (
                        <div key={m.id} className="mention-pop__item" onMouseDown={e => { e.preventDefault(); pickMention(m.name); }}>
                          <Avatar name={m.name} size="sm" />
                          <span>{m.name}</span>
                          <span className="mention-pop__role">
                            {m.role === 'EXPERT' ? <span className="tag tag--warning">专家</span> : <span className="tag">成员</span>}
                          </span>
                        </div>
                      ))}
                    </div>
                  )}
                  <textarea
                    ref={inputRef}
                    className="input"
                    rows={1}
                    value={inputText}
                    placeholder="输入消息，@ 可提及 Agent，@专家 结束讨论并生成结论…"
                    onKeyDown={handleKeyDown}
                    onChange={e => {
                      setInputText(e.target.value);
                      e.target.style.height = 'auto';
                      e.target.style.height = Math.min(e.target.scrollHeight, 132) + 'px';
                      maybeShowMention(e.target.value, e.target.selectionStart ?? e.target.value.length);
                    }}
                    onClick={e => { if (!e.currentTarget.value.includes('@')) hideMention(); }}
                  />
                </div>
                <button className="btn btn--brand" onClick={sendMessage}>发送</button>
              </div>
              <div className="chat-input__hint">Enter 发送 · Shift+Enter 换行 · @专家花名 触发总结陈词</div>
            </div>
          </>
        )}
      </section>

      {/* 右侧：信息面板（对齐设计稿） */}
      {group && (
        <aside className="info-panel">
          {/* 群成员 */}
          <div className="info-panel__section">
            <div className="info-panel__heading">群成员</div>
            <div className="member-list">
              {group.members.map(m => (
                <div key={`${m.type}-${m.id}`} className="member-item">
                  <Avatar name={m.name} size="sm" />
                  <div className="member-item__info">
                    <span className="member-item__name">{m.name}</span>
                    <div className="member-item__row">
                      {m.role === 'EXPERT' ? <span className="tag tag--warning">专家</span>
                        : m.type === 'USER' ? <span className="tag tag--brand">群主</span>
                        : <span className="tag tag--neutral">成员</span>}
                    </div>
                  </div>
                </div>
              ))}
            </div>
          </div>

          {/* 当前主题 */}
          <div className="info-panel__section">
            <div className="info-panel__heading">当前主题</div>
            {activeTopic ? (
              <>
                <div className="topic-panel__title">{activeTopic.title}</div>
                <div className="topic-panel__status-row">
                  {activeTopic.status === 'CONCLUDING'
                    ? <span className="tag tag--neutral">已结束</span>
                    : <span className="tag tag--success">讨论中</span>}
                  <span className="topic-panel__round">
                    {activeTopic.status === 'CONCLUDING' ? '已结束' : `轮次 ${activeTopic.messageCount}/20`}
                  </span>
                </div>
                <div className="topic-panel__actions">
                  {activeTopic.status === 'CONCLUDING' ? (
                    <button className="topic-panel__end-btn" onClick={restartTopic}>
                      <svg width="14" height="14" viewBox="0 0 16 16" fill="currentColor"><polygon points="4,2 14,8 4,14"/></svg>
                      重新开始
                    </button>
                  ) : (
                    <button className="topic-panel__end-btn" onClick={concludeTopic}>
                      <svg width="14" height="14" viewBox="0 0 16 16" fill="currentColor"><rect x="2" y="2" width="12" height="12" rx="2"/></svg>
                      结束讨论
                    </button>
                  )}
                </div>
              </>
            ) : (
              <div className="topic-panel__empty">
                <span className="topic-panel__empty-text">暂无进行中的讨论</span>
                <button className="topic-panel__start-btn" onClick={() => setShowCreateTopic(true)}>
                  <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5"><line x1="8" y1="3" x2="8" y2="13"/><line x1="3" y1="8" x2="13" y2="8"/></svg>
                  发起讨论
                </button>
              </div>
            )}
          </div>

          {/* 历史主题 */}
          {topics.filter(t => t.status === 'CLOSED').length > 0 && (
            <div className="info-panel__section">
              <div className="info-panel__heading">历史主题</div>
              <div className="history-list">
                {topics.filter(t => t.status === 'CLOSED').map(t => (
                  <div key={t.id} className="history-item history-item--clickable" onClick={() => viewConclusion(t.id)}>
                    <span className="history-item__name">{t.title}</span>
                    <span className="tag tag--neutral">已关闭</span>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* 知识卡片管理 */}
          <div className="info-panel__section">
            <div className="info-panel__heading">知识卡片管理</div>
            <div className="history-list">
              {topics.filter(t => t.status === 'CLOSED').map(t => (
                <div key={t.id} className="history-item">
                  <span className="history-item__name">{t.title}</span>
                  <span className="tag tag--success">已收录</span>
                </div>
              ))}
              {!topics.filter(t => t.status === 'CLOSED').length && (
                <div className="history-item">
                  <span className="history-item__name" style={{ color: 'var(--text-tertiary)' }}>暂无知识卡片</span>
                </div>
              )}
            </div>
          </div>

          {/* 知识库管理 */}
          <div className="info-panel__section">
            <div className="info-panel__heading">知识库管理</div>
            <div className="member-list">
              <div className="member-item">
                <Avatar name="知识库" size="sm" />
                <div className="member-item__info">
                  <span className="member-item__name">主知识库</span>
                  <div className="member-item__row">
                    <span className="tag tag--neutral">—</span>
                    <span className="tag tag--success">已连接</span>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </aside>
      )}

      {/* 弹窗：新建群 */}
      <Modal open={showCreateGroup} onClose={() => setShowCreateGroup(false)} title="新建群聊"
        footer={<>
          <button className="btn btn--ghost" onClick={() => setShowCreateGroup(false)}>取消</button>
          <button className="btn btn--brand" onClick={submitCreateGroup}>创建</button>
        </>}>
        <div className="form-row">
          <label>群名称</label>
          <input className="input" value={cgName} onChange={e => setCgName(e.target.value)} placeholder="如：Java 并发学习群" maxLength={64} />
        </div>
        <div className="form-row">
          <label>选择成员 Agent（普通讨论成员，可多选）</label>
          {agents.map(a => (
            <div key={a.id} className={`member-pick${cgSelectedAgents.has(a.id) ? ' is-checked' : ''}`}
              onClick={() => setCgSelectedAgents(prev => { const next = new Set(prev); next.has(a.id) ? next.delete(a.id) : next.add(a.id); return next; })}>
              <Avatar name={a.name} size="sm" />
              <div className="member-pick__meta">
                <div className="member-pick__name">{a.name}</div>
                <div className="member-pick__desc">{a.description || a.modelName}</div>
              </div>
            </div>
          ))}
        </div>
        <div className="form-row">
          <label>指定专家 Agent（负责总结陈词，不参与普通讨论）</label>
          <select className="input" value={cgExpert} onChange={e => setCgExpert(Number(e.target.value))}>
            {agents.map(a => <option key={a.id} value={a.id}>{a.name}</option>)}
          </select>
        </div>
      </Modal>

      {/* 弹窗：发起讨论 */}
      <Modal open={showCreateTopic} onClose={() => setShowCreateTopic(false)} title="发起主题讨论"
        footer={<>
          <button className="btn btn--ghost" onClick={() => setShowCreateTopic(false)}>取消</button>
          <button className="btn btn--brand" onClick={submitCreateTopic}>开始讨论</button>
        </>}>
        <div className="form-row">
          <label>讨论主题</label>
          <input className="input" value={ctTitle} onChange={e => setCtTitle(e.target.value)} placeholder="如：什么是进程调度？" maxLength={255} />
        </div>
      </Modal>

      {/* 弹窗：主题结论 */}
      <Modal open={!!showConclusion} onClose={() => setShowConclusion(null)} title={`📌 ${showConclusion?.title ?? ''}`} width={560}
        footer={<button className="btn btn--ghost" onClick={() => setShowConclusion(null)}>关闭</button>}>
        <div className="conclusion-box">{showConclusion?.conclusion}</div>
        <div className="conclusion-cards">
          {conclusionCards.length > 0 && (
            <>
              <div className="panel__title" style={{ marginTop: 14 }}>生成的知识卡片（{conclusionCards.length}）</div>
              {conclusionCards.map(c => (
                <div key={c.id} className="conclusion-card">
                  <div className="conclusion-card__q">Q: {c.question}</div>
                  <div className="conclusion-card__a">A: {c.answer}</div>
                </div>
              ))}
            </>
          )}
        </div>
      </Modal>
    </div>
  );
}
