import {
  createContext, useContext, useRef, useState, useCallback, useEffect,
  type ReactNode,
} from 'react';
import type { WsPayload, MessageDTO } from '../types';

/* ============================================================
   全局 WebSocket 上下文
   - 维护多个 WebSocket 连接（每个群一个）
   - 按 groupId 分发消息，存入各群的消息缓存
   - 管理每个群的未读计数
   - 断线自动重连（指数退避，最大 30s）
   ============================================================ */

/** 需要计入未读的消息类型 */
const UNREAD_TYPES = new Set([
  'NEW_MESSAGE',
  'MESSAGE_COMPLETE',
]);

/** 每个群最多缓存消息条数 */
const MAX_MESSAGES = 200;

/** 重连退避上限（ms） */
const MAX_RETRY_DELAY = 30_000;

// ---- 上下文类型 ----
interface WsCtx {
  /** 建立指定群的 WS 连接（幂等） */
  connectGroup: (groupId: number) => void;
  /** 断开指定群的 WS 连接 */
  disconnectGroup: (groupId: number) => void;
  /** 清空指定群的未读数 */
  clearUnread: (groupId: number) => void;
  /** 获取指定群的未读数 */
  getUnreadCount: (groupId: number) => number;
  /** 获取指定群的消息缓存 */
  getMessages: (groupId: number) => MessageDTO[];
  /** 向指定群发送 WS 消息 */
  send: (groupId: number, payload: WsPayload) => boolean;
  /** 指定群是否已连接 */
  isConnected: (groupId: number) => boolean;
  /** 注册全局事件监听（用于 ChatPage 接收实时 WS 事件） */
  onEvent: (handler: (groupId: number, msg: WsPayload) => void) => () => void;
  /** 当前正在查看的群 ID（用于判断未读） */
  setCurrentGroupId: (id: number | null) => void;
}

const WebSocketContext = createContext<WsCtx | null>(null);

export function useWebSocketContext(): WsCtx {
  const ctx = useContext(WebSocketContext);
  if (!ctx) throw new Error('useWebSocketContext must be used within WebSocketProvider');
  return ctx;
}

// ---- Provider ----
export function WebSocketProvider({ children }: { children: ReactNode }) {
  // 每个群的 WebSocket 实例
  const wsMap = useRef(new Map<number, WebSocket>());
  // 每个群的重连定时器
  const retryMap = useRef(new Map<number, ReturnType<typeof setTimeout>>());
  // 每个群的重连退避计数
  const retryCountMap = useRef(new Map<number, number>());
  // 每个群是否已主动关闭（主动 disconnect 不重连）
  const closedMap = useRef(new Map<number, boolean>());
  // 消息缓存
  const [messagesMap, setMessagesMap] = useState<Map<number, MessageDTO[]>>(new Map());
  // 未读计数
  const [unreadMap, setUnreadMap] = useState<Map<number, number>>(new Map());
  // 连接状态
  const [connectedSet, setConnectedSet] = useState<Set<number>>(new Set());
  // 当前查看的群
  const currentGroupIdRef = useRef<number | null>(null);
  // 全局事件监听器
  const eventHandlerRef = useRef<((groupId: number, msg: WsPayload) => void) | null>(null);

  // ---- 内部：收到消息 ----
  const handleMessage = useCallback((groupId: number, msg: WsPayload) => {
    // 分发给事件监听器（ChatPage 用于更新 typing / streams / topics 等）
    eventHandlerRef.current?.(groupId, msg);

    // 消息缓存：仅对实际消息类型
    if (msg.type === 'NEW_MESSAGE' || msg.type === 'MESSAGE_COMPLETE') {
      const message = (msg.type === 'MESSAGE_COMPLETE'
        ? (msg.data as any).message
        : msg.data) as MessageDTO;

      setMessagesMap(prev => {
        const next = new Map(prev);
        const existing = next.get(groupId) ?? [];
        // 去重（MESSAGE_COMPLETE 可能与 NEW_MESSAGE 重复）
        if (existing.some(m => m.id === message.id)) return prev;
        const updated = [...existing, message].slice(-MAX_MESSAGES);
        next.set(groupId, updated);
        return next;
      });

      // 未读计数：非当前查看的群才 +1
      if (currentGroupIdRef.current !== groupId && UNREAD_TYPES.has(msg.type)) {
        setUnreadMap(prev => {
          const next = new Map(prev);
          next.set(groupId, (next.get(groupId) ?? 0) + 1);
          return next;
        });
      }
    }
  }, []);

  // ---- 连接 ----
  const connectGroup = useCallback((groupId: number) => {
    // 已连接或正在连接中，跳过
    if (wsMap.current.has(groupId)) return;

    closedMap.current.set(groupId, false);
    retryCountMap.current.set(groupId, 0);

    const doConnect = () => {
      const proto = location.protocol === 'https:' ? 'wss' : 'ws';
      const ws = new WebSocket(`${proto}://${location.host}/ws/chat?groupId=${groupId}`);
      wsMap.current.set(groupId, ws);

      ws.onopen = () => {
        setConnectedSet(prev => new Set(prev).add(groupId));
        retryCountMap.current.set(groupId, 0);
      };

      ws.onclose = () => {
        setConnectedSet(prev => {
          const next = new Set(prev);
          next.delete(groupId);
          return next;
        });
        wsMap.current.delete(groupId);

        // 非主动关闭 → 自动重连（指数退避）
        if (!closedMap.current.get(groupId)) {
          const count = retryCountMap.current.get(groupId) ?? 0;
          const delay = Math.min(2000 * 2 ** count, MAX_RETRY_DELAY);
          retryCountMap.current.set(groupId, count + 1);
          const timer = setTimeout(doConnect, delay);
          retryMap.current.set(groupId, timer);
        }
      };

      ws.onmessage = (ev) => {
        try {
          const msg = JSON.parse(ev.data) as WsPayload;
          handleMessage(groupId, msg);
        } catch { /* ignore malformed */ }
      };
    };

    doConnect();
  }, [handleMessage]);

  // ---- 断开 ----
  const disconnectGroup = useCallback((groupId: number) => {
    closedMap.current.set(groupId, true);
    const timer = retryMap.current.get(groupId);
    if (timer) {
      clearTimeout(timer);
      retryMap.current.delete(groupId);
    }
    const ws = wsMap.current.get(groupId);
    if (ws) {
      ws.close();
      wsMap.current.delete(groupId);
    }
    setConnectedSet(prev => {
      const next = new Set(prev);
      next.delete(groupId);
      return next;
    });
  }, []);

  // ---- 清空未读 ----
  const clearUnread = useCallback((groupId: number) => {
    setUnreadMap(prev => {
      const next = new Map(prev);
      next.set(groupId, 0);
      return next;
    });
  }, []);

  // ---- 查询方法 ----
  const getUnreadCount = useCallback((groupId: number) => {
    return unreadMap.get(groupId) ?? 0;
  }, [unreadMap]);

  const getMessages = useCallback((groupId: number) => {
    return messagesMap.get(groupId) ?? [];
  }, [messagesMap]);

  const isConnected = useCallback((groupId: number) => {
    return connectedSet.has(groupId);
  }, [connectedSet]);

  // ---- 发送 ----
  const send = useCallback((groupId: number, payload: WsPayload) => {
    const ws = wsMap.current.get(groupId);
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify(payload));
      return true;
    }
    return false;
  }, []);

  // ---- 事件监听注册 ----
  const onEvent = useCallback((handler: (groupId: number, msg: WsPayload) => void) => {
    eventHandlerRef.current = handler;
    return () => { eventHandlerRef.current = null; };
  }, []);

  // ---- 设置当前群 ----
  const setCurrentGroupId = useCallback((id: number | null) => {
    currentGroupIdRef.current = id;
    // 切到某群时自动清零该群未读
    if (id !== null) {
      setUnreadMap(prev => {
        const next = new Map(prev);
        next.set(id, 0);
        return next;
      });
    }
  }, []);

  // ---- 清理：组件卸载时关闭所有连接 ----
  useEffect(() => {
    return () => {
      retryMap.current.forEach(t => clearTimeout(t));
      wsMap.current.forEach(ws => ws.close());
    };
  }, []);

  const value: WsCtx = {
    connectGroup,
    disconnectGroup,
    clearUnread,
    getUnreadCount,
    getMessages,
    send,
    isConnected,
    onEvent,
    setCurrentGroupId,
  };

  return (
    <WebSocketContext.Provider value={value}>
      {children}
    </WebSocketContext.Provider>
  );
}
