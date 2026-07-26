import { useState, useEffect, useCallback } from 'react';
import Sidebar from '../../components/Sidebar';
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

  // form
  const [name, setName] = useState('');
  const [modelName, setModelName] = useState('');
  const [description, setDescription] = useState('');
  const [baseUrl, setBaseUrl] = useState('');
  const [apiKey, setApiKey] = useState('');
  const [systemPrompt, setSystemPrompt] = useState('');
  const [submitting, setSubmitting] = useState(false);

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
      <Sidebar footer={<button className="btn btn--brand btn--block" onClick={openCreate}>＋ 新建 Agent</button>} />

      <section className="page">
        <header className="page__header">
          <div>
            <div className="page__title">Agent 管理</div>
            <div className="page__subtitle">配置 AI 同学的模型接入与人设，创建后即可拉入群聊参与讨论</div>
          </div>
          <button className="btn btn--brand" onClick={openCreate}>＋ 新建 Agent</button>
        </header>
        <div className="page__body">
          <div className="agent-grid">
            {!agents.length ? (
              <div className="empty" style={{ gridColumn: '1/-1' }}>
                <div className="empty__icon">🤖</div>还没有 Agent，点击右上角「新建 Agent」创建第一位 AI 同学
              </div>
            ) : agents.map(a => (
              <div key={a.id} className="agent-card">
                <div className="agent-card__head">
                  <Avatar name={a.name} size="lg" />
                  <div style={{ minWidth: 0 }}>
                    <div className="agent-card__name">{a.name}</div>
                    <div className="agent-card__model">{a.modelName} · {shortUrl(a.baseUrl)}</div>
                  </div>
                </div>
                <div className="agent-card__desc">{a.description || '暂无人设描述'}</div>
                <div className="agent-card__prompt">{a.systemPrompt || '（未配置系统提示词）'}</div>
                <div className="agent-card__footer">
                  <span className="agent-card__time">更新于 {new Date(a.updateTime || a.createTime).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })}</span>
                  <button className="btn btn--ghost" onClick={() => openEdit(a)}>编辑</button>
                </div>
              </div>
            ))}
          </div>
        </div>
      </section>

      <Modal open={showModal} onClose={() => setShowModal(false)} title={editingId === null ? '新建 Agent' : `编辑 Agent · ${name}`} width={520}
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
