import { useState, useEffect, useCallback, useMemo } from 'react';
import Sidebar from '../../components/Sidebar';
import { toast } from '../../components/Toast';
import { API } from '../../api';
import { renderMarkdown } from '../Chat/utils';
import type { TopicDigest, ConclusionDTO, KnowledgeCardDTO } from '../../types';
import './style.css';

export default function TopicsPage() {
  const [topics, setTopics] = useState<TopicDigest[]>([]);
  const [keyword, setKeyword] = useState('');
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [conclusion, setConclusion] = useState<ConclusionDTO | null>(null);
  const [cards, setCards] = useState<KnowledgeCardDTO[]>([]);
  const [loadingList, setLoadingList] = useState(true);
  const [loadingDetail, setLoadingDetail] = useState(false);
  // 左侧主题列表收起状态（持久化到 localStorage，跨刷新保留）
  const [listCollapsed, setListCollapsed] = useState<boolean>(() => {
    try { return localStorage.getItem('topics:listCollapsed') === '1'; }
    catch { return false; }
  });

  useEffect(() => {
    try { localStorage.setItem('topics:listCollapsed', listCollapsed ? '1' : '0'); }
    catch { /* 隐私模式或配额满 — 静默忽略 */ }
  }, [listCollapsed]);

  const loadTopics = useCallback(async () => {
    try {
      setTopics(await API.get<TopicDigest[]>('/api/topics/closed'));
    } catch (e: any) { toast(e.message, 'error'); }
    finally { setLoadingList(false); }
  }, []);

  useEffect(() => { loadTopics(); }, [loadTopics]);

  // 搜索过滤
  const filtered = useMemo(() => {
    const kw = keyword.trim().toLowerCase();
    if (!kw) return topics;
    return topics.filter(t =>
      t.title.toLowerCase().includes(kw) ||
      t.groupName.toLowerCase().includes(kw)
    );
  }, [topics, keyword]);

  // 统计
  const stats = useMemo(() => ({
    total: topics.length,
    groups: new Set(topics.map(t => t.groupId)).size,
  }), [topics]);

  // 加载详情
  const selectTopic = useCallback(async (topicId: number) => {
    setSelectedId(topicId);
    setConclusion(null);
    setCards([]);
    setLoadingDetail(true);
    try {
      const [c, k] = await Promise.all([
        API.get<ConclusionDTO>(`/api/topics/${topicId}/conclusion`),
        API.get<KnowledgeCardDTO[]>(`/api/topics/${topicId}/cards`),
      ]);
      setConclusion(c);
      setCards(k);
    } catch (e: any) {
      toast(e.message, 'error');
    } finally { setLoadingDetail(false); }
  }, []);

  // 默认选中第一个
  useEffect(() => {
    if (!selectedId && filtered.length > 0) {
      selectTopic(filtered[0].id);
    }
  }, [filtered, selectedId, selectTopic]);

  const selected = topics.find(t => t.id === selectedId);

  return (
    <div className="app-shell">
      <Sidebar onSearch={setKeyword} searchPlaceholder="搜索主题或群名...">
        <div className="sidebar__label">主题列表</div>
      </Sidebar>

      <section className="page page--editorial topics-page">
        <header className="page__head">
          <div className="page__head-meta">
            <span className="page__eyebrow">Topic Sediment</span>
            <span className="page__head-rule" aria-hidden />
            <span className="page__head-id">No. {String(stats.total).padStart(3, '0')}</span>
          </div>
          <div className="page__head-main">
            <div className="page__head-title-row">
              <h1 className="page__title-serif">主题沉淀区</h1>
            </div>
            <p className="page__lead">
              跨群回顾所有讨论的深度分析结论与知识沉淀，共 {stats.total} 个主题 · 覆盖 {stats.groups} 个群
            </p>
          </div>
        </header>

        <div className="page__body topics-body">
          {/* 左侧主题列表 — 支持收起 */}
          <div className={`topics-list-wrap${listCollapsed ? ' topics-list-wrap--collapsed' : ''}`}>
            <div className="topics-list">
              {loadingList ? (
                Array.from({ length: 5 }).map((_, i) => (
                  <div key={`sk-${i}`} className="topic-item-skeleton" style={{ animationDelay: `${i * 40}ms` }}>
                    <div className="topic-item-skeleton__title" />
                    <div className="topic-item-skeleton__meta" />
                  </div>
                ))
              ) : !filtered.length ? (
                <div className="empty empty--editorial topics-list__empty">
                  <div className="empty__icon">📜</div>
                  <div className="empty__title">暂无沉淀主题</div>
                  <div className="empty__hint">在群聊中结束讨论后会自动沉淀到这里</div>
                </div>
              ) : filtered.map((t, idx) => (
                <div
                  key={t.id}
                  className={`topic-item${t.id === selectedId ? ' topic-item--active' : ''}`}
                  style={{ animationDelay: `${Math.min(idx, 8) * 25}ms` }}
                  onClick={() => selectTopic(t.id)}
                >
                  <div className="topic-item__title">{t.title}</div>
                  <div className="topic-item__meta">
                    <span className="tag tag--neutral">{t.groupName}</span>
                    <span className="topic-item__count">{t.messageCount} 条消息</span>
                  </div>
                  <div className="topic-item__time">
                    {t.closedAt ? new Date(t.closedAt).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) : ''}
                  </div>
                </div>
              ))}
            </div>
            <button
              type="button"
              className="topics-list__toggle"
              onClick={() => setListCollapsed(v => !v)}
              aria-label={listCollapsed ? '展开主题列表' : '收起主题列表'}
              aria-expanded={!listCollapsed}
              title={listCollapsed ? '展开主题列表' : '收起主题列表'}
            >
              <span className="topics-list__toggle-icon" aria-hidden>
                {listCollapsed ? '›' : '‹'}
              </span>
            </button>
          </div>

          {/* 右侧详情 */}
          <div className="topics-detail">
            {!selected ? (
              <div className="empty empty--editorial topics-detail__empty">
                <div className="empty__icon">📖</div>
                <div className="empty__title">选择一个主题查看深度分析</div>
                <div className="empty__hint">点击左侧主题，查看讨论结论与生成的知识卡片</div>
              </div>
            ) : loadingDetail ? (
              <div className="topics-detail__loading">
                <span className="route-loading__spinner" />
                <span>加载结论中…</span>
              </div>
            ) : (
              <>
                <div className="topics-detail__head">
                  <h2 className="topics-detail__title">{selected.title}</h2>
                  <div className="topics-detail__meta">
                    <span className="tag tag--brand">{selected.groupName}</span>
                    <span className="topics-detail__count">{selected.messageCount} 条消息</span>
                    {conclusion?.closedAt && (
                      <span className="topics-detail__date">
                        关闭于 {new Date(conclusion.closedAt).toLocaleString('zh-CN')}
                      </span>
                    )}
                    {conclusion?.concluderAgentName && (
                      <span className="tag tag--success">由 {conclusion.concluderAgentName} 总结</span>
                    )}
                  </div>
                </div>

                {conclusion ? (
                  <div className="topics-detail__section">
                    <div className="topics-detail__label">讨论结论</div>
                    <div
                      className="conclusion-box md-body topics-detail__conclusion"
                      dangerouslySetInnerHTML={{ __html: renderMarkdown(conclusion.conclusion) }}
                    />
                  </div>
                ) : (
                  <div className="topics-detail__section topics-detail__section--empty">
                    该主题暂无结论
                  </div>
                )}

                <div className="topics-detail__section">
                  <div className="topics-detail__label">
                    生成的知识卡片{cards.length > 0 ? `（${cards.length}）` : ''}
                  </div>
                  {cards.length > 0 ? (
                    <div className="topics-cards">
                      {cards.map(c => (
                        <div key={c.id} className="conclusion-card topics-card">
                          <div className="conclusion-card__q">Q: {c.question}</div>
                          <div className="conclusion-card__a">A: {c.answer}</div>
                          {c.category && <span className="tag tag--brand topics-card__cat">{c.category}</span>}
                        </div>
                      ))}
                    </div>
                  ) : (
                    <div className="topics-detail__section--empty">该主题暂无知识卡片</div>
                  )}
                </div>
              </>
            )}
          </div>
        </div>
      </section>
    </div>
  );
}
