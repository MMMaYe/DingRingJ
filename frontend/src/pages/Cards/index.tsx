import { useState, useEffect, useCallback } from 'react';
import Sidebar from '../../components/Sidebar';
import { toast } from '../../components/Toast';
import { API } from '../../api';
import type { KnowledgeCardDTO, ReviewCardDTO } from '../../types';
import './style.css';

export default function CardsPage() {
  const [cards, setCards] = useState<KnowledgeCardDTO[]>([]);
  const [categories, setCategories] = useState<string[]>([]);
  const [currentCat, setCurrentCat] = useState<string | null>(null);
  const [order, setOrder] = useState<'sequential' | 'random'>('sequential');

  // 复习模式
  const [reviewCards, setReviewCards] = useState<KnowledgeCardDTO[]>([]);
  const [reviewIdx, setReviewIdx] = useState(0);
  const [reviewActive, setReviewActive] = useState(false);
  const [flipped, setFlipped] = useState(false);

  const loadCategories = useCallback(async () => {
    try { setCategories(await API.get<string[]>('/api/cards/categories')); } catch { /* ignore */ }
  }, []);

  const loadCards = useCallback(async () => {
    try {
      const query = currentCat ? `?category=${encodeURIComponent(currentCat)}` : '';
      setCards(await API.get<KnowledgeCardDTO[]>(`/api/cards${query}`));
    } catch (e: any) { toast(e.message, 'error'); }
  }, [currentCat]);

  useEffect(() => { loadCategories(); }, [loadCategories]);
  useEffect(() => { loadCards(); }, [loadCards]);

  // ---- 复习 ----
  const startReview = useCallback(async () => {
    try {
      const params = new URLSearchParams();
      if (currentCat) params.set('category', currentCat);
      params.set('order', order);
      const data = await API.get<ReviewCardDTO>(`/api/cards/review?${params}`);
      if (!data.cards.length) { toast('当前筛选下没有可复习的卡片', 'error'); return; }
      setReviewCards(data.cards);
      setReviewIdx(0);
      setFlipped(false);
      setReviewActive(true);
    } catch (e: any) { toast(e.message, 'error'); }
  }, [currentCat, order]);

  const showNext = useCallback(() => {
    if (reviewIdx < reviewCards.length - 1) {
      setReviewIdx(i => i + 1);
      setFlipped(false);
    } else {
      setReviewActive(false);
      toast(`🎉 本轮复习完成，共 ${reviewCards.length} 张卡片`, 'success');
    }
  }, [reviewIdx, reviewCards.length]);

  const showPrev = useCallback(() => {
    if (reviewIdx > 0) { setReviewIdx(i => i - 1); setFlipped(false); }
  }, [reviewIdx]);

  // 键盘
  useEffect(() => {
    if (!reviewActive) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === ' ') { e.preventDefault(); setFlipped(f => !f); }
      else if (e.key === 'ArrowLeft') showPrev();
      else if (e.key === 'ArrowRight') showNext();
      else if (e.key === 'Escape') setReviewActive(false);
    };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [reviewActive, showPrev, showNext]);

  const current = reviewCards[reviewIdx];

  return (
    <div className="app-shell">
      <Sidebar footer={<button className="btn btn--brand btn--block" onClick={startReview}>▶ 开始复习</button>}>
        <div className="sidebar__label">按分类筛选</div>
        <div className="cat-list">
          <div className={`cat-item${currentCat === null ? ' cat-item--active' : ''}`} onClick={() => setCurrentCat(null)}>全部卡片</div>
          {categories.map(c => (
            <div key={c} className={`cat-item${currentCat === c ? ' cat-item--active' : ''}`} onClick={() => setCurrentCat(c)}>{c}</div>
          ))}
        </div>
      </Sidebar>

      <section className="page">
        <header className="page__header">
          <div>
            <div className="page__title">知识卡片</div>
            <div className="page__subtitle">
              {currentCat ? `分类「${currentCat}」` : '全部'}共 {cards.length} 张卡片 · 点击卡片查看答案
            </div>
          </div>
          <div className="page__header-actions">
            <select className="input" style={{ width: 130 }} value={order} onChange={e => setOrder(e.target.value as any)}>
              <option value="sequential">顺序复习</option>
              <option value="random">随机复习</option>
            </select>
            <button className="btn btn--brand" onClick={startReview}>▶ 开始复习</button>
          </div>
        </header>
        <div className="page__body">
          <div className="card-grid">
            {!cards.length ? (
              <div className="empty" style={{ gridColumn: '1/-1' }}>
                <div className="empty__icon">🗂️</div>暂无知识卡片<br />在群里发起讨论并 @专家 结束后会自动生成
              </div>
            ) : cards.map(c => (
              <div key={c.id} className="k-card" onClick={e => (e.currentTarget as HTMLElement).classList.toggle('is-open')}>
                <div className="k-card__q">Q: {c.question}</div>
                <div className="k-card__a k-card__a--hidden">A: {c.answer}</div>
                <div className="k-card__footer">
                  {c.category && <span className="tag tag--brand">{c.category}</span>}
                  <span className="k-card__topic">来自「{c.topicTitle}」</span>
                  <span style={{ marginLeft: 'auto' }}>{new Date(c.createTime).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })}</span>
                </div>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* 复习浮层 */}
      {reviewActive && current && (
        <div className="review-mask">
          <div className="review">
            <div className="review__top">
              <span className="review__progress">第 {reviewIdx + 1} / {reviewCards.length} 张</span>
              <button className="btn btn--ghost" onClick={() => setReviewActive(false)}>✕ 退出复习</button>
            </div>
            <div className="review__stage">
              <div className={`flip-card${flipped ? ' is-flipped' : ''}`} onClick={() => setFlipped(f => !f)}>
                <div className="flip-card__inner">
                  <div className="flip-card__face flip-card__face--front">
                    <div className="flip-card__label">问题 · 点击翻面看答案</div>
                    <div className="flip-card__text">{current.question}</div>
                    <div className="flip-card__meta">{current.category ? `#${current.category} · ` : ''}来自「{current.topicTitle}」</div>
                  </div>
                  <div className="flip-card__face flip-card__face--back">
                    <div className="flip-card__label flip-card__label--answer">答案</div>
                    <div className="flip-card__text">{current.answer}</div>
                  </div>
                </div>
              </div>
            </div>
            <div className="review__nav">
              <button className="btn btn--ghost" disabled={reviewIdx === 0} onClick={showPrev}>← 上一张</button>
              <button className="btn" onClick={() => setFlipped(f => !f)}>🔄 翻面（空格）</button>
              <button className="btn btn--brand" onClick={showNext}>{reviewIdx === reviewCards.length - 1 ? '完成 ✓' : '下一张 →'}</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
