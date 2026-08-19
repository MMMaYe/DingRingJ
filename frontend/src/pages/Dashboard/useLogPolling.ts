import { useCallback, useEffect, useRef, useState } from 'react';
import { LogApi, type LogEvent, type LogQueryResult } from '../../api';

/**
 * 日志轮询 Hook：seq 游标增量拉取，页面不可见时暂停，恢复后从上次 seq 继续。
 *
 * <h3>不重不丢保证</h3>
 * - 文件字节偏移由服务端采集器单点推进，前端只持有 seq：
 *   首次拉取返回 latestSeq，之后每次回传 afterSeq=latestSeq，服务端只发更新的
 * - visibilitychange 暂停轮询（恢复后从上次 seq 继续，不丢日志）
 * - 过滤条件变化时重置 seq 重新拉取
 */
export function useLogPolling(filters: {
  level?: string;
  eventCode?: string;
  traceId?: string;
  keyword?: string;
}) {
  const [events, setEvents] = useState<LogEvent[]>([]);
  const [fileSize, setFileSize] = useState<number>(0);
  const [totalCount, setTotalCount] = useState<number>(0);
  const [polling, setPolling] = useState<boolean>(true);
  const [lastRefresh, setLastRefresh] = useState<number>(Date.now());
  const [pendingCount, setPendingCount] = useState<number>(0);

  /** 已确认接收到的最大 seq（不含 pending 中的） */
  const seqRef = useRef<number>(0);
  const pollingRef = useRef<boolean>(true);
  const filtersRef = useRef(filters);
  // 暂存轮询到但用户尚未"接受插入"的新事件
  const pendingRef = useRef<LogEvent[]>([]);
  /** pending 中的最大 seq，避免未点"插入"时游标停滞导致重复拉取 */
  const pendingSeqRef = useRef<number>(0);

  // 同步 ref
  useEffect(() => { pollingRef.current = polling; }, [polling]);
  useEffect(() => { filtersRef.current = filters; }, [filters]);

  const fetchOnce = useCallback(async (reset: boolean) => {
    try {
      const params = reset
        ? { limit: 200, ...filtersRef.current }
        : {
            afterSeq: Math.max(seqRef.current, pendingSeqRef.current),
            limit: 100,
            ...filtersRef.current,
          };
      const result: LogQueryResult = await LogApi.events(params);
      setFileSize(result.fileSize);

      if (reset) {
        // 首次/过滤变化：直接替换（服务端已按 seq 降序）
        setEvents(result.events);
        setTotalCount(result.events.length);
        pendingRef.current = [];
        setPendingCount(0);
        seqRef.current = result.latestSeq;
        pendingSeqRef.current = 0;
      } else {
        // 增量：放入 pending，等用户接受
        if (result.events.length > 0) {
          const known = new Set(pendingRef.current.map(e => e.seq));
          const fresh = result.events.filter(e => !known.has(e.seq));
          if (fresh.length > 0) {
            pendingRef.current.push(...fresh);
            setPendingCount(pendingRef.current.length);
          }
        }
        pendingSeqRef.current = result.latestSeq;
      }
      setLastRefresh(Date.now());
    } catch (e) {
      console.error('日志轮询失败:', e);
    }
  }, []);

  // 首次加载 + 过滤变化时重置
  useEffect(() => {
    seqRef.current = 0;
    pendingSeqRef.current = 0;
    fetchOnce(true);
  }, [filters.level, filters.eventCode, filters.traceId, filters.keyword, fetchOnce]);

  // 轮询定时器
  useEffect(() => {
    if (!polling) return;
    const timer = setInterval(() => {
      if (document.hidden) return; // 页面不可见时跳过本次
      fetchOnce(false);
    }, 2500);
    return () => clearInterval(timer);
  }, [polling, fetchOnce]);

  // 页面可见性变化：可见时立即拉一次
  useEffect(() => {
    const handler = () => {
      if (!document.hidden && pollingRef.current) {
        fetchOnce(false);
      }
    };
    document.addEventListener('visibilitychange', handler);
    return () => document.removeEventListener('visibilitychange', handler);
  }, [fetchOnce]);

  // 用户接受 pending 事件：按 seq 归并（seq 序即时间序），最新在前
  const acceptPending = useCallback(() => {
    if (pendingRef.current.length === 0) return;
    setEvents(prev => {
      const existing = new Set(prev.map(e => e.seq));
      const fresh = pendingRef.current.filter(e => !existing.has(e.seq));
      const merged = [...fresh, ...prev].sort((a, b) => b.seq - a.seq);
      setTotalCount(merged.length);
      return merged;
    });
    // 接受的 seq 推进到正式游标
    seqRef.current = Math.max(seqRef.current, pendingSeqRef.current);
    pendingRef.current = [];
    setPendingCount(0);
  }, []);

  return {
    events,
    fileSize,
    totalCount,
    polling,
    setPolling,
    lastRefresh,
    pendingCount,
    acceptPending,
    refresh: () => fetchOnce(false),
  };
}
