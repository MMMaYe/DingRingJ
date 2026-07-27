import { useState, useEffect, useCallback, useMemo } from 'react';
import Sidebar from '../../components/Sidebar';
import Modal from '../../components/Modal';
import { toast } from '../../components/Toast';
import { API } from '../../api';
import type { GroupSummary, TopicSummary, KnowledgeCardDTO } from '../../types';
import './style.css';

/** 知识库聚合视图：每个群组对应一个知识库，汇总其已关闭主题下的卡片 */
interface KbAggregate {
  groupId: number;
  groupName: string;
  cardCount: number;
  topicCount: number;
  categories: string[];
  lastTime: string | null;
  cards: KnowledgeCardDTO[];
}

type FilterTab = 'all' | 'filled' | 'empty';

export default function KBPage() {
  const [aggregates, setAggregates] = useState<KbAggregate[]>([]);
  const [loading, setLoading] = useState(true);
  const [keyword, setKeyword] = useState('');
  const [filter, setFilter] = useState<FilterTab>('all');
  const [detailKb, setDetailKb] = useState<KbAggregate | null>(null);

  // 加载所有数据：群组 → 各群主题 → 全部卡片，聚合为知识库视图
  const loadAll = useCallback(async () => {
    setLoading(true);
    try {
      const groupList = await API.get<GroupSummary[]>('/api/groups');

      // 并发拉取每个群的主题列表
      const topicsByGroup = await Promise.all(
        groupList.map(g => API.get<TopicSummary[]>(`/api/groups/${g.id}/topics`).catch(() => []))
      );

      // 拉取全部卡片（按主题归类）
      const allCards = await API.get<KnowledgeCardDTO[]>('/api/cards').catch(() => [] as KnowledgeCardDTO[]);
      const cardsByTopic = new Map<number, KnowledgeCardDTO[]>();
      for (const c of allCards) {
        const arr = cardsByTopic.get(c.topicId) || [];
        arr.push(c);
        cardsByTopic.set(c.topicId, arr);
      }

      // 聚合：群 → 已关闭主题 → 卡片
      const agg: KbAggregate[] = groupList.map((g, i) => {
        const topics = topicsByGroup[i] || [];
        const closedTopics = topics.filter(t => t.status === 'CLOSED');
        const groupCards: KnowledgeCardDTO[] = [];
        for (const t of closedTopics) {
          const tc = cardsByTopic.get(t.id);
          if (tc) groupCards.push(...tc);
        }
        const cats = [...new Set(groupCards.map(c => c.category).filter(Boolean))];
        const times = [g.lastMessageTime, ...closedTopics.map(t => t.createTime)].filter(Boolean) as string[];
        return {
          groupId: g.id,
          groupName: g.name,
          cardCount: groupCards.length,
          topicCount: closedTopics.length,
          categories: cats,
          lastTime: times.length ? times.sort().reverse()[0] : null,
          cards: groupCards,
        };
      });
      setAggregates(agg);
    } catch (e: any) {
      toast(e.message || '加载失败', 'error');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { loadAll(); }, [loadAll]);

  // 搜索 + 筛选
  const filtered = useMemo(() => {
    let list = aggregates;
    if (filter === 'filled') list = list.filter(a => a.cardCount > 0);
    else if (filter === 'empty') list = list.filter(a => a.cardCount === 0);
    const kw = keyword.trim().toLowerCase();
    if (kw) list = list.filter(a => a.groupName.toLowerCase().includes(kw) || a.categories.some(c => c.toLowerCase().includes(kw)));
    return list;
  }, [aggregates, filter, keyword]);

  const stats = useMemo(() => ({
    total: aggregates.length,
    filled: aggregates.filter(a => a.cardCount > 0).length,
    cards: aggregates.reduce((s, a) => s + a.cardCount, 0),
    topics: aggregates.reduce((s, a) => s + a.topicCount, 0),
  }), [aggregates]);

  function formatTime(iso: string | null) {
    if (!iso) return '—';
    const d = new Date(iso);
    if (isNaN(d.getTime())) return '—';
    return d.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
  }

  return (
    <div className="app-shell">
      <Sidebar>
        <div className="sidebar__label">知识库</div>
        <div className="kb-side-hint">
          知识库按群组自动聚合<br />讨论结束并生成卡片后自动收录
        </div>
      </Sidebar>

      <section className="page page--editorial">
        {/* editorial 页首 */}
        <header className="page__head">
          <div className="page__head-meta">
            <span className="page__eyebrow">Knowledge Base</span>
            <span className="page__head-rule" aria-hidden />
            <span className="page__head-id">No. {String(stats.total).padStart(2, '0')}</span>
          </div>
          <div className="page__head-main">
            <div className="page__head-title-row">
              <h1 className="page__title-serif">知识库管理</h1>
            </div>
            <p className="page__lead">按群组聚合知识卡片，沉淀每一次学习讨论的成果</p>
          </div>
          <div className="page__head-actions">
            <div className="page__search">
              <svg className="page__search-icon" width="14" height="14" viewBox="0 0 14 14" fill="none"><circle cx="6" cy="6" r="4" stroke="currentColor" strokeWidth="1.2"/><path d="M9.5 9.5L12.5 12.5" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round"/></svg>
              <input
                type="text"
                className="page__search-input"
                placeholder="搜索知识库（名称 / 分类）..."
                value={keyword}
                onChange={e => setKeyword(e.target.value)}
              />
            </div>
          </div>
        </header>

        {/* 筛选 tab：editorial 风格 */}
        <nav className="kb-filter-bar kb-filter-bar--editorial">
          <button className={`kb-filter-tab${filter === 'all' ? ' is-active' : ''}`} onClick={() => setFilter('all')}>
            <span className="kb-filter-tab__label">全部</span>
            <span className="kb-filter-tab__num">{aggregates.length}</span>
          </button>
          <button className={`kb-filter-tab${filter === 'filled' ? ' is-active' : ''}`} onClick={() => setFilter('filled')}>
            <span className="kb-filter-tab__label">有内容</span>
            <span className="kb-filter-tab__num">{stats.filled}</span>
          </button>
          <button className={`kb-filter-tab${filter === 'empty' ? ' is-active' : ''}`} onClick={() => setFilter('empty')}>
            <span className="kb-filter-tab__label">空</span>
            <span className="kb-filter-tab__num">{aggregates.length - stats.filled}</span>
          </button>
        </nav>

        <div className="page__body">
          {/* 统计卡片 */}
          <div className="page__stats">
            <div className="stat-card">
              <span className="stat-card__num">{stats.total}</span>
              <span className="stat-card__label">个知识库</span>
              <span className="stat-card__bar" aria-hidden />
            </div>
            <div className="stat-card">
              <span className="stat-card__num">{stats.cards}</span>
              <span className="stat-card__label">条卡片记录</span>
              <span className="stat-card__bar stat-card__bar--accent" aria-hidden />
            </div>
            <div className="stat-card">
              <span className="stat-card__num">{stats.topics}</span>
              <span className="stat-card__label">个已总结主题</span>
              <span className="stat-card__bar stat-card__bar--violet" aria-hidden />
            </div>
            <div className="stat-card">
              <span className="stat-card__num">{stats.filled}</span>
              <span className="stat-card__label">有内容</span>
              <span className="stat-card__bar stat-card__bar--amber" aria-hidden />
            </div>
          </div>

          {/* 知识库卡片列表 */}
          {loading ? (
            <div className="empty empty--editorial">
              <div className="empty__icon">⏳</div>
              <div className="empty__title">加载中…</div>
            </div>
          ) : !filtered.length ? (
            <div className="empty empty--editorial">
              <div className="empty__icon">📚</div>
              <div className="empty__title">
                {aggregates.length === 0 ? '暂无知识库' : '没有匹配的知识库'}
              </div>
              {aggregates.length === 0 && <div className="empty__hint">请先创建群组并发起讨论</div>}
            </div>
          ) : (
            <div className="kb-list">
              {filtered.map((kb, idx) => (
                <article
                  key={kb.groupId}
                  className={`kb-card kb-card--editorial${kb.cardCount > 0 ? ' kb-card--filled' : ''}`}
                  style={{ animationDelay: `${idx * 40}ms` }}
                >
                  <div className="kb-card__index" aria-hidden>{String(idx + 1).padStart(2, '0')}</div>
                  <div className="kb-card__avatar">
                    {kb.groupName.trim().charAt(0) || '?'}
                  </div>
                  <div className="kb-card__info">
                    <div className="kb-card__name">{kb.groupName}</div>
                    <div className="kb-card__desc">
                      {kb.cardCount > 0
                        ? `收录 ${kb.topicCount} 个主题讨论，共 ${kb.cardCount} 张知识卡片`
                        : '暂未生成知识卡片，发起讨论并 @专家 结束后会自动收录'}
                    </div>
                    {kb.categories.length > 0 && (
                      <div className="kb-card__tags">
                        {kb.categories.slice(0, 4).map(c => <span key={c} className="tag tag--brand">{c}</span>)}
                      </div>
                    )}
                  </div>
                  <div className="kb-card__right">
                    <div className={`kb-card__status${kb.cardCount > 0 ? ' kb-card__status--on' : ' kb-card__status--off'}`}>
                      {kb.cardCount > 0 ? '● 有内容' : '○ 空'}
                    </div>
                    <div className="kb-card__meta">
                      <span>卡片 {kb.cardCount}</span>
                      <span>主题 {kb.topicCount}</span>
                      <span>更新 {formatTime(kb.lastTime)}</span>
                    </div>
                    <div className="kb-card__actions">
                      <button className="kb-card__link" disabled={kb.cardCount === 0} onClick={() => setDetailKb(kb)}>
                        查看详情 →
                      </button>
                    </div>
                  </div>
                </article>
              ))}
            </div>
          )}
        </div>
      </section>

      {/* 详情弹窗 */}
      <Modal
        open={!!detailKb}
        onClose={() => setDetailKb(null)}
        title={`${detailKb?.groupName ?? ''} · 知识库`}
        eyebrow="Library"
        subtitle={detailKb ? `${detailKb.cardCount} 张卡片 · ${detailKb.topicCount} 个主题` : undefined}
        width={620}
        footer={<button className="btn btn--ghost" onClick={() => setDetailKb(null)}>关闭</button>}
      >
        {detailKb && (
          <>
            <div className="kb-detail-summary">
              <span className="tag tag--brand">{detailKb.cardCount} 张卡片</span>
              <span className="tag">{detailKb.topicCount} 个主题</span>
              {detailKb.categories.map(c => <span key={c} className="tag tag--neutral">{c}</span>)}
            </div>
            <div className="kb-detail-cards">
              {detailKb.cards.map(c => (
                <div key={c.id} className="kb-detail-card">
                  <div className="kb-detail-card__q">Q: {c.question}</div>
                  <div className="kb-detail-card__a">A: {c.answer}</div>
                  <div className="kb-detail-card__meta">来自「{c.topicTitle}」· {formatTime(c.createTime)}</div>
                </div>
              ))}
            </div>
          </>
        )}
      </Modal>
    </div>
  );
}
