import { useCallback, useEffect, useRef, useState } from 'react';
import Sidebar from '../../components/Sidebar';
import Modal from '../../components/Modal';
import { toast } from '../../components/Toast';
import { KbApi, type KbFileDTO, type KbSummary } from '../../api';
import './style.css';

/** 处理中状态：存在任一即触发 3s 轮询，全部终态（READY/FAILED）后停止 */
const PROCESSING: string[] = ['UPLOADED', 'CHUNKED', 'EMBEDDED'];

function statusLabel(s: KbFileDTO['status']): string {
  switch (s) {
    case 'UPLOADED': return '已上传';
    case 'CHUNKED': return '切片中';
    case 'EMBEDDED': return '向量化中';
    case 'READY': return '已就绪';
    case 'FAILED': return '处理失败';
  }
}

function statusTagClass(s: KbFileDTO['status']): string {
  switch (s) {
    case 'READY': return 'tag tag--success';
    case 'FAILED': return 'tag tag--error';
    default: return 'tag tag--amber';
  }
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

/**
 * RAG 知识库页（P2 全量对接）。
 * 知识库 = 用户主动上传 .md 文档的 RAG 检索源：上传 -> 自动切片 -> 向量化 -> 群聊注入。
 * 与「知识卡片」的区别：卡片是讨论结束自动生成的 Q/A；本页是用户上传的文档库。
 */
export default function KBPage() {
  const [kbs, setKbs] = useState<KbSummary[]>([]);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [files, setFiles] = useState<KbFileDTO[]>([]);
  const [keyword, setKeyword] = useState('');
  const [uploading, setUploading] = useState(false);
  const [creating, setCreating] = useState(false);
  const [newName, setNewName] = useState('');
  const [newScope, setNewScope] = useState<'GLOBAL' | 'GROUP'>('GLOBAL');
  const [newGroupId, setNewGroupId] = useState('');
  const [loading, setLoading] = useState(true);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const reloadKbs = useCallback(async () => {
    try {
      const list = await KbApi.list();
      setKbs(list);
      // 保持当前选中；已被删除或初载时回退到第一个
      setSelectedId(prev => (prev != null && list.some(k => k.id === prev) ? prev : (list[0]?.id ?? null)));
    } catch (e) {
      toast((e as Error).message, 'error');
    } finally {
      setLoading(false);
    }
  }, []);

  const reloadFiles = useCallback(async (id: number) => {
    try {
      setFiles(await KbApi.listFiles(id));
    } catch (e) {
      toast((e as Error).message, 'error');
    }
  }, []);

  useEffect(() => { void reloadKbs(); }, [reloadKbs]);

  useEffect(() => {
    if (selectedId != null) void reloadFiles(selectedId);
    else setFiles([]);
  }, [selectedId, reloadFiles]);

  /** 处理进度轮询：有处理中文件每 3s 刷新，全部终态停止 */
  const hasProcessing = files.some(f => PROCESSING.includes(f.status));
  useEffect(() => {
    if (selectedId == null || !hasProcessing) return;
    const t = setInterval(() => void reloadFiles(selectedId), 3000);
    return () => clearInterval(t);
  }, [selectedId, hasProcessing, reloadFiles]);

  const handleCreate = async () => {
    const name = newName.trim();
    if (!name) { toast('请输入知识库名称', 'info'); return; }
    if (newScope === 'GROUP' && !newGroupId.trim()) { toast('群专属知识库需填写 groupId', 'info'); return; }
    try {
      const created = await KbApi.create({
        name, scope: newScope,
        ...(newScope === 'GROUP' ? { groupId: Number(newGroupId) } : {}),
      });
      toast(`知识库「${created.name}」已创建`, 'success');
      setCreating(false); setNewName(''); setNewGroupId('');
      await reloadKbs();
      setSelectedId(created.id);
    } catch (e) {
      toast((e as Error).message, 'error');
    }
  };

  const handleDeleteKb = async (kb: KbSummary) => {
    if (!window.confirm(`删除知识库「${kb.name}」？将连带删除其全部文件与向量数据，不可恢复。`)) return;
    try {
      await KbApi.remove(kb.id);
      toast('知识库已删除', 'success');
      await reloadKbs();
    } catch (e) {
      toast((e as Error).message, 'error');
    }
  };

  const handleUpload = async (f: File) => {
    if (selectedId == null) { toast('请先选择或创建知识库', 'info'); return; }
    // 与后端双检对齐：扩展名 .md/.markdown（contentType 由后端校验）
    if (!/\.(md|markdown)$/i.test(f.name)) { toast('当前仅支持 .md 文件', 'error'); return; }
    setUploading(true);
    try {
      await KbApi.uploadFile(selectedId, f);
      toast(`「${f.name}」已上传，正在切片与向量化`, 'success');
      await reloadFiles(selectedId);
    } catch (e) {
      toast((e as Error).message, 'error');
    } finally {
      setUploading(false);
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
  };

  const handleDeleteFile = async (f: KbFileDTO) => {
    if (selectedId == null) return;
    if (!window.confirm(`删除文件「${f.name}」？其向量数据将一并清理。`)) return;
    try {
      await KbApi.deleteFile(selectedId, f.id);
      toast('文件已删除', 'success');
      await reloadFiles(selectedId);
    } catch (e) {
      toast((e as Error).message, 'error');
    }
  };

  const filtered = files.filter(d =>
    !keyword.trim() || d.name.toLowerCase().includes(keyword.trim().toLowerCase()));

  const readyCount = files.filter(f => f.status === 'READY').length;
  const totalChunks = files.reduce((sum, f) => sum + (f.chunkCount ?? 0), 0);

  return (
    <div className="app-shell">
      <Sidebar>
        <div className="sidebar__label">知识库</div>
        <button className="btn btn--brand kb-side-create" onClick={() => setCreating(true)}>新建知识库</button>
        <div className="kb-side-list">
          {kbs.map(kb => (
            <div
              key={kb.id}
              className={`kb-side-item ${kb.id === selectedId ? 'kb-side-item--active' : ''}`}
              onClick={() => setSelectedId(kb.id)}
            >
              <span className="kb-side-item__name">{kb.name}</span>
              <span className="kb-side-item__scope">{kb.scope === 'GLOBAL' ? '全局' : `群 ${kb.groupId}`}</span>
              <button className="kb-side-item__del" title="删除知识库"
                      onClick={e => { e.stopPropagation(); void handleDeleteKb(kb); }}>×</button>
            </div>
          ))}
          {!loading && kbs.length === 0 && <div className="kb-side-hint">暂无知识库，点击上方新建</div>}
        </div>
      </Sidebar>

      <section className="page page--editorial">
        <header className="page__head">
          <div className="page__head-meta">
            <span className="page__eyebrow">Knowledge Base · RAG</span>
            <span className="page__head-rule" aria-hidden />
            <span className="page__head-id">No. {String(kbs.findIndex(k => k.id === selectedId) + 1 || 0).padStart(2, '0')}</span>
          </div>
          <div className="page__head-main">
            <div className="page__head-title-row">
              <h1 className="page__title-serif">
                {kbs.find(k => k.id === selectedId)?.name ?? '知识库管理'}
              </h1>
              <div className="page__head-cta-group">
                {/* 隐藏 file input：按钮点击转交，上传后重置 value 以支持重复选择同一文件 */}
                <input
                  ref={fileInputRef}
                  type="file"
                  accept=".md,.markdown"
                  className="kb-upload-input"
                  disabled={uploading || selectedId == null}
                  onChange={e => { const f = e.target.files?.[0]; if (f) void handleUpload(f); }}
                />
                <button
                  className="btn btn--brand page__head-cta"
                  disabled={uploading || selectedId == null}
                  onClick={() => fileInputRef.current?.click()}
                  title={selectedId == null ? '请先选择知识库' : '上传 .md 文档'}
                >
                  <svg width="14" height="14" viewBox="0 0 14 14" fill="none"><path d="M7 9.5V2.5M7 2.5L4 5.5M7 2.5l3 3M2.5 9.5v2A1.5 1.5 0 004 13h6a1.5 1.5 0 001.5-1.5v-2" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" strokeLinejoin="round"/></svg>
                  {uploading ? '上传中…' : '上传文档'}
                </button>
              </div>
            </div>
            <p className="page__lead">
              上传 .md 文档构建专属知识库，系统自动切片并向量化，群聊讨论时作为 RAG 检索源自动注入
            </p>
          </div>
          <div className="page__head-actions">
            <div className="page__search">
              <svg className="page__search-icon" width="14" height="14" viewBox="0 0 14 14" fill="none"><circle cx="6" cy="6" r="4" stroke="currentColor" strokeWidth="1.2"/><path d="M9.5 9.5L12.5 12.5" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round"/></svg>
              <input
                type="text"
                className="page__search-input"
                placeholder="搜索文档名称..."
                value={keyword}
                onChange={e => setKeyword(e.target.value)}
              />
            </div>
          </div>
        </header>

        <div className="page__stats">
          <div className="stat-card">
            <span className="stat-card__num">{kbs.length}</span>
            <span className="stat-card__label">个知识库</span>
            <span className="stat-card__bar" aria-hidden />
          </div>
          <div className="stat-card">
            <span className="stat-card__num">{files.length}</span>
            <span className="stat-card__label">份文档</span>
            <span className="stat-card__bar stat-card__bar--accent" aria-hidden />
          </div>
          <div className="stat-card">
            <span className="stat-card__num">{totalChunks}</span>
            <span className="stat-card__label">个切片</span>
            <span className="stat-card__bar stat-card__bar--violet" aria-hidden />
          </div>
          <div className="stat-card">
            <span className="stat-card__num">{readyCount}</span>
            <span className="stat-card__label">已就绪</span>
            <span className="stat-card__bar stat-card__bar--amber" aria-hidden />
          </div>
        </div>

        <div className="page__body">
          {!filtered.length ? (
            <div className="empty empty--editorial">
              <div className="empty__title">{selectedId == null ? '暂无知识库' : '该知识库暂无文档'}</div>
              <div className="empty__hint">
                上传 .md 文档，系统将自动切片与向量化<br />在群聊讨论中作为 RAG 检索源
              </div>
            </div>
          ) : (
            <div className="kb-list">
              {filtered.map((doc, idx) => (
                <article key={doc.id} className="kb-card kb-card--editorial kb-card--filled"
                         style={{ animationDelay: `${idx * 40}ms` }}>
                  <div className="kb-card__index" aria-hidden>{String(idx + 1).padStart(2, '0')}</div>
                  <div className="kb-card__avatar">{doc.name.charAt(0)}</div>
                  <div className="kb-card__info">
                    <div className="kb-card__name">{doc.name}</div>
                    <div className="kb-card__desc">
                      {formatSize(doc.fileSize)} · 切片 {doc.chunkCount ?? '-'}
                      {doc.status === 'FAILED' && doc.errorMsg
                        ? ` · 失败原因：${doc.errorMsg.slice(0, 80)}` : ''}
                    </div>
                  </div>
                  <div className="kb-card__right">
                    <span className={statusTagClass(doc.status)}>{statusLabel(doc.status)}</span>
                    <button className="kb-card__del" title="删除文件"
                            onClick={() => void handleDeleteFile(doc)}>删除</button>
                  </div>
                </article>
              ))}
            </div>
          )}
        </div>

        <Modal
          open={creating}
          onClose={() => setCreating(false)}
          eyebrow="Knowledge Base"
          title="新建知识库"
          subtitle="上传 .md 文档，作为群聊 RAG 检索源"
          footer={
            <div className="kb-modal-actions">
              <button className="btn" onClick={() => setCreating(false)}>取消</button>
              <button className="btn btn--brand" onClick={() => void handleCreate()}>创建</button>
            </div>
          }
        >
          <div className="form-row">
            <label>名称</label>
            <input className="input" value={newName} onChange={e => setNewName(e.target.value)}
                   placeholder="如：Java 并发编程资料库" autoFocus />
          </div>
          <div className="form-row">
            <label>作用域</label>
            <select className="input" value={newScope} onChange={e => setNewScope(e.target.value as 'GLOBAL' | 'GROUP')}>
              <option value="GLOBAL">全局（所有群聊可用）</option>
              <option value="GROUP">群专属（仅指定群可用）</option>
            </select>
          </div>
          {newScope === 'GROUP' && (
            <div className="form-row">
              <label>群 ID</label>
              <input className="input" value={newGroupId} onChange={e => setNewGroupId(e.target.value)}
                     placeholder="如：1" inputMode="numeric" />
            </div>
          )}
        </Modal>
      </section>
    </div>
  );
}
