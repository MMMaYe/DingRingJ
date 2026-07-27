import { useState } from 'react';
import Sidebar from '../../components/Sidebar';
import { toast } from '../../components/Toast';
import './style.css';

/**
 * RAG 文档库骨架页（v1 占位）
 *
 * 设计语义：知识库 = 后续上传文件用于 RAG 检索的文档库
 * 当前阶段：仅 UI 骨架，文件上传/切片/向量化等后端能力暂未实现
 * 后端 stub：dingRing-domain/.../knowledgebase/{KnowledgeBase,File}.java（P2 阶段实现）
 *
 * 与「知识卡片管理」的区别：
 * - 知识卡片：讨论结束后自动生成的 Q/A 卡片，用于复习
 * - 知识库（本页）：用户主动上传的文档，用于 RAG 检索增强生成
 */
export default function KBPage() {
  const [keyword, setKeyword] = useState('');

  // 文档状态：UPLOADED → CHUNKED → EMBEDDED → READY
  // 当前没有真实数据，用空数组占位
  const documents: KbDocument[] = [];

  const filtered = documents.filter(d =>
    !keyword.trim() ||
    d.name.toLowerCase().includes(keyword.trim().toLowerCase())
  );

  // 上传按钮：暂未实现，点击提示
  const handleUpload = () => {
    toast('文件上传功能即将上线，敬请期待', 'info');
  };

  return (
    <div className="app-shell">
      <Sidebar>
        <div className="sidebar__label">知识库</div>
        <div className="kb-side-hint">
          上传文档构建专属知识库<br />后续将用于群聊 RAG 检索增强
        </div>
      </Sidebar>

      <section className="page page--editorial">
        {/* editorial 页首 */}
        <header className="page__head">
          <div className="page__head-meta">
            <span className="page__eyebrow">Knowledge Base · RAG</span>
            <span className="page__head-rule" aria-hidden />
            <span className="page__head-id">No. 00</span>
          </div>
          <div className="page__head-main">
            <div className="page__head-title-row">
              <h1 className="page__title-serif">知识库管理</h1>
              <div className="page__head-cta-group">
                <button
                  className="btn btn--brand page__head-cta"
                  onClick={handleUpload}
                  title="文件上传功能即将上线"
                >
                  <svg width="14" height="14" viewBox="0 0 14 14" fill="none"><path d="M7 9.5V2.5M7 2.5L4 5.5M7 2.5l3 3M2.5 9.5v2A1.5 1.5 0 004 13h6a1.5 1.5 0 001.5-1.5v-2" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" strokeLinejoin="round"/></svg>
                  上传文档
                </button>
              </div>
            </div>
            <p className="page__lead">
              上传 PDF / Markdown / TXT 等文档构建专属知识库，系统将自动切片并向量化，后续在群聊讨论中作为 RAG 检索源
            </p>
          </div>
          <div className="page__head-actions">
            <div className="page__search">
              <svg className="page__search-icon" width="14" height="14" viewBox="0 0 14 14" fill="none"><circle cx="6" cy="6" r="4" stroke="currentColor" strokeWidth="1.2"/><path d="M9.5 9.5L12.5 12.5" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round"/></svg>
              <input
                type="text"
                className="page__search-input"
                placeholder="搜索文档（名称 / 类型）..."
                value={keyword}
                onChange={e => setKeyword(e.target.value)}
              />
            </div>
          </div>
        </header>

        {/* 统计卡片：全部为 0（占位） */}
        <div className="page__stats">
          <div className="stat-card">
            <span className="stat-card__num">0</span>
            <span className="stat-card__label">个知识库</span>
            <span className="stat-card__bar" aria-hidden />
          </div>
          <div className="stat-card">
            <span className="stat-card__num">0</span>
            <span className="stat-card__label">份文档</span>
            <span className="stat-card__bar stat-card__bar--accent" aria-hidden />
          </div>
          <div className="stat-card">
            <span className="stat-card__num">0</span>
            <span className="stat-card__label">个切片</span>
            <span className="stat-card__bar stat-card__bar--violet" aria-hidden />
          </div>
          <div className="stat-card">
            <span className="stat-card__num">0</span>
            <span className="stat-card__label">已向量化</span>
            <span className="stat-card__bar stat-card__bar--amber" aria-hidden />
          </div>
        </div>

        {/* 主体：文档列表 / 空状态 / 功能预告 */}
        <div className="page__body">
          {!filtered.length ? (
            <div className="kb-empty-stack">
              {/* 空状态 */}
              <div className="empty empty--editorial">
                <div className="empty__icon empty__icon--logo">
                  <svg width="40" height="40" viewBox="0 0 32 32" fill="none">
                    <path d="M9 3.5h11L25 8.5v17a3 3 0 01-3 3H9a3 3 0 01-3-3V6.5a3 3 0 013-3z" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round"/>
                    <path d="M20 3.5V8.5h5" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round"/>
                    <path d="M10 16h12M10 20h8" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round"/>
                  </svg>
                </div>
                <div className="empty__title">知识库暂无文档</div>
                <div className="empty__hint">
                  上传 PDF / Markdown / TXT 文档，系统将自动切片与向量化<br />
                  后续在群聊讨论中作为 RAG 检索源
                </div>
                <button
                  className="btn btn--brand kb-empty-stack__cta"
                  onClick={handleUpload}
                >
                  上传第一份文档
                </button>
              </div>

              {/* 功能预告卡片 */}
              <div className="kb-roadmap">
                <div className="kb-roadmap__head">
                  <span className="kb-roadmap__eyebrow">Roadmap</span>
                  <span className="kb-roadmap__title">知识库能力规划</span>
                </div>
                <div className="kb-roadmap__grid">
                  <article className="kb-roadmap__item">
                    <div className="kb-roadmap__num">01</div>
                    <div className="kb-roadmap__body">
                      <div className="kb-roadmap__name">文档上传</div>
                      <div className="kb-roadmap__desc">支持 PDF / Markdown / TXT / DOCX，单文件最大 20MB</div>
                      <span className="tag tag--amber">即将上线</span>
                    </div>
                  </article>
                  <article className="kb-roadmap__item">
                    <div className="kb-roadmap__num">02</div>
                    <div className="kb-roadmap__body">
                      <div className="kb-roadmap__name">自动切片</div>
                      <div className="kb-roadmap__desc">按语义段落 + 滑动窗口切片，保留上下文重叠</div>
                      <span className="tag tag--neutral">规划中</span>
                    </div>
                  </article>
                  <article className="kb-roadmap__item">
                    <div className="kb-roadmap__num">03</div>
                    <div className="kb-roadmap__body">
                      <div className="kb-roadmap__name">向量化入库</div>
                      <div className="kb-roadmap__desc">基于 Embedding 模型生成向量，存入 VectorStore（pgvector）</div>
                      <span className="tag tag--neutral">规划中</span>
                    </div>
                  </article>
                  <article className="kb-roadmap__item">
                    <div className="kb-roadmap__num">04</div>
                    <div className="kb-roadmap__body">
                      <div className="kb-roadmap__name">RAG 检索增强</div>
                      <div className="kb-roadmap__desc">群聊讨论时自动检索相关知识，注入 Agent 上下文</div>
                      <span className="tag tag--neutral">规划中</span>
                    </div>
                  </article>
                </div>
              </div>
            </div>
          ) : (
            <div className="kb-list">
              {filtered.map((doc, idx) => (
                <article
                  key={doc.id}
                  className="kb-card kb-card--editorial kb-card--filled"
                  style={{ animationDelay: `${idx * 40}ms` }}
                >
                  <div className="kb-card__index" aria-hidden>{String(idx + 1).padStart(2, '0')}</div>
                  <div className="kb-card__avatar">{doc.name.charAt(0)}</div>
                  <div className="kb-card__info">
                    <div className="kb-card__name">{doc.name}</div>
                    <div className="kb-card__desc">{doc.size} · {doc.type}</div>
                  </div>
                  <div className="kb-card__right">
                    <span className={`tag kb-status--${doc.status.toLowerCase()}`}>{statusLabel(doc.status)}</span>
                  </div>
                </article>
              ))}
            </div>
          )}
        </div>
      </section>
    </div>
  );
}

/** 文档状态：与后端 File.java 对齐（P2 实现后启用） */
type KbDocumentStatus = 'UPLOADED' | 'CHUNKED' | 'EMBEDDED' | 'READY' | 'FAILED';

interface KbDocument {
  id: number;
  name: string;
  type: string;
  size: string;
  status: KbDocumentStatus;
  uploadTime: string;
}

function statusLabel(s: KbDocumentStatus): string {
  switch (s) {
    case 'UPLOADED': return '已上传';
    case 'CHUNKED': return '已切片';
    case 'EMBEDDED': return '已向量化';
    case 'READY': return '已就绪';
    case 'FAILED': return '处理失败';
  }
}
