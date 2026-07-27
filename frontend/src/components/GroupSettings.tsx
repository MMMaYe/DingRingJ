import { useState, useEffect, useMemo } from 'react';
import Avatar from './Avatar';
import { API } from '../api';
import { toast } from './Toast';
import type { GroupDetail, AgentDTO, UpdateMembersRequest } from '../types';

interface GroupSettingsProps {
  open: boolean;
  onClose: () => void;
  group: GroupDetail;
  agents: AgentDTO[];
  /** 成员更新成功后的回调，传入最新的群详情 */
  onUpdated: (detail: GroupDetail) => void;
}

/**
 * 群设置抽屉（成员管理）— Editorial 风格。
 *
 * 设计要点：
 * - 顶部「刊头」：大号衬线群名 + 头像堆叠 + 装饰性元数据
 * - 章节式排版：左侧大号编号 + 细横线 + 标题描述，呈现目录感
 * - 成员/专家卡片：印章式选中态、错峰入场动画
 * - 改动检测：精确计数「N 项改动」徽章 + 脉冲提示
 * - 专家与普通成员互斥：专家卡片不可勾选为普通成员（锁态）
 */
export default function GroupSettings({ open, onClose, group, agents, onUpdated }: GroupSettingsProps) {
  const [selectedAgents, setSelectedAgents] = useState<Set<number>>(new Set());
  const [expertId, setExpertId] = useState<number>(0);
  const [saving, setSaving] = useState(false);

  // 打开时根据当前群成员初始化勾选状态，并作为改动检测基线
  useEffect(() => {
    if (!open) return;
    const members = group.members.filter(m => m.type === 'AGENT');
    const initMembers = new Set(members.filter(m => m.role === 'MEMBER').map(m => m.id));
    const initExpert = members.find(m => m.role === 'EXPERT')?.id ?? 0;
    setSelectedAgents(initMembers);
    setExpertId(initExpert);
  }, [open, group]);

  // 改动检测：精确计算「成员变化数」与「专家是否变更」
  const { memberDelta, expertChanged, dirtyCount } = useMemo(() => {
    const members = group.members.filter(m => m.type === 'AGENT');
    const baseMembers = new Set(members.filter(m => m.role === 'MEMBER').map(m => m.id));
    const baseExpert = members.find(m => m.role === 'EXPERT')?.id ?? 0;

    let added = 0, removed = 0;
    for (const id of selectedAgents) if (!baseMembers.has(id)) added++;
    for (const id of baseMembers) if (!selectedAgents.has(id)) removed++;
    const memberDelta = added + removed;
    const expertChanged = expertId !== baseExpert;
    const dirtyCount = memberDelta + (expertChanged ? 1 : 0);
    return { memberDelta, expertChanged, dirtyCount };
  }, [selectedAgents, expertId, group]);

  if (!open) return null;

  const owner = group.members.find(m => m.type === 'USER');
  const agentMembers = group.members.filter(m => m.type === 'AGENT');
  const agentCount = agentMembers.length;

  // 头像堆叠：取前 4 位 Agent + 群主
  const stackSources = [owner, ...agentMembers.slice(0, 4)].filter(Boolean) as { name: string }[];
  const stackOverflow = Math.max(0, group.members.length - stackSources.length);

  const toggleAgent = (id: number) => {
    if (id === expertId) return; // 专家不可作为普通成员勾选
    setSelectedAgents(prev => {
      const next = new Set(prev);
      next.has(id) ? next.delete(id) : next.add(id);
      return next;
    });
  };

  const handleSave = async () => {
    if (selectedAgents.size === 0) {
      toast('至少保留一个普通成员 Agent', 'error');
      return;
    }
    if (!expertId) {
      toast('请指定专家 Agent', 'error');
      return;
    }
    setSaving(true);
    try {
      const payload: UpdateMembersRequest = {
        agentIds: [...selectedAgents],
        expertAgentId: expertId,
      };
      const detail = await API.put<GroupDetail>(`/api/groups/${group.id}/members`, payload);
      toast('成员已更新', 'success');
      onUpdated(detail);
      onClose();
    } catch (e) {
      toast(e instanceof Error ? e.message : '保存失败', 'error');
    } finally {
      setSaving(false);
    }
  };

  return (
    <>
      <div className="drawer-mask" onClick={e => { if (e.target === e.currentTarget) onClose(); }} />
      <aside className="drawer drawer--editorial" role="dialog" aria-label="群设置">
        <header className="drawer__header">
          <div className="drawer__header-meta">
            <span className="drawer__eyebrow">Group Settings</span>
            <span className="drawer__header-rule" aria-hidden />
            <span className="drawer__header-id">No. {String(group.id).padStart(3, '0')}</span>
          </div>
          <button className="drawer__close" onClick={onClose} title="关闭 (Esc)" aria-label="关闭">
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none"><path d="M3 3l10 10M13 3L3 13" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round"/></svg>
          </button>
        </header>

        <div className="drawer__body">
          {/* 群刊头：大号衬线群名 + 头像堆叠 + 元数据 */}
          <div className="group-masthead">
            <div className="group-masthead__title-row">
              <h2 className="group-masthead__title">{group.name}</h2>
              <span className="group-masthead__count">{agentCount + 1}</span>
            </div>
            <div className="group-masthead__sub">
              <div className="avatar-stack" aria-label="成员头像">
                {stackSources.map((m, i) => (
                  <div key={i} className="avatar-stack__item" style={{ zIndex: stackSources.length - i }}>
                    <Avatar name={m.name} size="sm" />
                  </div>
                ))}
                {stackOverflow > 0 && (
                  <div className="avatar-stack__item avatar-stack__item--overflow">
                    +{stackOverflow}
                  </div>
                )}
              </div>
              <div className="group-masthead__meta">
                <span className="group-masthead__meta-item">{agentCount} 位 Agent</span>
                <span className="group-masthead__meta-sep">/</span>
                <span className="group-masthead__meta-item">群主 {owner?.name ?? '—'}</span>
              </div>
            </div>
          </div>

          {/* 章节 01：讨论成员 */}
          <section className="chapter">
            <header className="chapter__head">
              <span className="chapter__num">01</span>
              <div className="chapter__title-wrap">
                <h3 className="chapter__title">讨论成员</h3>
                <span className="chapter__hint">参与普通讨论的 Agent，可多选</span>
              </div>
              <span className="chapter__count">{selectedAgents.size}<span className="chapter__count-sep">/</span>{agents.length}</span>
            </header>
            <div className="member-grid member-grid--drawer">
              {agents.map((a, idx) => {
                const checked = selectedAgents.has(a.id);
                const order = checked ? [...selectedAgents].indexOf(a.id) + 1 : 0;
                const isExpert = a.id === expertId;
                return (
                  <div
                    key={a.id}
                    className={`member-card${checked ? ' is-checked' : ''}${isExpert ? ' is-locked' : ''}`}
                    style={{ animationDelay: `${idx * 28}ms` }}
                    onClick={() => toggleAgent(a.id)}
                    role="checkbox"
                    aria-checked={checked}
                    aria-disabled={isExpert}
                    tabIndex={isExpert ? -1 : 0}
                    onKeyDown={e => { if (!isExpert && (e.key === ' ' || e.key === 'Enter')) { e.preventDefault(); toggleAgent(a.id); } }}
                  >
                    {checked && <span className="member-card__order" aria-hidden>{order}</span>}
                    {checked && (
                      <span className="member-card__check" aria-hidden>
                        <svg width="12" height="12" viewBox="0 0 12 12" fill="none"><path d="M2.5 6.5l2.5 2.5 4.5-5" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"/></svg>
                      </span>
                    )}
                    <Avatar name={a.name} size="sm" />
                    <div className="member-card__meta">
                      <div className="member-card__name">{a.name}</div>
                      <div className="member-card__desc">{a.description || a.modelName}</div>
                    </div>
                    {isExpert && <span className="tag tag--warning member-card__role-tag">已选为专家</span>}
                  </div>
                );
              })}
            </div>
          </section>

          {/* 章节 02：专家 Agent */}
          <section className="chapter">
            <header className="chapter__head">
              <span className="chapter__num">02</span>
              <div className="chapter__title-wrap">
                <h3 className="chapter__title">专家 Agent</h3>
                <span className="chapter__hint">负责讨论结束时的总结陈词，不参与普通讨论</span>
              </div>
            </header>
            <div className="expert-grid expert-grid--drawer">
              {agents.map(a => {
                const selected = a.id === expertId;
                const inMembers = selectedAgents.has(a.id);
                return (
                  <div
                    key={a.id}
                    className={`expert-card${selected ? ' is-selected' : ''}${inMembers ? ' is-conflict' : ''}`}
                    onClick={() => {
                      if (inMembers) return;
                      setExpertId(a.id);
                    }}
                    title={inMembers ? '请先取消该 Agent 的普通成员勾选' : ''}
                  >
                    <Avatar name={a.name} size="sm" />
                    <div className="expert-card__meta">
                      <div className="expert-card__name">{a.name}</div>
                      <div className="expert-card__desc">{a.description || a.modelName}</div>
                    </div>
                    {selected ? (
                      <span className="tag tag--warning">已选专家</span>
                    ) : inMembers ? (
                      <span className="tag tag--neutral">成员中</span>
                    ) : (
                      <span className="expert-card__pick">点击提名</span>
                    )}
                  </div>
                );
              })}
            </div>
          </section>
        </div>

        <footer className="drawer__footer">
          <div className="drawer__dirty-zone">
            {dirtyCount > 0 ? (
              <span className="dirty-badge" role="status">
                <span className="dirty-badge__dot" aria-hidden />
                {dirtyCount} 项改动未保存
                <span className="dirty-badge__detail">
                  {memberDelta > 0 && `成员 ${memberDelta}`}
                  {memberDelta > 0 && expertChanged && ' · '}
                  {expertChanged && '专家已变更'}
                </span>
              </span>
            ) : (
              <span className="drawer__saved-hint">
                <svg width="12" height="12" viewBox="0 0 12 12" fill="none"><path d="M2.5 6.5l2.5 2.5 4.5-5" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"/></svg>
                已是最新
              </span>
            )}
          </div>
          <div className="drawer__footer-actions">
            <button className="btn btn--ghost" onClick={onClose} disabled={saving}>取消</button>
            <button
              className={`btn btn--brand${dirtyCount > 0 ? ' is-dirty' : ''}`}
              onClick={handleSave}
              disabled={saving || dirtyCount === 0}
            >
              {saving ? '保存中…' : dirtyCount > 0 ? '保存改动' : '已保存'}
            </button>
          </div>
        </footer>
      </aside>
    </>
  );
}
