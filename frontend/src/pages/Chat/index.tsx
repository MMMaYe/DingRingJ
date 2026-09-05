import { useState, useEffect, useRef, useCallback, useMemo, useLayoutEffect, type KeyboardEvent as ReactKeyboardEvent, type MouseEvent as ReactMouseEvent } from 'react';
import { useSearchParams } from 'react-router-dom';
import Sidebar, { IconPlus } from '../../components/Sidebar';
import Avatar from '../../components/Avatar';
import MessageItem from './MessageItem';
import DiscussionStatus from '../../components/DiscussionStatus';
import FlowSteps from '../../components/FlowSteps';
import { formatTime, renderMarkdown, findExpandableSvg } from './utils';
import SvgLightbox from './SvgLightbox';
import Modal from '../../components/Modal';
import GroupSettings from '../../components/GroupSettings';
import MultiSelect from '../../components/MultiSelect';
import { toast } from '../../components/Toast';
import { useWebSocketContext } from '../../context/WebSocketContext';
import { API, KbApi } from '../../api';
import type { KbSummary } from '../../api';
import type {
  GroupSummary, GroupDetail, TopicSummary, MemberInfo,
  MessageDTO, AgentDTO, ConclusionDTO, KnowledgeCardDTO, PageResult,
  TopicStatusPayload, FlowEventPayload,
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
  // 讨论状态实时快照(由 TOPIC_STATUS WS 事件驱动)
  const [topicStatus, setTopicStatus] = useState<TopicStatusPayload | null>(null);
  // 图流程步骤事件（由 FLOW_EVENT WS 事件驱动，渲染顶部流程步骤条）
  const [flowSteps, setFlowSteps] = useState<FlowEventPayload[]>([]);
  // 流式发言半成品气泡：streamId -> 累积内容（COMPLETE 替换正式消息 / ABORT 丢弃）
  const [streams, setStreams] = useState<Map<string, { agentId: number; agentName: string; content: string }>>(new Map());
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
  // 侧栏收起状态（localStorage 持久化，刷新后保持）
  const [leftCollapsed, setLeftCollapsed] = useState(() => localStorage.getItem('dingring.chat.leftCollapsed') === '1');
  const [rightCollapsed, setRightCollapsed] = useState(() => localStorage.getItem('dingring.chat.rightCollapsed') === '1');

  // modals
  const [showCreateGroup, setShowCreateGroup] = useState(false);
  const [showConclusion, setShowConclusion] = useState<ConclusionDTO | null>(null);
  const [conclusionCards, setConclusionCards] = useState<KnowledgeCardDTO[]>([]);
  const [expandedSvg, setExpandedSvg] = useState<{ markup: string; trigger: SVGSVGElement } | null>(null);
  // 群设置抽屉
  const [showGroupSettings, setShowGroupSettings] = useState(false);

  // create group form
  const [cgName, setCgName] = useState('');
  const [cgSelectedAgents, setCgSelectedAgents] = useState<Set<number>>(new Set());
  const [cgSelectedKbs, setCgSelectedKbs] = useState<number[]>([]);

  // 知识库列表：右侧信息面板展示绑定 + 建群/群设置下拉多选共用
  const [kbs, setKbs] = useState<KbSummary[]>([]);

  const chatBodyRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  // 中文输入法组合输入（拼音选词）进行中：期间 Enter 用于确认候选词，不得触发发送
  const composingRef = useRef(false);
  // 是否贴底：仅贴底时新消息才自动滚动，避免翻历史被拽回
  const stickToBottomRef = useRef(true);

  // ---- 群消息分页（向上滚动加载更早历史，置顶 prepend） ----
  const [hasMore, setHasMore] = useState(false);          // 是否还有更早的页
  const [loadingOlder, setLoadingOlder] = useState(false); // 顶部加载更早消息中
  const currentPageRef = useRef(1);                        // 当前已加载的页（1 基，最早页=1）
  const loadingOlderRef = useRef(false);                   // 防并发加载
  const pendingPrependRef = useRef<{ prevHeight: number; prevTop: number } | null>(null); // 加载前快照，用于 prepend 后恢复滚动位置

  const groupId = group?.id ?? null;
  const wsCtx = useWebSocketContext();
  const connected = groupId !== null && wsCtx.isConnected(groupId);

  // 连接断开时清理正在进行的流式消息和输入状态
  const prevConnectedRef = useRef(connected);
  useEffect(() => {
    if (prevConnectedRef.current && !connected) {
      setTyping(new Map());
      setStreams(new Map());
    }
    prevConnectedRef.current = connected;
  }, [connected]);

  // ---- 稳定派生数据（供 memo 化的 MessageItem 使用） ----
  const agentNames = useMemo(
    () => group ? group.members.filter(m => m.type === 'AGENT').map(m => m.name) : [],
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

  // ---- 加载知识库列表（面板展示绑定 + 建群/群设置多选共用；静默失败不弹错误） ----
  const reloadKbs = useCallback(async () => {
    try { setKbs(await KbApi.list()); } catch { /* 面板数据缺失不阻塞聊天主流程 */ }
  }, []);

  useEffect(() => { void reloadKbs(); }, [reloadKbs]);

  // 侧栏收起状态持久化
  useEffect(() => { localStorage.setItem('dingring.chat.leftCollapsed', leftCollapsed ? '1' : '0'); }, [leftCollapsed]);
  useEffect(() => { localStorage.setItem('dingring.chat.rightCollapsed', rightCollapsed ? '1' : '0'); }, [rightCollapsed]);

  // ---- 为所有群建立 WS 连接（群列表变化时同步） ----
  useEffect(() => {
    groups.forEach(g => wsCtx.connectGroup(g.id));
  }, [groups, wsCtx.connectGroup]);

  // ---- 设置当前查看的群（用于未读判断） ----
  useEffect(() => {
    wsCtx.setCurrentGroupId(groupId);
  }, [groupId, wsCtx.setCurrentGroupId]);

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
      setStreams(new Map());
      setTopicStatus(null);
      setFlowSteps([]);
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
    setHasMore(false);
    loadingOlderRef.current = false;
    (async () => {
      try {
        // page=0 让后端返回【最新一页】，打开群即见刚聊的内容
        const page = await API.get<PageResult<MessageDTO>>(`/api/groups/${groupId}/messages?page=0&pageSize=200`);
        if (cancelled) return;
        setMessages(page.items);
        currentPageRef.current = page.page;   // 后端回传命中的页号
        setHasMore(page.page > 1);
      } catch (e: any) {
        if (!cancelled) toast(e.message, 'error');
      } finally {
        if (!cancelled) setMsgLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, [groupId]);

  // ---- 向上滚动加载更早消息（取上一页，置顶 prepend，并恢复滚动位置） ----
  const loadOlderMessages = useCallback(async () => {
    if (!groupId || loadingOlderRef.current || currentPageRef.current <= 1) return;
    const targetPage = currentPageRef.current - 1;
    loadingOlderRef.current = true;
    setLoadingOlder(true);
    try {
      const page = await API.get<PageResult<MessageDTO>>(`/api/groups/${groupId}/messages?page=${targetPage}&pageSize=200`);
      const el = chatBodyRef.current;
      const prevHeight = el ? el.scrollHeight : 0;
      const prevTop = el ? el.scrollTop : 0;
      pendingPrependRef.current = { prevHeight, prevTop };
      setMessages(prev => {
        const existing = new Set(prev.map(m => m.id));
        const fresh = page.items.filter(m => !existing.has(m.id));
        return [...fresh, ...prev];
      });
      currentPageRef.current = page.page;
      setHasMore(page.page > 1);
    } catch (e: any) {
      toast(e.message, 'error');
      pendingPrependRef.current = null;
    } finally {
      loadingOlderRef.current = false;
      setLoadingOlder(false);
    }
  }, [groupId]);

  // ---- 置顶 prepend 后恢复滚动位置（避免画面跳动） ----
  useLayoutEffect(() => {
    if (pendingPrependRef.current && chatBodyRef.current) {
      const { prevHeight, prevTop } = pendingPrependRef.current;
      const el = chatBodyRef.current;
      el.scrollTop = el.scrollHeight - prevHeight + prevTop;
      pendingPrependRef.current = null;
    }
  }, [messages]);

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

  // ---- 滚动：仅贴底时跟随新消息（流式 delta 同样触发跟随） ----
  useEffect(() => {
    const el = chatBodyRef.current;
    if (el && stickToBottomRef.current) el.scrollTop = el.scrollHeight;
  }, [messages, streams]);

  const handleBodyScroll = useCallback(() => {
    const el = chatBodyRef.current;
    if (!el) return;
    const nearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 60;
    stickToBottomRef.current = nearBottom;
    if (nearBottom) setUnseenCount(0);
    // 滚到顶部且无并发加载时，加载更早的历史消息
    if (el.scrollTop < 60 && hasMore && !loadingOlderRef.current) loadOlderMessages();
  }, [hasMore, loadOlderMessages]);

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
    if (!groupId) return;
    const unregister = wsCtx.onEvent((evGroupId, msg) => {
      // 仅处理当前群的事件（影响 UI 状态）
      if (evGroupId !== groupId) return;
      const d = msg.data;
      switch (msg.type) {
        case 'NEW_MESSAGE':
          setMessages(prev => [...prev, d as unknown as MessageDTO]);
          if ((d as any).senderType === 'AGENT') {
            setActiveTopic(prev => prev && prev.id === (d as any).topicId ? { ...prev, round: prev.round + 1 } : prev);
          }
          if (!stickToBottomRef.current) setUnseenCount(c => c + 1);
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
        case 'MESSAGE_DELTA':
          setStreams(prev => {
            const next = new Map(prev);
            const cur = next.get((d as any).streamId);
            next.set((d as any).streamId, {
              agentId: (d as any).agentId,
              agentName: (d as any).agentName,
              content: (cur?.content ?? '') + String((d as any).delta ?? ''),
            });
            return next;
          });
          break;
        case 'MESSAGE_COMPLETE': {
          const full = (d as any).message as MessageDTO;
          setStreams(prev => {
            const next = new Map(prev);
            next.delete((d as any).streamId);
            return next;
          });
          setMessages(prev => [...prev, full]);
          if (full.senderType === 'AGENT') {
            setActiveTopic(prev => prev && prev.id === full.topicId ? { ...prev, round: prev.round + 1 } : prev);
          }
          if (!stickToBottomRef.current) setUnseenCount(c => c + 1);
          setGroups(prev => prev.map(g =>
            g.id === groupId
              ? { ...g, lastMessagePreview: `${full.senderName || ''}: ${String(full.content).slice(0, 30)}`, lastMessageTime: full.createTime }
              : g
          ));
          break;
        }
        case 'MESSAGE_ABORT':
          setStreams(prev => {
            const next = new Map(prev);
            next.delete((d as any).streamId);
            return next;
          });
          break;
        case 'TOPIC_CREATED':
          setActiveTopic({ id: (d as any).topicId, title: (d as any).title, status: (d as any).status, messageCount: 0, round: (d as any).round ?? 0, maxRounds: (d as any).maxRounds ?? 20, createTime: '' });
          loadTopics();
          toast(`讨论开始：${(d as any).title}`, 'success');
          break;
        case 'TOPIC_STATUS':
          setTopicStatus(d as unknown as TopicStatusPayload);
          break;
        case 'FLOW_EVENT': {
          const ev = d as unknown as FlowEventPayload;
          setFlowSteps(prev => {
            // 每次图流程都从 preprocess 起步：以此为界重置，避免上一次的 END 定格残留在新流程上
            if (ev.node === 'preprocess') return [ev];
            const next = [...prev, ev];
            return next.slice(-20);
          });
          break;
        }
        case 'TOPIC_STATUS_CHANGED':
          if (activeTopic && activeTopic.id === (d as any).topicId) {
            setActiveTopic(prev => prev ? { ...prev, status: (d as any).status } : prev);
          }
          loadTopics();
          break;
        case 'TOPIC_CLOSED':
          setActiveTopic(null);
          setTopicStatus(null);
          loadTopics();
          loadGroups();
          toast(`讨论「${(d as any).title}」已结束，结论已生成`, 'success');
          break;
        case 'CARD_GENERATED':
          toast(`✨ 已生成 ${(d as any).cardCount} 张知识卡片`, 'success');
          break;
        case 'WORK_TASK_STARTED':
          const task = String((d as any).taskDescription || '');
          toast(`${(d as any).agentName} 开始执行任务${(d as any).supervisorMode ? '（成员协作）' : ''}：${task.length > 30 ? task.slice(0, 30) + '…' : task}`, 'info');
          break;
        case 'ERROR':
          toast((d as any).message || '服务异常', 'error');
          break;
      }
    });
    return unregister;
  }, [wsCtx, groupId, activeTopic, loadGroups, loadTopics]);

  // ---- 发消息 ----
  const hideMention = useCallback(() => setMentionState(s => ({ ...s, open: false })), []);

  const sendMessage = useCallback(() => {
    const content = inputText.trim();
    if (!content || !group) return;
    const payload = replyTo
      ? { type: 'REPLY_MESSAGE', data: { groupId: group.id, content, replyToMessageId: replyTo.id } }
      : { type: 'SEND_MESSAGE', data: { groupId: group.id, content } };
    if (!wsCtx.send(group.id, payload)) {
      toast('连接已断开，正在重连，请稍后重试', 'error');
      return;
    }
    setInputText('');
    setReplyTo(null);
    hideMention();
    // 发送后复位输入框高度并回到底部
    if (inputRef.current) inputRef.current.style.height = 'auto';
    requestAnimationFrame(() => scrollToBottom(false));
  }, [inputText, group, replyTo, wsCtx, scrollToBottom, hideMention]);

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
    // 输入法组合输入中：Enter 是确认候选词（选字），交给输入法处理，不触发发送/选择
    // （iOS Safari 的 isComposing 不可靠，用 keyCode 229 兜底）
    if (composingRef.current || e.nativeEvent.isComposing || e.keyCode === 229) return;
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
      toast('已发起结束讨论，正在生成总结…', 'success');
    } catch (e: any) { toast(e.message, 'error'); }
  }, [activeTopic]);

  // ---- 收束确认/拒绝(CONCLUDE_PROPOSED 时前端按钮触发) ----
  const concludeConfirm = useCallback(() => {
    if (!group) return;
    // 发送确认消息,后端 runLoop 从队列取出后判定为确认收束
    wsCtx.send(group.id, { type: 'SEND_MESSAGE', data: { groupId: group.id, content: '总结吧，可以收尾了' } });
    setTopicStatus(null);
  }, [group, wsCtx]);

  const concludeReject = useCallback(() => {
    if (!group) return;
    // 发送拒绝消息,后端 runLoop 判定为继续讨论
    wsCtx.send(group.id, { type: 'SEND_MESSAGE', data: { groupId: group.id, content: '还想继续讨论一下' } });
    setTopicStatus(null);
  }, [group, wsCtx]);

  const dismissHint = useCallback(() => {
    setTopicStatus(prev => prev ? { ...prev, restartHint: '' } : null);
  }, []);

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
      void reloadKbs();  // 刷新知识库选项（KB 页可能新增/删除）
      setCgName('');
      setCgSelectedAgents(new Set());
      setCgSelectedKbs([]);
      setShowCreateGroup(true);
    } catch (e: any) { toast(e.message, 'error'); }
  }, [reloadKbs]);

  // ---- 群设置（成员管理 + 知识库绑定）：打开前确保 agents / kbs 已加载 ----
  const openGroupSettings = useCallback(async () => {
    try {
      if (!agents.length) {
        const list = await API.get<AgentDTO[]>('/api/agents');
        if (!list.length) { toast('请先到「Agent 管理」创建 Agent', 'error'); return; }
        setAgents(list);
      }
      void reloadKbs();
      setShowGroupSettings(true);
    } catch (e: any) { toast(e.message, 'error'); }
  }, [agents.length, reloadKbs]);

  const submitCreateGroup = useCallback(async () => {
    const agentIds = [...cgSelectedAgents];
    if (!cgName.trim()) { toast('请输入群名称', 'error'); return; }
    if (!agentIds.length) { toast('请至少选择一个成员 Agent', 'error'); return; }
    try {
      // kbIds 可空：不绑定任何知识库也允许建群
      const detail = await API.post<GroupDetail>('/api/groups',
        { name: cgName.trim(), agentIds, kbIds: cgSelectedKbs });
      setShowCreateGroup(false);
      toast('群创建成功', 'success');
      await loadGroups();
      selectGroup(detail.id);
    } catch (e: any) { toast(e.message, 'error'); }
  }, [cgName, cgSelectedAgents, cgSelectedKbs, loadGroups, selectGroup]);

  // ---- 删除群（逻辑删除，历史数据保留） ----
  const deleteGroup = useCallback(async (targetId: number, name: string) => {
    if (!window.confirm(`确定删除群「${name}」吗？`)) return;
    try {
      await API.del(`/api/groups/${targetId}`);
      toast('群已删除', 'success');
      setGroups(prev => prev.filter(g => g.id !== targetId));
      // 删的是当前群：清空右侧，自动选群 effect 会切到剩余第一个群
      if (group?.id === targetId) {
        setGroup(null);
        setActiveTopic(null);
        setMessages([]);
        setTopics([]);
      }
    } catch (e: any) { toast(e.message, 'error'); }
  }, [group?.id]);

  // ---- 辅助 ----
  const handleReply = useCallback((m: MessageDTO) => {
    setReplyTo({ id: m.id, senderName: m.senderName, content: m.content.slice(0, 40) });
    inputRef.current?.focus();
  }, []);

  // 流式半成品气泡不支持引用回复（尚未落库无消息 id）
  const noopReply = useCallback(() => {}, []);

  const openSvgLightbox = useCallback((svg: SVGSVGElement) => {
    setExpandedSvg({ markup: svg.outerHTML, trigger: svg });
  }, []);

  const handleSvgClick = useCallback((event: ReactMouseEvent<HTMLDivElement>) => {
    const svg = findExpandableSvg(event.target);
    if (svg) openSvgLightbox(svg);
  }, [openSvgLightbox]);

  const handleSvgKeyDown = useCallback((event: ReactKeyboardEvent<HTMLDivElement>) => {
    if (event.key !== 'Enter' && event.key !== ' ') return;
    const svg = findExpandableSvg(event.target);
    if (!svg) return;
    event.preventDefault();
    openSvgLightbox(svg);
  }, [openSvgLightbox]);

  // ---- Render ----
  return (
    <div className="app-shell">
      {/* 左侧：导航 + 群列表 */}
      <Sidebar showGroupLabel className={leftCollapsed ? 'is-collapsed' : undefined} onSearch={setGroupKw} footer={<button className="sidebar__new-group" onClick={openCreateGroup}><IconPlus /> 新建群组</button>}>
        <div className="group-list">
          {!groups.length ? (
            <div className="empty"><div className="empty__icon">👥</div>还没有群，点击下方按钮创建</div>
          ) : !filteredGroups.length ? (
            <div className="empty"><div className="empty__icon">🔍</div>没有匹配「{groupKw}」的群组</div>
          ) : filteredGroups.map(g => {
            const unread = wsCtx.getUnreadCount(g.id);
            return (
            <div key={g.id} className={`group-item${group?.id === g.id ? ' group-item--active' : ''}`} onClick={() => { if (group?.id !== g.id) selectGroup(g.id); }}>
              <Avatar name={g.name} size="sm" />
              <div className="group-item__content">
                <div className="group-item__top">
                  <div className="group-item__name-group">
                    <span className="group-item__name">{g.name}</span>
                    {g.activeTopicTitle && <span className="tag tag--brand">讨论中</span>}
                    {unread > 0 && <span className="group-item__badge">{unread > 99 ? '99+' : unread}</span>}
                  </div>
                  <span className="group-item__time">{formatTime(g.lastMessageTime)}</span>
                </div>
                <span className="group-item__preview">{g.lastMessagePreview || '暂无消息'}</span>
              </div>
              <button
                className="group-item__del"
                title="删除群"
                onClick={(e) => { e.stopPropagation(); deleteGroup(g.id, g.name); }}
              >
                <svg width="13" height="13" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"><path d="M2.5 4h11M6.5 4V2.5h3V4M4 4l.7 9.5A1 1 0 0 0 5.7 14.5h4.6a1 1 0 0 0 1-.94L12 4M6.7 7v4.5M9.3 7v4.5"/></svg>
              </button>
            </div>
            );
          })}
        </div>
      </Sidebar>

      {/* 中间：聊天窗口 */}
      <section className="chat-window">
        {/* 侧栏收起开关（悬浮于聊天窗口左右边缘） */}
        <button
          className={`edge-toggle edge-toggle--left${leftCollapsed ? ' is-collapsed' : ''}`}
          onClick={() => setLeftCollapsed(c => !c)}
          title={leftCollapsed ? '展开左侧栏' : '收起左侧栏'}
          aria-label={leftCollapsed ? '展开左侧栏' : '收起左侧栏'}
        >
          {leftCollapsed
            ? <svg width="12" height="12" viewBox="0 0 12 12" fill="none"><path d="M4.5 2.5L8 6l-3.5 3.5" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round"/></svg>
            : <svg width="12" height="12" viewBox="0 0 12 12" fill="none"><path d="M7.5 2.5L4 6l3.5 3.5" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round"/></svg>}
        </button>
        {group && (
          <button
            className={`edge-toggle edge-toggle--right${rightCollapsed ? ' is-collapsed' : ''}`}
            onClick={() => setRightCollapsed(c => !c)}
            title={rightCollapsed ? '展开右侧栏' : '收起右侧栏'}
            aria-label={rightCollapsed ? '展开右侧栏' : '收起右侧栏'}
          >
            {rightCollapsed
              ? <svg width="12" height="12" viewBox="0 0 12 12" fill="none"><path d="M7.5 2.5L4 6l3.5 3.5" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round"/></svg>
              : <svg width="12" height="12" viewBox="0 0 12 12" fill="none"><path d="M4.5 2.5L8 6l-3.5 3.5" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round"/></svg>}
          </button>
        )}
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
                    : '暂无进行中的讨论，直接提问即可自动开启'}
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

            {/* 图流程步骤条(由 FLOW_EVENT WS 事件驱动，可观测性) */}
            <FlowSteps steps={flowSteps} />

            {/* 讨论状态横幅(由 TOPIC_STATUS WS 事件驱动) */}
            <DiscussionStatus
              status={topicStatus}
              onConcludeConfirm={concludeConfirm}
              onConcludeReject={concludeReject}
              onDismissHint={dismissHint}
            />

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
              <div className="chat-body" ref={chatBodyRef} onScroll={handleBodyScroll} onClick={handleSvgClick} onKeyDown={handleSvgKeyDown}>
                {/* 顶部加载更早消息的占位 */}
                {loadingOlder && (
                  <div className="msg-older-loading">
                    <span className="msg-older-loading__spinner" />
                    <span>加载更早的消息…</span>
                  </div>
                )}
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
                    isMatch={matchSet.has(m.id)}
                    isCurrent={currentMatchId === m.id}
                    agentNames={agentNames}
                    onReply={handleReply}
                  />
                ))}
                {/* 流式发言半成品气泡：复用 MessageItem，COMPLETE 后由正式消息替换 */}
                {!msgLoading && [...streams.entries()].map(([sid, s]) => (
                  <div key={sid} className="msg-streaming">
                    <MessageItem
                      m={{
                        id: -1, groupId: group.id, topicId: null, senderId: s.agentId,
                        senderName: s.agentName, senderType: 'AGENT', senderAvatar: null,
                        messageType: 'TEXT', content: s.content, replyToMessageId: null,
                        replyToSenderName: null, replyToContent: null, createTime: '',
                      }}
                      isMatch={false}
                      isCurrent={false}
                      agentNames={agentNames}
                      onReply={noopReply}
                    />
                  </div>
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
                            <span className="tag">成员</span>
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
                    placeholder="输入消息，@ 可提及 Agent，@成员说「总结一下」可结束讨论并生成结论…"
                    onCompositionStart={() => { composingRef.current = true; }}
                    onCompositionEnd={() => { composingRef.current = false; }}
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
              <div className="chat-input__hint">Enter 发送 · Shift+Enter 换行 · @成员说「总结一下」触发总结陈词</div>
            </div>
          </>
        )}
      </section>

      {/* 右侧：信息面板（对齐设计稿） */}
      {group && (
        <aside className={`info-panel${rightCollapsed ? ' is-collapsed' : ''}`}>
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
                      {m.type === 'USER' ? <span className="tag tag--brand">群主</span>
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
                    {activeTopic.status === 'CONCLUDING' ? '已结束' : `轮次 ${activeTopic.round}/${activeTopic.maxRounds}`}
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

          {/* 知识库管理：展示群绑定的知识库（chat_group.knowledge_base_config.kbIds） */}
          <div className="info-panel__section">
            <div className="info-panel__heading">知识库管理</div>
            <div className="member-list">
              {(group.kbIds ?? []).map(id => {
                const kb = kbs.find(k => k.id === id);
                return (
                  <div key={id} className="member-item">
                    <Avatar name={kb?.name ?? `KB-${id}`} size="sm" />
                    <div className="member-item__info">
                      <span className="member-item__name">{kb?.name ?? `知识库 #${id}`}</span>
                      <div className="member-item__row">
                        <span className="tag tag--success">已绑定</span>
                      </div>
                    </div>
                  </div>
                );
              })}
              {!(group.kbIds ?? []).length && (
                <div className="member-item">
                  <Avatar name="知识库" size="sm" />
                  <div className="member-item__info">
                    <span className="member-item__name">未绑定知识库</span>
                    <div className="member-item__row">
                      <span className="tag tag--neutral">在群设置中绑定</span>
                    </div>
                  </div>
                </div>
              )}
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
            {/* 头像串联预览：已选成员 */}
            <div className="preview-stack" aria-hidden>
              {[...cgSelectedAgents].slice(0, 5).map(id => {
                const a = agents.find(x => x.id === id);
                return a ? <div key={id} className="preview-stack__item"><Avatar name={a.name} size="sm" /></div> : null;
              })}
              {cgSelectedAgents.size === 0 && (
                <div className="preview-stack__empty">未选择</div>
              )}
            </div>
            <div className="preview-pills">
              <span className="preview-pill">
                <span className="preview-pill__dot" />
                {cgSelectedAgents.size} 位成员
              </span>
              {cgSelectedKbs.length > 0 && (
                <span className="preview-pill">
                  <span className="preview-pill__dot" />
                  {cgSelectedKbs.length} 个知识库
                </span>
              )}
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
              <span className="chapter__hint">参与讨论的 Agent，任意成员均可总结，可多选</span>
            </div>
            <span className="chapter__count">{cgSelectedAgents.size}<span className="chapter__count-sep">/</span>{agents.length}</span>
          </header>
          <div className="member-grid">
            {agents.map((a, idx) => {
              const checked = cgSelectedAgents.has(a.id);
              const order = checked ? [...cgSelectedAgents].indexOf(a.id) + 1 : 0;
              return (
                <div
                  key={a.id}
                  className={`member-card${checked ? ' is-checked' : ''}`}
                  style={{ animationDelay: `${idx * 28}ms` }}
                  onClick={() => {
                    setCgSelectedAgents(prev => {
                      const next = new Set(prev);
                      next.has(a.id) ? next.delete(a.id) : next.add(a.id);
                      return next;
                    });
                  }}
                  role="checkbox"
                  aria-checked={checked}
                  tabIndex={0}
                  onKeyDown={e => { if (e.key === ' ' || e.key === 'Enter') { e.preventDefault(); setCgSelectedAgents(prev => { const next = new Set(prev); next.has(a.id) ? next.delete(a.id) : next.add(a.id); return next; }); } }}
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
                </div>
              );
            })}
          </div>
        </section>

        {/* 章节 03：绑定知识库（可选） */}
        <section className="chapter">
          <header className="chapter__head">
            <span className="chapter__num">03</span>
            <div className="chapter__title-wrap">
              <h3 className="chapter__title">绑定知识库</h3>
              <span className="chapter__hint">讨论时作为 RAG 检索源注入，可多选，可稍后在群设置中调整</span>
            </div>
            <span className="chapter__count">{cgSelectedKbs.length}<span className="chapter__count-sep">/</span>{kbs.length}</span>
          </header>
          <MultiSelect
            options={kbs.map(kb => ({
              value: kb.id,
              label: kb.name,
              desc: kb.description ? (kb.description.length > 18 ? kb.description.slice(0, 18) + '…' : kb.description) : undefined,
            }))}
            selected={cgSelectedKbs}
            onChange={setCgSelectedKbs}
            placeholder="选择要绑定的知识库（可多选）"
            emptyText="暂无知识库，可到「知识库管理」页创建"
          />
        </section>
      </Modal>

      {/* 弹窗：主题结论 */}
      <Modal open={!!showConclusion} onClose={() => setShowConclusion(null)} title={`📌 ${showConclusion?.title ?? ''}`} width={560}
        footer={<button className="btn btn--ghost" onClick={() => setShowConclusion(null)}>关闭</button>}>
        <div
          className="conclusion-box md-body"
          onClick={handleSvgClick}
          onKeyDown={handleSvgKeyDown}
          dangerouslySetInnerHTML={{ __html: showConclusion ? renderMarkdown(showConclusion.conclusion) : '' }}
        />
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

      {/* 抽屉：群设置（成员管理 + 知识库绑定） */}
      {group && (
        <GroupSettings
          open={showGroupSettings}
          onClose={() => setShowGroupSettings(false)}
          group={group}
          agents={agents}
          kbs={kbs}
          onUpdated={detail => { setGroup(detail); setActiveTopic(detail.activeTopic ?? null); }}
        />
      )}

      {expandedSvg && (
        <SvgLightbox
          svgMarkup={expandedSvg.markup}
          restoreFocus={expandedSvg.trigger}
          onClose={() => setExpandedSvg(null)}
        />
      )}
    </div>
  );
}
