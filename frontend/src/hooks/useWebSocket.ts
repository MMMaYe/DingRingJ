import { useEffect, useRef, useCallback, useState } from 'react';
import type { WsPayload } from '../types';

/**
 * WebSocket 连接 Hook：自动重连、收发消息。
 * groupId 为 null 时断开连接。
 */
export default function useWebSocket(groupId: number | null) {
  const wsRef = useRef<WebSocket | null>(null);
  const [connected, setConnected] = useState(false);
  const handlerRef = useRef<((msg: WsPayload) => void) | null>(null);

  /** 注册消息处理函数 */
  const onMessage = useCallback((handler: (msg: WsPayload) => void) => {
    handlerRef.current = handler;
  }, []);

  /** 发送消息 */
  const send = useCallback((payload: WsPayload) => {
    const ws = wsRef.current;
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify(payload));
    }
  }, []);

  useEffect(() => {
    if (groupId === null) {
      wsRef.current?.close();
      wsRef.current = null;
      setConnected(false);
      return;
    }

    let retryTimer: ReturnType<typeof setTimeout> | null = null;
    let closed = false;

    function connect() {
      const proto = location.protocol === 'https:' ? 'wss' : 'ws';
      const ws = new WebSocket(`${proto}://${location.host}/ws/chat?groupId=${groupId}`);
      wsRef.current = ws;

      ws.onopen = () => setConnected(true);
      ws.onclose = () => {
        setConnected(false);
        if (!closed) retryTimer = setTimeout(connect, 2000);
      };
      ws.onmessage = (ev) => {
        try {
          const msg = JSON.parse(ev.data) as WsPayload;
          handlerRef.current?.(msg);
        } catch { /* ignore */ }
      };
    }

    connect();
    return () => {
      closed = true;
      if (retryTimer) clearTimeout(retryTimer);
      wsRef.current?.close();
      wsRef.current = null;
    };
  }, [groupId]);

  return { send, connected, onMessage };
}
