import { useState, useEffect } from 'react';
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
 * 群设置抽屉（成员管理）。
 * - 右侧滑出，遮罩点击关闭
 * - 复用 member-pick 样式，与创建群弹窗的成员选择体验一致
 * - 整体覆盖语义：提交时用完整勾选状态替换后端成员配置
 */
export default function GroupSettings({ open, onClose, group, agents, onUpdated }: GroupSettingsProps) {
  const [selectedAgents, setSelectedAgents] = useState<Set<number>>(new Set());
  const [expertId, setExpertId] = useState<number>(0);
  const [saving, setSaving] = useState(false);

  // 打开时根据当前群成员初始化勾选状态
  useEffect(() => {
    if (!open) return;
    const members = group.members.filter(m => m.type === 'AGENT');
    setSelectedAgents(new Set(members.filter(m => m.role === 'MEMBER').map(m => m.id)));
    const expert = members.find(m => m.role === 'EXPERT');
    setExpertId(expert?.id ?? 0);
  }, [open, group]);

  if (!open) return null;

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
      <aside className="drawer">
        <header className="drawer__header">
          <span className="drawer__title">群设置</span>
          <button className="drawer__close" onClick={onClose} title="关闭">
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none"><path d="M3 3l10 10M13 3L3 13" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round"/></svg>
          </button>
        </header>

        <div className="drawer__body">
          {/* 群信息（只读） */}
          <div className="drawer__section">
            <div className="drawer__label">群名称</div>
            <div className="drawer__group-name">{group.name}</div>
            <div className="drawer__group-meta">
              共 {group.members.length} 人 · 群主：{group.members.find(m => m.type === 'USER')?.name ?? '—'}
            </div>
          </div>

          {/* 成员管理 */}
          <div className="drawer__section">
            <div className="drawer__label">成员管理</div>
            <div className="drawer__sublabel">普通讨论成员（可多选）</div>
            {agents.map(a => {
              const isExpert = a.id === expertId;
              const checked = selectedAgents.has(a.id);
              return (
                <div
                  key={a.id}
                  className={`member-pick${checked ? ' is-checked' : ''}${isExpert ? ' is-disabled' : ''}`}
                  onClick={() => toggleAgent(a.id)}
                >
                  <Avatar name={a.name} size="sm" />
                  <div className="member-pick__meta">
                    <div className="member-pick__name">{a.name}</div>
                    <div className="member-pick__desc">{a.description || a.modelName}</div>
                  </div>
                  {isExpert && <span className="tag tag--warning">专家</span>}
                </div>
              );
            })}
          </div>

          <div className="drawer__section">
            <div className="drawer__sublabel">指定专家 Agent（负责总结陈词，不参与普通讨论）</div>
            <select
              className="input"
              value={expertId}
              onChange={e => setExpertId(Number(e.target.value))}
            >
              {agents.map(a => <option key={a.id} value={a.id}>{a.name}</option>)}
            </select>
          </div>
        </div>

        <footer className="drawer__footer">
          <button className="btn btn--ghost" onClick={onClose} disabled={saving}>取消</button>
          <button className="btn btn--brand" onClick={handleSave} disabled={saving}>
            {saving ? '保存中…' : '保存'}
          </button>
        </footer>
      </aside>
    </>
  );
}
