import { useState, useEffect, useCallback, useMemo } from 'react';
import Sidebar, { IconPlus } from '../../components/Sidebar';
import Avatar from '../../components/Avatar';
import Modal from '../../components/Modal';
import { toast } from '../../components/Toast';
import { API } from '../../api';
import type { AgentDTO } from '../../types';
import './style.css';

export default function AgentsPage() {
  const [agents, setAgents] = useState<AgentDTO[]>([]);
  const [showModal, setShowModal] = useState(false);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [keyword, setKeyword] = useState('');

  // form
  const [name, setName] = useState('');
  const [modelName, setModelName] = useState('');
  const [description, setDescription] = useState('');
  const [baseUrl, setBaseUrl] = useState('');
  const [apiKey, setApiKey] = useState('');
  const [systemPrompt, setSystemPrompt] = useState('');
  const [submitting, setSubmitting] = useState(false);

  // 搜索过滤
  const filteredAgents = useMemo(() => {
    const kw = keyword.trim().toLowerCase();
    if (!kw) return agents;
    return agents.filter(a =>
      a.name.toLowerCase().includes(kw) ||
      a.modelName.toLowerCase().includes(kw) ||
      (a.description || '').toLowerCase().includes(kw)
    );
  }, [agents, keyword]);

  // 统计：按 baseUrl 去重统计供应商数
  const stats = useMemo(() => ({
    total: agents.length,
    providers: new Set(agents.map(a => { try { return new URL(a.baseUrl).host; } catch { return a.baseUrl; } })).size,
    models: new Set(agents.map(a => a.modelName)).size,
  }), [agents]);

  const loadAgents = useCallback(async () => {
    try { setAgents(await API.get<AgentDTO[]>('/api/agents')); } catch (e: any) { toast(e.message, 'error'); }
  }, []);

  useEffect(() => { loadAgents(); }, [loadAgents]);

  const openCreate = useCallback(() => {
    setEditingId(null);
    setName(''); setModelName(''); setDescription(''); setBaseUrl(''); setApiKey(''); setSystemPrompt('');
    setShowModal(true);
  }, []);

  const openEdit = useCallback((a: AgentDTO) => {
    setEditingId(a.id);
    setName(a.name); setModelName(a.modelName); setDescription(a.description || '');
    setBaseUrl(a.baseUrl); setApiKey(''); setSystemPrompt(a.systemPrompt || '');
    setShowModal(true);
  }, []);

  const submit = useCallback(async () => {
    if (!name.trim()) { toast('请输入花名', 'error'); return; }
    if (!baseUrl.trim()) { toast('请输入 Base URL', 'error'); return; }
    if (!modelName.trim()) { toast('请输入模型名', 'error'); return; }
    if (editingId === null && !apiKey.trim()) { toast('请输入 API Key', 'error'); return; }

    const body = { name: name.trim(), description: description.trim(), baseUrl: baseUrl.trim(), modelName: modelName.trim(), systemPrompt: systemPrompt.trim() };
    setSubmitting(true);
    try {
      if (editingId === null) {
        await API.post('/api/agents', { ...body, apiKey: apiKey.trim() });
        toast('Agent 创建成功', 'success');
      } else {
        await API.put(`/api/agents/${editingId}`, apiKey.trim() ? { ...body, apiKey: apiKey.trim() } : body);
        toast('Agent 已更新', 'success');
      }
      setShowModal(false);
      await loadAgents();
    } catch (e: any) { toast(e.message, 'error'); }
    finally { setSubmitting(false); }
  }, [name, modelName, description, baseUrl, apiKey, systemPrompt, editingId, loadAgents]);

  function shortUrl(url: string) {
    try { return new URL(url).host; } catch { return url; }
  }

  return (
    <div className="app-shell">
      <Sidebar footer={<button className="sidebar__new-group" onClick={openCreate}><IconPlus /> 新建 Agent</button>} />

      <section className="page page--editorial">
        <header className="page__head">
          <div className="page__head-meta">
            <span className="page__eyebrow">Agents</span>
            <span className="page__head-rule" aria-hidden />
            <span className="page__head-id">No. {String(stats.total).padStart(2, '0')}</span>
          </div>
          <div className="page__head-main">
            <div className="page__head-title-row">
              <h1 className="page__title-serif">Agent 管理</h1>
              <button className="btn btn--brand page__head-cta" onClick={openCreate}><IconPlus /> 新建 Agent</button>
            </div>
            <p className="page__lead">配置 AI 同学的模型接入与人设，创建后即可拉入群聊参与讨论</p>
          </div>
          <div className="page__head-actions">
            <div className="page__search">
              <svg className="page__search-icon" width="14" height="14" viewBox="0 0 14 14" fill="none"><circle cx="6" cy="6" r="4" stroke="currentColor" strokeWidth="1.2"/><path d="M9.5 9.5L12.5 12.5" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round"/></svg>
              <input
                type="text"
                className="page__search-input"
                placeholder="搜索 Agent（名称 / 模型 / 描述）..."
                value={keyword}
                onChange={e => setKeyword(e.target.value)}
              />
            </div>
          </div>
        </header>

        {/* 统计卡片：editorial 三联 */}
        <div className="page__stats">
          <div className="stat-card">
            <span className="stat-card__num">{stats.total}</span>
            <span className="stat-card__label">个 Agent</span>
            <span className="stat-card__bar" aria-hidden />
          </div>
          <div className="stat-card">
            <span className="stat-card__num">{stats.providers}</span>
            <span className="stat-card__label">个供应商</span>
            <span className="stat-card__bar stat-card__bar--accent" aria-hidden />
          </div>
          <div className="stat-card">
            <span className="stat-card__num">{stats.models}</span>
            <span className="stat-card__label">种模型</span>
            <span className="stat-card__bar stat-card__bar--violet" aria-hidden />
          </div>
        </div>

        <div className="page__body">
          <div className="agent-list">
            {!agents.length ? (
              <div className="empty empty--editorial">
                <div className="empty__icon empty__icon--logo">
                  <svg width="32" height="32" viewBox="0 0 32 32" fill="none">
                    <circle cx="16" cy="16" r="13" stroke="currentColor" strokeWidth="1.4" opacity="0.35"/>
                    <circle cx="16" cy="16" r="9" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeDasharray="42.4 56.5" strokeDashoffset={-14}/>
                    <circle cx="16" cy="16" r="3" fill="currentColor"/>
                  </svg>
                </div>
                <div className="empty__title">还没有 Agent</div>
                <div className="empty__hint">点击右上方「新建 Agent」创建第一位 AI 同学</div>
              </div>
            ) : !filteredAgents.length ? (
              <div className="empty empty--editorial">
                <div className="empty__icon">🔍</div>
                <div className="empty__title">没有匹配「{keyword}」的 Agent</div>
              </div>
            ) : filteredAgents.map((a, idx) => (
              <article key={a.id} className="agent-card agent-card--editorial" style={{ animationDelay: `${idx * 40}ms` }}>
                <div className="agent-card__index" aria-hidden>{String(idx + 1).padStart(2, '0')}</div>
                <div className="agent-card__avatar">
                  <Avatar name={a.name} size="lg" />
                </div>
                <div className="agent-card__info">
                  <div className="agent-card__name">{a.name}</div>
                  <div className="agent-card__desc">{a.description || a.modelName}</div>
                  <div className="agent-card__tags">
                    <span className="tag">{a.modelName}</span>
                    <span className="tag tag--neutral">{shortUrl(a.baseUrl)}</span>
                  </div>
                </div>
                <div className="agent-card__right">
                  <span className="tag tag--success">运行中</span>
                  <div className="agent-card__meta">
                    <span>最后活跃: {new Date(a.updateTime || a.createTime).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })}</span>
                  </div>
                  <button className="btn btn--ghost" onClick={() => openEdit(a)}>配置</button>
                </div>
              </article>
            ))}
          </div>
        </div>
      </section>

      <Modal
        open={showModal}
        onClose={() => setShowModal(false)}
        title={editingId === null ? '新建 Agent' : '编辑 Agent'}
        eyebrow={editingId === null ? 'Create' : 'Edit'}
        subtitle={editingId === null ? '为群聊引入一位新的 AI 同学' : `正在配置 ${name}`}
        width={520}
        footer={<>
          <button className="btn btn--ghost" onClick={() => setShowModal(false)}>取消</button>
          <button className="btn btn--brand" disabled={submitting} onClick={submit}>{submitting ? '保存中…' : '保存'}</button>
        </>}>
        <div className="form-grid">
          <div className="form-row">
            <label>花名 *</label>
            <input className="input" value={name} onChange={e => setName(e.target.value)} placeholder="如：老王" maxLength={32} />
          </div>
          <div className="form-row">
            <label>模型名 *</label>
            <input className="input" value={modelName} onChange={e => setModelName(e.target.value)} placeholder="如：gpt-4o-mini / qwen-plus" />
          </div>
        </div>
        <div className="form-row">
          <label>人设描述</label>
          <input className="input" value={description} onChange={e => setDescription(e.target.value)} placeholder="一句话描述这个 Agent 的性格与擅长领域" maxLength={255} />
        </div>
        <div className="form-row">
          <label>Base URL *</label>
          <input className="input" value={baseUrl} onChange={e => setBaseUrl(e.target.value)} placeholder="如：https://api.openai.com" />
        </div>
        <div className="form-row">
          <label>API Key {editingId === null ? '*' : '（留空表示不修改）'}</label>
          <input className="input" type="password" value={apiKey} onChange={e => setApiKey(e.target.value)} placeholder={editingId === null ? 'sk-…' : '留空则保持原 Key 不变'} autoComplete="new-password" />
        </div>
        <div className="form-row">
          <label>系统提示词（人设 Prompt）</label>
          <textarea className="input" rows={5} value={systemPrompt} onChange={e => setSystemPrompt(e.target.value)} placeholder="你是一位经验丰富的后端工程师，说话直接，喜欢结合生产实践举例…" />
        </div>
      </Modal>
    </div>
  );
}
