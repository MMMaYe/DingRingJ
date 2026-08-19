import { Fragment, useEffect, useState } from 'react';
import { LogApi, type LlmCall } from '../../api';
import './Dashboard.css';

export default function LlmCallsPanel() {
  const [calls, setCalls] = useState<LlmCall[]>([]);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());

  const refresh = async () => {
    try {
      const list = await LogApi.llmCalls();
      setCalls(list);
    } catch (e) {
      console.error('llmCalls 加载失败', e);
    }
  };

  useEffect(() => {
    refresh();
    const t = setInterval(refresh, 5000);
    return () => clearInterval(t);
  }, []);

  const maxLatency = Math.max(1, ...calls.map(c => c.latencyMs));

  const toggleExpand = (key: string) => {
    setExpanded(prev => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  };

  if (calls.length === 0) {
    return <div className="llm-panel"><div className="llm-panel__empty">暂无 LLM 调用记录</div></div>;
  }

  return (
    <div className="llm-panel">
      <table className="llm-table">
        <thead>
          <tr>
            <th>Agent</th>
            <th>Model</th>
            <th>Latency</th>
            <th>Prompt T</th>
            <th>Completion T</th>
            <th>Total T</th>
            <th>Trace</th>
            <th>Time</th>
          </tr>
        </thead>
        <tbody>
          {calls.map((c, idx) => {
            const key = `${c.seq}-${idx}`;
            const expandedRow = expanded.has(key);
            const barWidth = Math.max(2, (c.latencyMs / maxLatency) * 80);
            return (
              <Fragment key={key}>
                <tr onClick={() => toggleExpand(key)} style={{ cursor: 'pointer' }}>
                  <td className="llm-table__agent">{c.agent || '-'}</td>
                  <td className="llm-table__model">{c.model || '-'}</td>
                  <td>
                    {c.latencyMs}ms
                    <span className="llm-table__latency-bar" style={{ width: `${barWidth}px` }} />
                  </td>
                  <td>{c.promptTokens ?? '-'}</td>
                  <td>{c.completionTokens ?? '-'}</td>
                  <td>{c.totalTokens ?? '-'}</td>
                  <td className="llm-table__muted">{c.traceId ? `${c.traceId.slice(0, 20)}...` : '-'}</td>
                  <td className="llm-table__muted">{formatTs(c.timestamp)}</td>
                </tr>
                {expandedRow && (
                  <tr>
                    <td colSpan={8} className="llm-table__expand">
                      <div className="llm-table__expand-grid">
                        <div>
                          <span className="llm-table__expand-label">traceId: </span>{c.traceId || '-'}
                        </div>
                        <div>
                          <span className="llm-table__expand-label">agent: </span>{c.agent || '-'}
                          <span className="llm-table__expand-label" style={{ marginLeft: '16px' }}>model: </span>{c.model || '-'}
                        </div>
                        <div>
                          <span className="llm-table__expand-label">耗时: </span>{c.latencyMs}ms
                          {c.totalTokens != null && (
                            <span style={{ marginLeft: '16px' }}>
                              Token: {c.promptTokens ?? '?'} + {c.completionTokens ?? '?'} = {c.totalTokens}
                            </span>
                          )}
                        </div>
                        <div className="llm-table__expand-hint">
                          prompt/响应全文请切换到「日志检索」Tab，按 traceId 过滤查看
                        </div>
                      </div>
                    </td>
                  </tr>
                )}
              </Fragment>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function formatTs(ts: number): string {
  const d = new Date(ts);
  return `${d.getHours().toString().padStart(2, '0')}:${d.getMinutes().toString().padStart(2, '0')}:${d.getSeconds().toString().padStart(2, '0')}`;
}
