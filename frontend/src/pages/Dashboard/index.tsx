import { lazy, Suspense, useEffect, useState } from 'react';
import { LogApi, type LogStats } from '../../api';
import Sidebar from '../../components/Sidebar';
import { useLogPolling } from './useLogPolling';
import './Dashboard.css';

// 子视图懒加载
const LogsExplorer = lazy(() => import('./LogsExplorer'));
const TracePanel = lazy(() => import('./TracePanel'));
const LlmCallsPanel = lazy(() => import('./LlmCallsPanel'));

type Tab = 'logs' | 'traces' | 'llm';

const STATS_COLLAPSED_KEY = 'dashboard.statsCollapsed';
const HEAD_COLLAPSED_KEY = 'dashboard.headCollapsed';

interface Filters {
  level: string;
  eventCode: string;
  traceId: string;
  keyword: string;
}

export default function DashboardPage() {
  const [tab, setTab] = useState<Tab>('logs');
  const [stats, setStats] = useState<LogStats | null>(null);
  const [filters, setFilters] = useState<Filters>({ level: '', eventCode: '', traceId: '', keyword: '' });
  const [statsCollapsed, setStatsCollapsed] = useState<boolean>(() => {
    try { return localStorage.getItem(STATS_COLLAPSED_KEY) === '1'; } catch { return false; }
  });
  const [headCollapsed, setHeadCollapsed] = useState<boolean>(() => {
    try { return localStorage.getItem(HEAD_COLLAPSED_KEY) === '1'; } catch { return false; }
  });

  const polling = useLogPolling(filters);

  // 加载统计
  const refreshStats = async () => {
    try {
      const s = await LogApi.stats();
      setStats(s);
    } catch (e) { console.error('stats 加载失败', e); }
  };

  useEffect(() => {
    refreshStats();
    const t = setInterval(refreshStats, 5000);
    return () => clearInterval(t);
  }, []);

  const onFilterChange = (patch: Partial<Filters>) => {
    setFilters(prev => ({ ...prev, ...patch }));
  };

  const toggleStatsCollapsed = () => {
    setStatsCollapsed(prev => {
      const next = !prev;
      try { localStorage.setItem(STATS_COLLAPSED_KEY, next ? '1' : '0'); } catch { /* 忽略持久化失败 */ }
      return next;
    });
  };

  const toggleHeadCollapsed = () => {
    setHeadCollapsed(prev => {
      const next = !prev;
      try { localStorage.setItem(HEAD_COLLAPSED_KEY, next ? '1' : '0'); } catch { /* 忽略持久化失败 */ }
      return next;
    });
  };

  // 收起页头时仍需可见：当前生效的筛选条件摘要
  const activeFilters = [filters.level, filters.eventCode, filters.traceId, filters.keyword].filter(Boolean);

  return (
    <div className="app-shell">
      <Sidebar />

      <section className="dashboard">
        {/* 页首：editorial 风格（eyebrow + serif 标题 + 过滤操作行），支持收起成紧凑条 */}
        <header className={`page__head${headCollapsed ? ' page__head--collapsed' : ''}`}>
          <div className="page__head-collapsible">
            <div className="page__head-collapsible-inner">
              <div className="page__head-meta">
                <span className="page__eyebrow">Observability</span>
                <span className="page__head-rule" aria-hidden />
                <span className="page__head-id">{polling.polling ? 'LIVE' : 'PAUSED'}</span>
              </div>
              <div className="page__head-main">
                <div className="page__head-title-row">
                  <h1 className="page__title-serif">仪表盘</h1>
                  <div className="page__head-cta-group">
                    <span className="dashboard__live">
                      <span className={`dashboard__pulse${polling.polling ? ' dashboard__pulse--on' : ''}`} />
                      {polling.polling ? '实时' : '已暂停'}
                    </span>
                    <button
                      className="btn btn--ghost page__head-cta"
                      onClick={() => polling.setPolling(!polling.polling)}
                    >
                      {polling.polling ? '暂停' : '恢复'}
                    </button>
                    <button
                      className="btn btn--ghost page__head-cta dashboard__collapse-btn"
                      onClick={toggleHeadCollapsed}
                      aria-expanded={!headCollapsed}
                      title="收起页头"
                    >
                      <span className="dashboard__stats-chevron dashboard__stats-chevron--open">›</span>
                    </button>
                  </div>
                </div>
                <p className="page__lead">实时采集 logs/dingring.log，检索日志事件、追踪群讨论流程与 LLM 调用</p>
              </div>
              <div className="page__head-actions">
                <select
                  className="page__head-select"
                  value={filters.level}
                  onChange={e => onFilterChange({ level: e.target.value })}
                >
                  <option value="">全部级别</option>
                  <option value="ERROR">ERROR</option>
                  <option value="WARN">WARN</option>
                  <option value="INFO">INFO</option>
                </select>
                <input
                  className="dashboard__filter-input"
                  type="text"
                  placeholder="事件码（如 CHAT_RESPONSE）"
                  value={filters.eventCode}
                  onChange={e => onFilterChange({ eventCode: e.target.value })}
                />
                <input
                  className="dashboard__filter-input"
                  type="text"
                  placeholder="traceId"
                  value={filters.traceId}
                  onChange={e => onFilterChange({ traceId: e.target.value })}
                />
                <input
                  className="dashboard__filter-input dashboard__filter-input--wide"
                  type="text"
                  placeholder="关键字搜索"
                  value={filters.keyword}
                  onChange={e => onFilterChange({ keyword: e.target.value })}
                />
              </div>
            </div>
          </div>

          {/* 收起态：紧凑条（标题 + 实时状态 + 筛选摘要 + 暂停/恢复） */}
          <div className="page__head-compact">
            <button className="page__head-compact-expand" onClick={toggleHeadCollapsed} aria-expanded={!headCollapsed} title="展开页头">
              <span className="dashboard__stats-chevron">›</span>
              <span className="page__head-compact-title">仪表盘</span>
              <span className="page__head-compact-eyebrow">Observability</span>
            </button>
            <span className="dashboard__live">
              <span className={`dashboard__pulse${polling.polling ? ' dashboard__pulse--on' : ''}`} />
              {polling.polling ? '实时' : '已暂停'}
            </span>
            {activeFilters.length > 0 && (
              <button className="page__head-compact-filters" onClick={toggleHeadCollapsed} title={activeFilters.join(' · ')}>
                筛选生效 {activeFilters.length} 项
              </button>
            )}
            <button
              className="btn btn--ghost page__head-compact-pause"
              onClick={() => polling.setPolling(!polling.polling)}
            >
              {polling.polling ? '暂停' : '恢复'}
            </button>
          </div>
        </header>

        {/* 统计概览条：可收起（收起后保留一行紧凑数字；卡片复用全局 editorial stat-card） */}
        <div className={`dashboard__stats${statsCollapsed ? ' dashboard__stats--collapsed' : ''}`}>
          <button
            className="dashboard__stats-head"
            onClick={toggleStatsCollapsed}
            aria-expanded={!statsCollapsed}
            title={statsCollapsed ? '展开统计概览' : '收起统计概览'}
          >
            <span className="dashboard__stats-head-label">统计概览</span>
            <span className="dashboard__stats-head-summary">
              <SummaryItem value={(stats?.total ?? 0).toLocaleString()} label="日志总数" />
              <SummaryItem value={(stats?.errorCount ?? 0).toLocaleString()} label="ERROR" tone="error" />
              <SummaryItem value={(stats?.warnCount ?? 0).toLocaleString()} label="WARN" tone="warn" />
              <SummaryItem value={(stats?.llmCallCount ?? 0).toLocaleString()} label={`LLM 调用 · avg ${stats?.avgLatencyMs ?? 0}ms`} />
              <SummaryItem value={(stats?.totalTokens ?? 0).toLocaleString()} label="Token" />
            </span>
            <span className={`dashboard__stats-chevron${statsCollapsed ? '' : ' dashboard__stats-chevron--open'}`}>›</span>
          </button>
          <div className="dashboard__stats-panels">
            <div className="dashboard__stats-panels-inner">
              <div className="stat-card">
                <span className="stat-card__num">{(stats?.total ?? 0).toLocaleString()}</span>
                <span className="stat-card__label">日志总数</span>
                <span className="stat-card__bar" aria-hidden />
              </div>
              <div className="stat-card">
                <span className="stat-card__num stat-card__num--error">{(stats?.errorCount ?? 0).toLocaleString()}</span>
                <span className="stat-card__label">ERROR</span>
                <span className="stat-card__bar stat-card__bar--error" aria-hidden />
              </div>
              <div className="stat-card">
                <span className="stat-card__num stat-card__num--warn">{(stats?.warnCount ?? 0).toLocaleString()}</span>
                <span className="stat-card__label">WARN</span>
                <span className="stat-card__bar stat-card__bar--amber" aria-hidden />
              </div>
              <div className="stat-card">
                <span className="stat-card__num">{(stats?.llmCallCount ?? 0).toLocaleString()}</span>
                <span className="stat-card__label">LLM 调用 · avg {stats?.avgLatencyMs ?? 0}ms</span>
                <span className="stat-card__bar stat-card__bar--accent" aria-hidden />
              </div>
              <div className="stat-card">
                <span className="stat-card__num">{(stats?.totalTokens ?? 0).toLocaleString()}</span>
                <span className="stat-card__label">Token 总量</span>
                <span className="stat-card__bar stat-card__bar--violet" aria-hidden />
              </div>
            </div>
          </div>
        </div>

        {/* Tab 切换 */}
        <nav className="dashboard__tabs">
          <TabBtn active={tab === 'logs'} onClick={() => setTab('logs')} label="日志检索" />
          <TabBtn active={tab === 'traces'} onClick={() => setTab('traces')} label="Trace 会话" />
          <TabBtn active={tab === 'llm'} onClick={() => setTab('llm')} label="LLM 调用" />
        </nav>

        {/* 主内容区 */}
        <main className="dashboard__body">
          <Suspense fallback={<div className="route-loading"><span className="route-loading__spinner" /></div>}>
            {tab === 'logs' && (
              <LogsExplorer
                events={polling.events}
                pendingCount={polling.pendingCount}
                onAcceptPending={polling.acceptPending}
              />
            )}
            {tab === 'traces' && <TracePanel />}
            {tab === 'llm' && <LlmCallsPanel />}
          </Suspense>
        </main>

        {/* 底部状态条 */}
        <footer className="dashboard__footer">
          <span>已加载 {polling.totalCount.toLocaleString()} 条 · 日志文件 {formatBytes(polling.fileSize)}</span>
          <span>上次刷新 {formatTime(polling.lastRefresh)}</span>
        </footer>
      </section>
    </div>
  );
}

function TabBtn({ active, onClick, label }: { active: boolean; onClick: () => void; label: string }) {
  return (
    <button className={`dashboard__tab${active ? ' dashboard__tab--active' : ''}`} onClick={onClick}>
      {label}
    </button>
  );
}

function SummaryItem({ value, label, tone }: { value: string; label: string; tone?: 'error' | 'warn' }) {
  const toneClass = tone === 'error' ? ' dashboard__stats-summary-item--error' : tone === 'warn' ? ' dashboard__stats-summary-item--warn' : '';
  return (
    <span className={`dashboard__stats-summary-item${toneClass}`}>
      <span className="dashboard__stats-summary-value">{value}</span>
      <span className="dashboard__stats-summary-label">{label}</span>
    </span>
  );
}

function formatBytes(n: number): string {
  if (n < 1024) return `${n}B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)}KB`;
  return `${(n / 1024 / 1024).toFixed(1)}MB`;
}

function formatTime(ts: number): string {
  const d = new Date(ts);
  return `${d.getHours().toString().padStart(2, '0')}:${d.getMinutes().toString().padStart(2, '0')}:${d.getSeconds().toString().padStart(2, '0')}`;
}
