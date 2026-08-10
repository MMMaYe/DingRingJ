import { useState, useEffect, useCallback, useMemo } from 'react';
import Sidebar, { IconPlus } from '../../components/Sidebar';
import Modal from '../../components/Modal';
import { toast } from '../../components/Toast';
import { API } from '../../api';
import type { AgentDTO, SaveSkillRequest, SkillDTO } from '../../types';
import './style.css';

/** 当前工具注册表（SkillToolkitFactory 中 @Tool 方法名），未知工具装配时会被跳过 */
const TOOL_OPTIONS = [
  { name: 'searchKnowledge', label: '知识库检索', desc: '搜索内部知识库，引用权威资料' },
  { name: 'queryUserProfile', label: '用户画像', desc: '查询用户表达习惯与思考方式' },
  { name: 'queryTopicHistory', label: '历史结论', desc: '查询群内已结束讨论的结论' },
];

const SCOPE_META = {
  GLOBAL: { label: '全局', className: 'tag' },
  AGENT: { label: '绑定 Agent', className: 'tag tag--neutral' },
} as const;

export default function SkillsPage() {
  const [skills, setSkills] = useState<SkillDTO[]>([]);
  const [agents, setAgents] = useState<AgentDTO[]>([]);
  const [keyword, setKeyword] = useState('');
  const [showModal, setShowModal] = useState(false);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [submitting, setSubmitting] = useState(false);

  // form
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [toolNames, setToolNames] = useState<string[]>([]);
  const [systemPrompt, setSystemPrompt] = useState('');
  const [scope, setScope] = useState<'GLOBAL' | 'AGENT'>('GLOBAL');
  const [agentId, setAgentId] = useState<number | ''>('');
  const [status, setStatus] = useState<'ACTIVE' | 'INACTIVE'>('ACTIVE');

  const filtered = useMemo(() => {
    const kw = keyword.trim().toLowerCase();
    if (!kw) return skills;
    return skills.filter(s =>
      s.name.toLowerCase().includes(kw) ||
      (s.description || '').toLowerCase().includes(kw) ||
      s.toolNameList.some(t => t.toLowerCase().includes(kw))
    );
  }, [skills, keyword]);

  const stats = useMemo(() => ({
    total: skills.length,
    active: skills.filter(s => s.status === 'ACTIVE').length,
    global: skills.filter(s => s.scope === 'GLOBAL').length,
  }), [skills]);

  const loadSkills = useCallback(async () => {
    try { setSkills(await API.get<SkillDTO[]>('/api/skills')); } catch (e: any) { toast(e.message, 'error'); }
  }, []);

  const loadAgents = useCallback(async () => {
    try { setAgents(await API.get<AgentDTO[]>('/api/agents')); } catch (e: any) { toast(e.message, 'error'); }
  }, []);

  useEffect(() => { loadSkills(); loadAgents(); }, [loadSkills, loadAgents]);

  const agentName = useCallback((id: number | null) => {
    if (id == null) return '';
    return agents.find(a => a.id === id)?.name ?? `#${id}`;
  }, [agents]);

  const openCreate = useCallback(() => {
    setEditingId(null);
    setName(''); setDescription(''); setToolNames([]); setSystemPrompt('');
    setScope('GLOBAL'); setAgentId(''); setStatus('ACTIVE');
    setShowModal(true);
  }, []);

  const openEdit = useCallback(async (id: number) => {
    setEditingId(id);
    setShowModal(true);
    try {
      const detail = await API.get<SkillDTO>(`/api/skills/${id}`);
      setName(detail.name);
      setDescription(detail.description || '');
      setToolNames(detail.toolNameList);
      setSystemPrompt(detail.systemPrompt || '');
      setScope(detail.scope);
      setAgentId(detail.agentId ?? '');
      setStatus(detail.status);
    } catch (e: any) {
      toast(e.message || '加载技能失败', 'error');
      setShowModal(false);
    }
  }, []);

  const submit = useCallback(async () => {
    if (!name.trim()) { toast('请输入技能名称', 'error'); return; }
    if (scope === 'AGENT' && agentId === '') { toast('绑定 Agent 作用域需选择目标 Agent', 'error'); return; }

    const body: SaveSkillRequest = {
      name: name.trim(),
      description: description.trim(),
      toolNames: toolNames.join(','),
      systemPrompt: systemPrompt.trim(),
      scope,
      agentId: scope === 'AGENT' ? Number(agentId) : null,
      status,
    };
    setSubmitting(true);
    try {
      if (editingId === null) {
        await API.post('/api/skills', body);
        toast('技能创建成功', 'success');
      } else {
        await API.put(`/api/skills/${editingId}`, body);
        toast('技能已更新', 'success');
      }
      setShowModal(false);
      await loadSkills();
    } catch (e: any) { toast(e.message, 'error'); }
    finally { setSubmitting(false); }
  }, [name, description, toolNames, systemPrompt, scope, agentId, status, editingId, loadSkills]);

  const toggleStatus = useCallback(async (skill: SkillDTO) => {
    const next: 'ACTIVE' | 'INACTIVE' = skill.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE';
    try {
      await API.put(`/api/skills/${skill.id}`, {
        name: skill.name,
        description: skill.description || '',
        toolNames: skill.toolNames || '',
        systemPrompt: skill.systemPrompt || '',
        scope: skill.scope,
        agentId: skill.agentId,
        status: next,
      } satisfies SaveSkillRequest);
      toast(next === 'ACTIVE' ? `已启用「${skill.name}」` : `已停用「${skill.name}」`, 'success');
      await loadSkills();
    } catch (e: any) { toast(e.message, 'error'); }
  }, [loadSkills]);

  const remove = useCallback(async (skill: SkillDTO) => {
    if (!window.confirm(`确认删除技能「${skill.name}」？删除后挂载该技能的 Agent 将不再获得此能力。`)) return;
    try {
      await API.del(`/api/skills/${skill.id}`);
      toast('技能已删除', 'success');
      await loadSkills();
    } catch (e: any) { toast(e.message, 'error'); }
  }, [loadSkills]);

  return (
    <div className="app-shell">
      <Sidebar footer={<button className="sidebar__new-group" onClick={openCreate}><IconPlus /> 新建 SKILL</button>} />

      <section className="page page--editorial">
        <header className="page__head">
          <div className="page__head-meta">
            <span className="page__eyebrow">Skills</span>
            <span className="page__head-rule" aria-hidden />
            <span className="page__head-id">No. {String(stats.total).padStart(2, '0')}</span>
          </div>
          <div className="page__head-main">
            <div className="page__head-title-row">
              <h1 className="page__title-serif">SKILL 管理</h1>
              <button className="btn btn--brand page__head-cta" onClick={openCreate}><IconPlus /> 新建 SKILL</button>
            </div>
            <p className="page__lead">技能 = 工具组 + 附加系统提示词。配置后即可为 Agent 动态装配能力，如画图、检索知识库</p>
          </div>
          <div className="page__head-actions">
            <div className="page__search">
              <svg className="page__search-icon" width="14" height="14" viewBox="0 0 14 14" fill="none"><circle cx="6" cy="6" r="4" stroke="currentColor" strokeWidth="1.2"/><path d="M9.5 9.5L12.5 12.5" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round"/></svg>
              <input
                type="text"
                className="page__search-input"
                placeholder="搜索 SKILL（名称 / 工具 / 描述）..."
                value={keyword}
                onChange={e => setKeyword(e.target.value)}
              />
            </div>
          </div>
        </header>

        <div className="page__stats">
          <div className="stat-card">
            <span className="stat-card__num">{stats.total}</span>
            <span className="stat-card__label">个技能</span>
            <span className="stat-card__bar" aria-hidden />
          </div>
          <div className="stat-card">
            <span className="stat-card__num">{stats.active}</span>
            <span className="stat-card__label">个启用中</span>
            <span className="stat-card__bar stat-card__bar--accent" aria-hidden />
          </div>
          <div className="stat-card">
            <span className="stat-card__num">{stats.global}</span>
            <span className="stat-card__label">个全局技能</span>
            <span className="stat-card__bar stat-card__bar--violet" aria-hidden />
          </div>
        </div>

        <div className="page__body">
          <div className="skill-list">
            {!skills.length ? (
              <div className="empty empty--editorial">
                <div className="empty__icon">🧩</div>
                <div className="empty__title">还没有 SKILL</div>
                <div className="empty__hint">点击右上方「新建 SKILL」沉淀第一个能力，如画图、知识库检索</div>
              </div>
            ) : !filtered.length ? (
              <div className="empty empty--editorial">
                <div className="empty__icon">🔍</div>
                <div className="empty__title">没有匹配「{keyword}」的 SKILL</div>
              </div>
            ) : filtered.map((s, idx) => (
              <article key={s.id} className="skill-card" style={{ animationDelay: `${idx * 40}ms` }}>
                <div className="skill-card__index" aria-hidden>{String(idx + 1).padStart(2, '0')}</div>
                <div className="skill-card__body">
                  <div className="skill-card__name-row">
                    <span className="skill-card__name">{s.name}</span>
                    <span className={SCOPE_META[s.scope].className}>
                      {s.scope === 'AGENT' ? `绑定 ${agentName(s.agentId)}` : SCOPE_META[s.scope].label}
                    </span>
                    <span className={s.status === 'ACTIVE' ? 'tag tag--success' : 'tag tag--muted'}>
                      {s.status === 'ACTIVE' ? '启用中' : '已停用'}
                    </span>
                  </div>
                  <div className="skill-card__desc">{s.description || '（无描述）'}</div>
                  <div className="skill-card__tags">
                    {s.toolNameList.length ? s.toolNameList.map(t => (
                      <span key={t} className="tag tag--code">{t}</span>
                    )) : (
                      <span className="tag tag--ghost">纯提示词技能</span>
                    )}
                  </div>
                  {s.systemPrompt && (
                    <div className="skill-card__prompt">
                      <span className="skill-card__prompt-label">Prompt</span>
                      <span className="skill-card__prompt-text">{s.systemPrompt}</span>
                    </div>
                  )}
                </div>
                <div className="skill-card__right">
                  <div className="skill-card__actions">
                    <button className="btn btn--ghost" onClick={() => openEdit(s.id)}>配置</button>
                    <button className="btn btn--ghost" onClick={() => toggleStatus(s)}>
                      {s.status === 'ACTIVE' ? '停用' : '启用'}
                    </button>
                    <button className="btn btn--danger" onClick={() => remove(s)}>删除</button>
                  </div>
                  <span className="skill-card__meta">更新于 {new Date(s.updateTime || s.createTime).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })}</span>
                </div>
              </article>
            ))}
          </div>
        </div>
      </section>

      <Modal
        open={showModal}
        onClose={() => setShowModal(false)}
        title={editingId === null ? '新建 SKILL' : '编辑 SKILL'}
        eyebrow={editingId === null ? 'Create' : 'Edit'}
        subtitle={editingId === null ? '沉淀一个能力：工具组 + 附加系统提示词' : `正在配置 ${name}`}
        width={560}
        footer={<>
          <button className="btn btn--ghost" onClick={() => setShowModal(false)} disabled={submitting}>取消</button>
          <button className="btn btn--brand" disabled={submitting} onClick={submit}>
            {submitting ? '保存中…' : '保存'}
          </button>
        </>}
      >
        <div className="form-row">
          <label>技能名称 *</label>
          <input className="input" value={name} onChange={e => setName(e.target.value)} placeholder="如：diagram / rag-search（唯一标识）" maxLength={64} />
        </div>
        <div className="form-row">
          <label>描述</label>
          <input className="input" value={description} onChange={e => setDescription(e.target.value)} placeholder="一句话说明该技能的用途，会注入到 Agent 提示词" maxLength={255} />
        </div>
        <div className="form-row">
          <label>工具集</label>
          <div className="tool-picker">
            {TOOL_OPTIONS.map(t => {
              const checked = toolNames.includes(t.name);
              return (
                <button
                  key={t.name}
                  type="button"
                  className={`tool-option${checked ? ' is-active' : ''}`}
                  onClick={() => setToolNames(prev => checked ? prev.filter(n => n !== t.name) : [...prev, t.name])}
                >
                  <span className="tool-option__check">{checked ? '✓' : ''}</span>
                  <span className="tool-option__title">{t.label}</span>
                  <span className="tool-option__name">{t.name}</span>
                  <span className="tool-option__desc">{t.desc}</span>
                </button>
              );
            })}
          </div>
          <span className="form-hint">勾选工具组装配到 Agent；纯提示词技能（如画图规范）可不勾选</span>
        </div>
        <div className="form-row">
          <label>作用域 *</label>
          <div className="scope-picker">
            <button
              type="button"
              className={`scope-option${scope === 'GLOBAL' ? ' is-active' : ''}`}
              onClick={() => { setScope('GLOBAL'); setAgentId(''); }}
            >
              <span className="scope-option__title">全局</span>
              <span className="scope-option__desc">所有 Agent 可用</span>
            </button>
            <button
              type="button"
              className={`scope-option${scope === 'AGENT' ? ' is-active' : ''}`}
              onClick={() => setScope('AGENT')}
            >
              <span className="scope-option__title">绑定 Agent</span>
              <span className="scope-option__desc">仅指定 Agent 可用</span>
            </button>
          </div>
        </div>
        {scope === 'AGENT' && (
          <div className="form-row">
            <label>绑定 Agent *</label>
            <select className="input" value={agentId} onChange={e => setAgentId(e.target.value === '' ? '' : Number(e.target.value))}>
              <option value="">请选择 Agent</option>
              {agents.map(a => <option key={a.id} value={a.id}>{a.name} · {a.modelName}</option>)}
            </select>
          </div>
        )}
        <div className="form-row">
          <label>系统提示词（挂载到 Agent 人设之后）</label>
          <textarea className="input" rows={5} value={systemPrompt} onChange={e => setSystemPrompt(e.target.value)} placeholder="技能要求、约束与使用方式，如画图技能的 Excalidraw 风格规范…" />
        </div>
        <div className="form-row">
          <label>状态</label>
          <div className="scope-picker">
            <button
              type="button"
              className={`scope-option${status === 'ACTIVE' ? ' is-active' : ''}`}
              onClick={() => setStatus('ACTIVE')}
            >
              <span className="scope-option__title">启用</span>
              <span className="scope-option__desc">立即对 Agent 生效</span>
            </button>
            <button
              type="button"
              className={`scope-option${status === 'INACTIVE' ? ' is-active' : ''}`}
              onClick={() => setStatus('INACTIVE')}
            >
              <span className="scope-option__title">停用</span>
              <span className="scope-option__desc">暂时挂起，不注入提示词</span>
            </button>
          </div>
        </div>
      </Modal>
    </div>
  );
}
