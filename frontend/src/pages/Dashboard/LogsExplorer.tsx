import { useMemo, useRef, useState } from 'react';
import type { LogEvent } from '../../api';
import './Dashboard.css';

interface Props {
  events: LogEvent[];
  pendingCount: number;
  onAcceptPending: () => void;
}

const OVERSCAN = 8;
const LINE_HEIGHT = 28; // 单行紧凑高度
const MAX_INLINE_BYTES = 1024 * 2; // 2KB 内联展示
const MAX_FORMATTABLE_BYTES = 1024 * 100; // 100KB 内才格式化

export default function LogsExplorer({ events, pendingCount, onAcceptPending }: Props) {
  const [expandedSeq, setExpandedSeq] = useState<number | null>(null);
  const scrollRef = useRef<HTMLDivElement>(null);

  // 虚拟滚动（仅在没有展开行时启用；展开详情行高不固定，会破坏定高虚拟滚动的偏移计算，
  // 因此展开时退化为普通流式渲染——可见行数有限，性能可接受）
  const expandedEvent = expandedSeq != null ? events.find(e => e.seq === expandedSeq) : undefined;
  const useVirtual = expandedSeq == null;

  const { visibleItems, totalHeight, offsetY } = useMemo(() => {
    if (!useVirtual) {
      return { visibleItems: events, totalHeight: 0, offsetY: 0 };
    }
    const total = events.length;
    const containerH = (scrollRef.current?.clientHeight ?? 800);
    const scrollTop = scrollRef.current?.scrollTop ?? 0;
    const start = Math.max(0, Math.floor(scrollTop / LINE_HEIGHT) - OVERSCAN);
    const end = Math.min(total, Math.ceil((scrollTop + containerH) / LINE_HEIGHT) + OVERSCAN);
    return {
      visibleItems: events.slice(start, end),
      totalHeight: total * LINE_HEIGHT,
      offsetY: start * LINE_HEIGHT,
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [events, useVirtual, scrollRef.current?.scrollTop, scrollRef.current?.clientHeight]);

  // 触发重渲染用
  const [, forceTick] = useState(0);
  const onScroll = () => forceTick(v => v + 1);

  const toggle = (seq: number) => setExpandedSeq(expandedSeq === seq ? null : seq);

  return (
    <div className="logs-explorer">
      {pendingCount > 0 && (
        <div className="new-logs-banner" onClick={onAcceptPending}>
          ↓ {pendingCount} 条新日志，点击插入
        </div>
      )}
      <div className="logs-explorer__list" ref={scrollRef} onScroll={onScroll}>
        {useVirtual ? (
          <div style={{ height: totalHeight, position: 'relative' }}>
            <div style={{ transform: `translateY(${offsetY}px)` }}>
              {visibleItems.map(e => (
                <LogRow key={e.seq} event={e} expanded={false} onToggle={() => toggle(e.seq)} />
              ))}
            </div>
          </div>
        ) : (
          // 展开模式：正常流式布局，行高由内容决定，详情块不再被裁剪
          <div>
            {events.map(e => (
              <LogRow
                key={e.seq}
                event={e}
                expanded={expandedSeq === e.seq}
                onToggle={() => toggle(e.seq)}
                detailEvent={expandedSeq === e.seq ? expandedEvent : undefined}
              />
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

function LogRow({ event, expanded, onToggle, detailEvent }: {
  event: LogEvent;
  expanded: boolean;
  onToggle: () => void;
  detailEvent?: LogEvent;
}) {
  const isError = event.level === 'ERROR';
  const isWarn = event.level === 'WARN';
  return (
    <div className={`log-row${isError ? ' log-row--error' : ''}${isWarn ? ' log-row--warn' : ''}${expanded ? ' log-row--expanded' : ''}`}>
      <div className="log-row__line" onClick={onToggle}>
        <span className="log-row__time">{formatTs(event.timestamp)}</span>
        <span className={`log-row__level log-row__level--${event.level.toLowerCase()}`}>{event.level}</span>
        <span className="log-row__trace">{event.traceId || '-'}</span>
        {event.eventCode && (
          <span className="log-row__code">{event.eventCode}</span>
        )}
        {event.costMs != null && (
          <span className={`log-row__cost${event.costMs > 1000 ? ' log-row__cost--slow' : ''}`}>
            {event.costMs}ms
          </span>
        )}
        <span className="log-row__summary">{event.summary}</span>
        <span className={`log-row__chevron${expanded ? ' log-row__chevron--open' : ''}`}>›</span>
      </div>
      {expanded && detailEvent && <LogDetail event={detailEvent} />}
    </div>
  );
}

function LogDetail({ event }: { event: LogEvent }) {
  const fields = event.fields || {};
  const hasFields = Object.keys(fields).length > 0;
  const [showFull, setShowFull] = useState(false);
  const messageBytes = new Blob([event.message]).size;
  const tooLarge = messageBytes > MAX_FORMATTABLE_BYTES;

  // 详情字段：只展示行首未覆盖/需完整值的元数据，避免与行首重复
  const metaFields: Array<[string, string]> = [
    ['time', formatTs(event.timestamp)],
    ['source', event.source],
    ['eventName', event.eventName],
    ['traceId', event.traceId],
    ['groupId', event.groupId || ''],
    ['thread', event.thread],
    ['logger', event.logger],
  ];
  if (event.costMs != null) metaFields.push(['cost', `${event.costMs}ms`]);
  if (fields.latencyMs != null) metaFields.push(['latency', `${fields.latencyMs}ms`]);
  if (fields.model != null) metaFields.push(['model', String(fields.model)]);
  if (fields.agent != null) metaFields.push(['agent', String(fields.agent)]);
  if (fields.node != null) metaFields.push(['node', String(fields.node)]);
  if (fields.nodeName != null) metaFields.push(['nodeName', String(fields.nodeName)]);
  if (fields.status != null) metaFields.push(['status', String(fields.status)]);
  if (fields.elapsedMs != null) metaFields.push(['elapsedMs', String(fields.elapsedMs)]);
  if (fields.promptTokens != null) metaFields.push(['promptTokens', String(fields.promptTokens)]);
  if (fields.completionTokens != null) metaFields.push(['completionTokens', String(fields.completionTokens)]);
  if (fields.totalTokens != null) metaFields.push(['totalTokens', String(fields.totalTokens)]);

  return (
    <div className="log-detail">
      <div className="log-detail__fields">
        {metaFields.map(([label, value]) => (
          <FieldRow key={label} label={label} value={value} />
        ))}
      </div>

      {hasFields && (
        <div className="log-detail__section">
          <div className="log-detail__section-label">分区字段</div>
          {fields.request != null && (
            <JsonBlock label="request" content={String(fields.request)} showFull={showFull} tooLarge={tooLarge} onToggle={() => setShowFull(!showFull)} />
          )}
          {fields.result != null && (
            <JsonBlock label="result" content={String(fields.result)} showFull={showFull} tooLarge={tooLarge} onToggle={() => setShowFull(!showFull)} />
          )}
        </div>
      )}

      <div className="log-detail__section">
        <div className="log-detail__section-label">完整消息</div>
        <JsonBlock label="message" content={event.message} showFull={showFull} tooLarge={tooLarge} onToggle={() => setShowFull(!showFull)} />
      </div>
    </div>
  );
}

function FieldRow({ label, value }: { label: string; value: string }) {
  if (!value || value === '-') return null;
  return (
    <div className="log-detail__field">
      <span className="log-detail__field-label">{label}</span>
      <span className="log-detail__field-value" title={value}>{value}</span>
    </div>
  );
}

function JsonBlock({ label, content, showFull, tooLarge, onToggle }: {
  label: string;
  content: string;
  showFull: boolean;
  tooLarge: boolean;
  onToggle: () => void;
}) {
  const bytes = new Blob([content]).size;
  const inline = bytes < MAX_INLINE_BYTES;

  if (!showFull) {
    return (
      <div className="json-block">
        <div className="json-block__header">
          <span className="json-block__label">{label}</span>
          <span className="json-block__size">{formatBytes(bytes)}</span>
          <button className="json-block__btn" onClick={onToggle}>
            {tooLarge ? `内容较大，谨慎展开 (${formatBytes(bytes)})` : '展开'}
          </button>
        </div>
        <pre className="json-block__preview">{content.slice(0, 200)}{content.length > 200 ? '...' : ''}</pre>
      </div>
    );
  }

  let formatted = content;
  if (!tooLarge) {
    try {
      const obj = JSON.parse(content);
      formatted = JSON.stringify(obj, null, 2);
    } catch {
      // 非 JSON 保持原文
    }
  }

  return (
    <div className="json-block">
      <div className="json-block__header">
        <span className="json-block__label">{label}</span>
        <span className="json-block__size">{formatBytes(bytes)}</span>
        <button className="json-block__btn" onClick={onToggle}>收起</button>
      </div>
      <pre className={`json-block__content${inline ? ' json-block__content--inline' : ' json-block__content--scroll'}`}>
        {formatted}
      </pre>
    </div>
  );
}

function formatTs(ts: number): string {
  const d = new Date(ts);
  return `${d.getHours().toString().padStart(2, '0')}:${d.getMinutes().toString().padStart(2, '0')}:${d.getSeconds().toString().padStart(2, '0')}.${d.getMilliseconds().toString().padStart(3, '0')}`;
}

function formatBytes(n: number): string {
  if (n < 1024) return `${n}B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)}KB`;
  return `${(n / 1024 / 1024).toFixed(1)}MB`;
}
