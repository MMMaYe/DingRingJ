import { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';
import Sidebar, { IconPlus } from '../../components/Sidebar';
import Avatar from '../../components/Avatar';
import MessageItem from './MessageItem';
import { formatTime } from './utils';
import Modal from '../../components/Modal';
import GroupSettings from '../../components/GroupSettings';
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
  const [mentionIdx, setMentionIdx] = useState(0);
  const [searchParams, setSearchParams] = useSearchParams();
  // 消息加载态（切群时展示骨架屏，避免旧消息闪现）
  const [msgLoading, setMsgLoading] = useState(false);
  // 用户上翻历史时收到的新消息数（悬浮按钮提示）
  const [unseenCount, setUnseenCount] = useState(0);

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
  // 群设置抽屉
  const [showGroupSettings, setShowGroupSettings] = useState(false);

  // create group form
  const [cgName, setCgName] = useState('');
  const [cgSelectedAgents, setCgSelectedAgents] = useState<Set<number>>(new Set());
  const [cgExpert, setCgExpert] = useState<number>(0);
  // create topic form
  const [ctTitle, setCtTitle] = useState('');

  const chatBodyRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  // 是否贴底：仅贴底时新消息才自动滚动，避免翻历史被拽回
  const stickToBottomRef = useRef(true);

  const groupId = group?.id ?? null;
  const { send, onMessage, connected } = useWebSocket(groupId);

  // ---- 稳定派生数据（供 memo 化的 MessageItem 使用） ----
  const agentNames = useMemo(
    () => group ? group.members.filter(m => m.type === 'AGENT').map(m => m.name) : [],
    [group],
  );
  const expertIds = useMemo(
    () => new Set(group?.members.filter(m => m.type === 'AGENT' && m.role === 'EXPERT').map(m => m.id) ?? []),
    [group],
  );

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
  // Set 查找 O(1)，避免每条消息渲染时 includes O(n)
  const matchSet = useMemo(() => new Set(searchMatches), [searchMatches]);
  const currentMatchId = searchMatches.length ? searchMatches[Math.min(searchIdx, searchMatches.length - 1)] : null;

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
  const selectGroup = useCallback(async (targetId: number) => {
    try {
      const detail = await API.get<GroupDetail>(`/api/groups/${targetId}`);
      setGroup(detail);
      setActiveTopic(detail.activeTopic ?? null);
      setReplyTo(null);
      setTyping(new Map());
      stickToBottomRef.current = true;
      setUnseenCount(0);
      setSearchParams({ groupId: String(targetId) }, { replace: true });
    } catch (e: any) { toast(e.message, 'error'); }
  }, [setSearchParams]);

  // ---- 加载消息（仅依赖 groupId，群设置更新不触发重拉；带取消保护防快速切群串数据） ----
  useEffect(() => {
    if (!groupId) return;
    let cancelled = false;
    setMsgLoading(true);
    setMessages([]);
    (async () => {
      try {
        const page = await API.get<PageResult<MessageDTO>>(`/api/groups/${groupId}/messages?page=1&pageSize=200`);
        if (!cancelled) setMessages(page.items);
      } catch (e: any) {
        if (!cancelled) toast(e.message, 'error');
      } finally {
        if (!cancelled) setMsgLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, [groupId]);

  // ---- 加载主题 ----
  useEffect(() => {
    if (!groupId) return;
    let cancelled = false;
    (async () => {
      try {
        const list = await API.get<TopicSummary[]>(`/api/groups/${groupId}/topics`);
        if (!cancelled) setTopics(list);
      } catch { /* ignore */ }
    })();
    return () => { cancelled = true; };
  }, [groupId]);

  // ---- 滚动：仅贴底时跟随新消息 ----
  useEffect(() => {
    const el = chatBodyRef.current;
    if (el && stickToBottomRef.current) el.scrollTop = el.scrollHeight;
  }, [messages]);

  const handleBodyScroll = useCallback(() => {
    const el = chatBodyRef.current;
    if (!el) return;
    const nearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 60;
    stickToBottomRef.current = nearBottom;
    if (nearBottom) setUnseenCount(0);
  }, []);

  const scrollToBottom = useCallback((smooth = true) => {
    const el = chatBodyRef.current;
    if (!el) return;
    el.scrollTo({ top: el.scrollHeight, behavior: smooth ? 'smooth' : 'auto' });
    stickToBottomRef.current = true;
    setUnseenCount(0);
  }, []);

  // ---- WebSocket 消息处理 ----
  const loadTopics = useCallback(async () => {
    if (!groupId) return;
    try { setTopics(await API.get<TopicSummary[]>(`/api/groups/${groupId}/topics`)); } catch { /* ignore */ }
  }, [groupId]);

  useEffect(() => {
    onMessage((msg) => {
      const d = msg.data;
      switch (msg.type) {
        case 'NEW_MESSAGE':
          setMessages(prev => [...prev, d as unknown as MessageDTO]);
          // 用户正在翻历史：不打断，计入新消息提示
          if (!stickToBottomRef.current) setUnseenCount(c => c + 1);
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
  }, [onMessage, groupId, activeTopic, loadGroups, loadTopics]);

  // ---- 发消息 ----
  const hideMention = useCallback(() => setMentionState(s => ({ ...s, open: false })), []);

  const sendMessage = useCallback(() => {
    const content = inputText.trim();
    if (!content || !group) return;
    const payload = replyTo
      ? { type: 'REPLY_MESSAGE', data: { groupId: group.id, content, replyToMessageId: replyTo.id } }
      : { type: 'SEND_MESSAGE', data: { groupId: group.id, content } };
    if (!send(payload)) {
      toast('连接已断开，正在重连，请稍后重试', 'error');
      return;
    }
    setInputText('');
    setReplyTo(null);
    hideMention();
    // 发送后复位输入框高度并回到底部
    if (inputRef.current) inputRef.current.style.height = 'auto';
    requestAnimationFrame(() => scrollToBottom(false));
  }, [inputText, group, replyTo, send, scrollToBottom, hideMention]);

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
    setMentionIdx(0);
  }, [group, hideMention]);

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

  // ---- 键盘：@提及浮层支持 ↑↓ 选择、Enter/Tab 确认、Esc 关闭 ----
  const handleKeyDown = useCallback((e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (mentionState.open) {
      const len = mentionState.candidates.length;
      if (e.key === 'ArrowDown') { e.preventDefault(); setMentionIdx(i => (i + 1) % len); return; }
      if (e.key === 'ArrowUp') { e.preventDefault(); setMentionIdx(i => (i - 1 + len) % len); return; }
      if (e.key === 'Enter' || e.key === 'Tab') {
        e.preventDefault();
        pickMention(mentionState.candidates[Math.min(mentionIdx, len - 1)].name);
        return;
      }
      if (e.key === 'Escape') { hideMention(); return; }
    }
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      sendMessage();
    }
  }, [sendMessage, mentionState, mentionIdx, pickMention, hideMention]);

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

  // ---- 群设置（成员管理）：打开前确保 agents 已加载 ----
  const openGroupSettings = useCallback(async () => {
    try {
      if (!agents.length) {
        const list = await API.get<AgentDTO[]>('/api/agents');
        if (!list.length) { toast('请先到「Agent 管理」创建 Agent', 'error'); return; }
        setAgents(list);
      }
      setShowGroupSettings(true);
    } catch (e: any) { toast(e.message, 'error'); }
  }, [agents.length]);

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
  const handleReply = useCallback((m: MessageDTO) => {
    setReplyTo({ id: m.id, senderName: m.senderName, content: m.content.slice(0, 40) });
    inputRef.current?.focus();
  }, []);

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
            <div key={g.id} className={`group-item${group?.id === g.id ? ' group-item--active' : ''}`} onClick={() => { if (group?.id !== g.id) selectGroup(g.id); }}>
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
                  {!connected && <span className="chat-conn"><span className="chat-conn__dot" />连接中…</span>}
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
                <button className="chat-header__icon-btn" title="群设置" onClick={openGroupSettings}>
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z"/><circle cx="12" cy="12" r="3"/></svg>
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

            <div className="chat-body-wrap">
              <div className="chat-body" ref={chatBodyRef} onScroll={handleBodyScroll}>
                {msgLoading ? (
                  /* 骨架屏：切群时占位，避免旧消息闪现与空白跳动 */
                  <>
                    {[0, 1, 2].map(i => (
                      <div key={i} className={`msg-skeleton${i === 1 ? ' msg-skeleton--self' : ''}`}>
                        <span className="msg-skeleton__avatar" />
                        <span className="msg-skeleton__bubble" style={{ width: `${46 - i * 8}%` }} />
                      </div>
                    ))}
                  </>
                ) : messages.map(m => (
                  <MessageItem
                    key={m.id}
                    m={m}
                    expert={m.senderType === 'AGENT' && expertIds.has(m.senderId)}
                    isMatch={matchSet.has(m.id)}
                    isCurrent={currentMatchId === m.id}
                    agentNames={agentNames}
                    onReply={handleReply}
                  />
                ))}
              </div>
              {unseenCount > 0 && (
                <button className="chat-jump" onClick={() => scrollToBottom(true)}>
                  <svg width="12" height="12" viewBox="0 0 14 14" fill="none"><path d="M7 3v8M3 7l4 4 4-4" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round"/></svg>
                  {unseenCount} 条新消息
                </button>
              )}
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
                    <div className="mention-pop is-open" role="listbox">
                      {mentionState.candidates.map((m, idx) => (
                        <div
                          key={m.id}
                          className={`mention-pop__item${idx === mentionIdx ? ' is-active' : ''}`}
                          role="option"
                          aria-selected={idx === mentionIdx}
                          onMouseEnter={() => setMentionIdx(idx)}
                          onMouseDown={e => { e.preventDefault(); pickMention(m.name); }}
                        >
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
                <button className="btn btn--brand" onClick={sendMessage} disabled={!inputText.trim() || !connected}>发送</button>
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

      {/* 弹窗：新建群 — Editorial 风格 */}
      <Modal
        open={showCreateGroup}
        onClose={() => setShowCreateGroup(false)}
        title="新建群聊"
        eyebrow="Compose"
        subtitle="三步组建你的学习讨论群"
        width={540}
        footer={<>
          <div className="modal__preview">
            {/* 头像串联预览：成员 + 专家 */}
            <div className="preview-stack" aria-hidden>
              {[...cgSelectedAgents].slice(0, 4).map(id => {
                const a = agents.find(x => x.id === id);
                return a ? <div key={id} className="preview-stack__item"><Avatar name={a.name} size="sm" /></div> : null;
              })}
              {cgExpert && (() => {
                const ex = agents.find(x => x.id === cgExpert);
                return ex ? <div className="preview-stack__item preview-stack__item--expert"><Avatar name={ex.name} size="sm" /></div> : null;
              })()}
              {cgSelectedAgents.size === 0 && !cgExpert && (
                <div className="preview-stack__empty">未选择</div>
              )}
            </div>
            <div className="preview-pills">
              <span className="preview-pill">
                <span className="preview-pill__dot" />
                {cgSelectedAgents.size} 位成员
              </span>
              <span className="preview-pill preview-pill--expert">
                <span className="preview-pill__dot" />
                {cgExpert ? '1 位专家' : '未指定'}
              </span>
            </div>
          </div>
          <div className="modal__footer-actions">
            <button className="btn btn--ghost" onClick={() => setShowCreateGroup(false)}>取消</button>
            <button
              className={`btn btn--brand${cgName.trim() && cgSelectedAgents.size > 0 ? ' is-dirty' : ''}`}
              onClick={submitCreateGroup}
              disabled={!cgName.trim() || cgSelectedAgents.size === 0}
            >
              创建群聊
            </button>
          </div>
        </>}
      >
        {/* 章节 01：命名 */}
        <section className="chapter">
          <header className="chapter__head">
            <span className="chapter__num">01</span>
            <div className="chapter__title-wrap">
              <h3 className="chapter__title">群名称</h3>
              <span className="chapter__hint">给学习群起个清晰的名字</span>
            </div>
            <span className="chapter__count">{cgName.length}<span className="chapter__count-sep">/</span>64</span>
          </header>
          <input
            className="input chapter__input"
            value={cgName}
            onChange={e => setCgName(e.target.value)}
            placeholder="如：Java 并发学习群"
            maxLength={64}
            autoFocus
          />
        </section>

        {/* 章节 02：选成员 */}
        <section className="chapter">
          <header className="chapter__head">
            <span className="chapter__num">02</span>
            <div className="chapter__title-wrap">
              <h3 className="chapter__title">讨论成员</h3>
              <span className="chapter__hint">参与普通讨论的 Agent，可多选</span>
            </div>
            <span className="chapter__count">{cgSelectedAgents.size}<span className="chapter__count-sep">/</span>{agents.length}</span>
          </header>
          <div className="member-grid">
            {agents.map((a, idx) => {
              const checked = cgSelectedAgents.has(a.id);
              const order = checked ? [...cgSelectedAgents].indexOf(a.id) + 1 : 0;
              const isExpert = a.id === cgExpert;
              return (
                <div
                  key={a.id}
                  className={`member-card${checked ? ' is-checked' : ''}${isExpert ? ' is-locked' : ''}`}
                  style={{ animationDelay: `${idx * 28}ms` }}
                  onClick={() => {
                    if (isExpert) return;
                    setCgSelectedAgents(prev => {
                      const next = new Set(prev);
                      next.has(a.id) ? next.delete(a.id) : next.add(a.id);
                      return next;
                    });
                  }}
                  role="checkbox"
                  aria-checked={checked}
                  aria-disabled={isExpert}
                  tabIndex={isExpert ? -1 : 0}
                  onKeyDown={e => { if (!isExpert && (e.key === ' ' || e.key === 'Enter')) { e.preventDefault(); setCgSelectedAgents(prev => { const next = new Set(prev); next.has(a.id) ? next.delete(a.id) : next.add(a.id); return next; }); } }}
                >
                  {checked && <span className="member-card__order" aria-hidden>{order}</span>}
                  {checked && (
                    <span className="member-card__check" aria-hidden>
                      <svg width="12" height="12" viewBox="0 0 12 12" fill="none"><path d="M2.5 6.5l2.5 2.5 4.5-5" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"/></svg>
                    </span>
                  )}
                  <Avatar name={a.name} size="sm" />
                  <div className="member-card__meta">
                    <div className="member-card__name">{a.name}</div>
                    <div className="member-card__desc">{a.description || a.modelName}</div>
                  </div>
                  {isExpert && <span className="tag tag--warning member-card__role-tag">已选为专家</span>}
                </div>
              );
            })}
          </div>
        </section>

        {/* 章节 03：选专家 */}
        <section className="chapter">
          <header className="chapter__head">
            <span className="chapter__num">03</span>
            <div className="chapter__title-wrap">
              <h3 className="chapter__title">专家 Agent</h3>
              <span className="chapter__hint">负责讨论结束时的总结陈词，不参与普通讨论</span>
            </div>
          </header>
          <div className="expert-grid">
            {agents.map(a => {
              const selected = a.id === cgExpert;
              const inMembers = cgSelectedAgents.has(a.id);
              return (
                <div
                  key={a.id}
                  className={`expert-card${selected ? ' is-selected' : ''}${inMembers ? ' is-conflict' : ''}`}
                  onClick={() => {
                    if (inMembers) return;
                    setCgExpert(a.id);
                  }}
                  title={inMembers ? '请先取消该 Agent 的普通成员勾选' : ''}
                >
                  <Avatar name={a.name} size="sm" />
                  <div className="expert-card__meta">
                    <div className="expert-card__name">{a.name}</div>
                    <div className="expert-card__desc">{a.description || a.modelName}</div>
                  </div>
                  {selected ? (
                    <span className="tag tag--warning">已选专家</span>
                  ) : inMembers ? (
                    <span className="tag tag--neutral">成员中</span>
                  ) : (
                    <span className="expert-card__pick">点击提名</span>
                  )}
                </div>
              );
            })}
          </div>
        </section>
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

      {/* 抽屉：群设置（成员管理） */}
      {group && (
        <GroupSettings
          open={showGroupSettings}
          onClose={() => setShowGroupSettings(false)}
          group={group}
          agents={agents}
          onUpdated={detail => { setGroup(detail); setActiveTopic(detail.activeTopic ?? null); }}
        />
      )}
    </div>
  );
}
