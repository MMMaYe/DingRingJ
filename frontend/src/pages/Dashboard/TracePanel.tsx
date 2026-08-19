import { useCallback, useEffect, useRef, useState } from 'react';
import { LogApi, type LogEvent, type TraceDetail, type TraceSummary } from '../../api';
import './Dashboard.css';

export default function TracePanel() {
  const [traces, setTraces] = useState<TraceSummary[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [detail, setDetail] = useState<TraceDetail | null>(null);
  const [groupFilter, setGroupFilter] = useState('');

  // ref 同步选中项，避免定时刷新闭包捕获过期值
  const selectedRef = useRef<string | null>(null);
  selectedRef.current = selectedId;

  const refreshDetail = useCallback(async (traceId: string) => {
    try {
      setDetail(await LogApi.traceDetail(traceId));
    } catch (e) {
      console.error('traceDetail', e);
    }
  }, []);

  const refreshTraces = useCallback(async () => {
    try {
      // 时间降序：API 已返回倒序，前端直接用
      const list = await LogApi.traces(groupFilter.trim() || undefined);
      setTraces(list);
      const current = selectedRef.current;
      if (!current) {
        // 首次自动选中最新一条
        if (list.length > 0) setSelectedId(list[0].traceId);
        return;
      }
      if (list.some(t => t.traceId === current)) {
        // trace 可能仍在进行中：详情随列表一起刷新，事件流实时增长
        refreshDetail(current);
      } else {
        setDetail(null);
      }
    } catch (e) {
      console.error('traces 加载失败', e);
    }
  }, [groupFilter, refreshDetail]);

  useEffect(() => {
    refreshTraces();
    const t = setInterval(refreshTraces, 5000);
    return () => clearInterval(t);
  }, [refreshTraces]);

  useEffect(() => {
    if (selectedId) refreshDetail(selectedId);
  }, [selectedId, refreshDetail]);

  return (
    <div className="trace-panel">
      <div className="trace-panel__list">
        <div className="trace-panel__header">
          <span>Trace 会话</span>
          <input
            className="trace-panel__group-filter"
            placeholder="群ID"
            value={groupFilter}
            onChange={e => setGroupFilter(e.target.value)}
          />
          <span className="trace-panel__count">{traces.length}</span>
        </div>
        <div className="trace-panel__items">
          {traces.length === 0 && (
            <div className="trace-panel__empty">暂无 trace 记录</div>
          )}
          {traces.map(t => (
            <div
              key={t.traceId}
              className={`trace-card${selectedId === t.traceId ? ' trace-card--active' : ''}`}
              onClick={() => setSelectedId(t.traceId)}
            >
              <div className="trace-card__row1">
                <span className="trace-card__group">G{t.groupId}</span>
                <span className="trace-card__duration">{formatDuration(t.endTimestamp - t.startTimestamp)}</span>
                {t.errorCount > 0 && (
                  <span className="trace-card__error">{t.errorCount} err</span>
                )}
              </div>
              {t.title && <span className="trace-card__title" title={t.title}>{t.title}</span>}
              <span className="trace-card__trace-id" title={t.traceId}>{t.traceId}</span>
              <div className="trace-card__row3">
                <span>{t.eventCount} 事件</span>
                {t.llmCount > 0 && <span>{t.llmCount} LLM</span>}
                {t.maxCost != null && (
                  <span className="trace-card__cost">最慢 {t.maxCost}ms</span>
                )}
                <span style={{ marginLeft: 'auto' }}>{formatTs(t.endTimestamp)}</span>
              </div>
            </div>
          ))}
        </div>
      </div>

      <div className="trace-panel__detail">
        {detail ? <TraceDetailView detail={detail} /> : (
          <div className="trace-panel__empty">选择左侧 trace 查看详情</div>
        )}
      </div>
    </div>
  );
}

function TraceDetailView({ detail }: { detail: TraceDetail }) {
  return (
    <div className="trace-detail">
      {detail.relatedEntries.length > 0 && (
        <div className="trace-detail__related">
          <div className="trace-detail__section-label">关联入口事件（用户消息入队，跨线程弱关联）</div>
          {detail.relatedEntries.map(e => (
            <div key={e.seq} className="trace-detail__related-row">
              <span className="trace-detail__related-time">{formatTs(e.timestamp)}</span>
              <span className={`log-row__level log-row__level--${e.level.toLowerCase()}`}>{e.level}</span>
              <span className="trace-detail__related-summary" title={e.summary}>{e.summary}</span>
            </div>
          ))}
        </div>
      )}

      {detail.llmCalls.length > 0 && (
        <div>
          <div className="trace-detail__section-label">LLM 调用（{detail.llmCalls.length}）</div>
          <div className="trace-detail__llm">
            {detail.llmCalls.map((c, i) => (
              <span key={`${c.seq}-${i}`} className="trace-detail__llm-chip">
                <b>{c.agent || '-'}</b>
                <span>{c.model || '-'}</span>
                <i>{c.latencyMs}ms</i>
                {c.totalTokens != null && <span>{c.totalTokens} tok</span>}
              </span>
            ))}
          </div>
        </div>
      )}

      <div>
        <div className="trace-detail__section-label">引擎处理事件流（{detail.events.length}）</div>
        <div className="trace-detail__flow">
          {detail.events.map((e, idx) => (
            <FlowEvent key={e.seq} event={e} isLast={idx === detail.events.length - 1} />
          ))}
        </div>
      </div>
    </div>
  );
}

function FlowEvent({ event: e, isLast }: { event: LogEvent; isLast: boolean }) {
  const [expanded, setExpanded] = useState(false);
  return (
    <div className="flow-event">
      <div className={`flow-event__dot${e.level === 'ERROR' ? ' flow-event__dot--error'
        : e.level === 'WARN' ? ' flow-event__dot--warn' : ''}
        ${e.eventCode === 'CHAT_RESPONSE' ? ' flow-event__dot--llm' : ''}`} />
      {!isLast && <div className="flow-event__line" />}
      <div className="flow-event__body">
        <div className="flow-event__header">
          <span className="flow-event__time">{formatTs(e.timestamp)}</span>
          <span className={`log-row__level log-row__level--${e.level.toLowerCase()}`}>{e.level}</span>
          {e.eventCode && <span className="log-row__code">{e.eventCode}</span>}
          {e.costMs != null && <span className="flow-event__cost">{e.costMs}ms</span>}
        </div>
        <div className="flow-event__summary" onClick={() => setExpanded(!expanded)} title="点击展开全文">
          {e.summary}
        </div>
        {expanded && <pre className="flow-event__message">{e.message}</pre>}
      </div>
    </div>
  );
}

function formatTs(ts: number): string {
  const d = new Date(ts);
  return `${d.getHours().toString().padStart(2, '0')}:${d.getMinutes().toString().padStart(2, '0')}:${d.getSeconds().toString().padStart(2, '0')}.${d.getMilliseconds().toString().padStart(3, '0')}`;
}

function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  return `${Math.floor(ms / 60000)}m${Math.round((ms % 60000) / 1000)}s`;
}
