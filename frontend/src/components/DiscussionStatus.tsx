/**
 * 讨论状态横幅:位于 chat-header 和 chat-body 之间。
 * 根据 TOPIC_STATUS WS 事件实时更新,展示讨论动态。
 *
 * 设计要点(方案 6.3.7):
 * - discussMode 映射为用户可读的状态标签 + 颜色
 * - DIVERGE 模式展示发散进度条(N/M 轮)
 * - CONCLUDE_PROPOSED 展示"确认结束"/"继续讨论"按钮
 * - restartHint 非空时展示话题重启提示
 * - 无 activeTopic 时隐藏(闲聊态不需要状态横幅)
 */

import './DiscussionStatus.css';

export type DiscussMode = 'CONVERGE' | 'DIVERGE' | 'WAIT' | 'CONCLUDE_PROPOSED' | 'CONCLUDE' | '';

export interface TopicStatus {
  discussMode: DiscussMode;
  topicTitle: string;
  divergeRounds: number;
  maxDivergeRounds: number;
  restartHint: string;
}

interface DiscussionStatusProps {
  status: TopicStatus | null;
  onConcludeConfirm: () => void;
  onConcludeReject: () => void;
  onDismissHint: () => void;
}

/** discussMode -> {标签, 颜色变量, 图标} */
const MODE_CONFIG: Record<DiscussMode, { label: string; color: string; icon: string }> = {
  CONVERGE:           { label: '讨论中',         color: 'var(--color-primary)',       icon: '💬' },
  DIVERGE:            { label: '发散探索',       color: 'var(--status-primary-default)', icon: '🔍' },
  WAIT:               { label: '等待你回答',     color: 'var(--status-alert-default)',  icon: '⏳' },
  CONCLUDE_PROPOSED:  { label: '提议收束',       color: 'var(--accent-violet)',         icon: '✅' },
  CONCLUDE:           { label: '正在收束',       color: 'var(--accent-violet)',         icon: '📝' },
  '':                 { label: '',               color: '',                             icon: '' },
};

export default function DiscussionStatus({
  status,
  onConcludeConfirm,
  onConcludeReject,
  onDismissHint,
}: DiscussionStatusProps) {
  if (!status || !status.discussMode) return null;

  const config = MODE_CONFIG[status.discussMode];
  if (!config.label) return null;

  const isDiverge = status.discussMode === 'DIVERGE';
  const isConcludeProposed = status.discussMode === 'CONCLUDE_PROPOSED';
  const hasHint = !!status.restartHint;

  return (
    <div className="discuss-status">
      {/* 状态标签 + 发散进度 */}
      <div className="discuss-status__bar" style={{ '--status-color': config.color } as React.CSSProperties}>
        <span className="discuss-status__icon">{config.icon}</span>
        <span className="discuss-status__label">{config.label}</span>

        {isDiverge && (
          <span className="discuss-status__diverge">
            <span className="discuss-status__diverge-text">
              第 {status.divergeRounds}/{status.maxDivergeRounds} 轮
            </span>
            <span className="discuss-status__diverge-track">
              <span
                className="discuss-status__diverge-fill"
                style={{ width: `${(status.divergeRounds / status.maxDivergeRounds) * 100}%` }}
              />
            </span>
          </span>
        )}
      </div>

      {/* 话题重启提示 */}
      {hasHint && (
        <div className="discuss-status__hint">
          <span className="discuss-status__hint-text">{status.restartHint}</span>
          <button className="discuss-status__hint-close" onClick={onDismissHint} title="关闭提示">
            <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
              <path d="M3 3l6 6M9 3l-6 6" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round"/>
            </svg>
          </button>
        </div>
      )}

      {/* 收束确认按钮 */}
      {isConcludeProposed && (
        <div className="discuss-status__actions">
          <button className="btn btn--brand btn--sm" onClick={onConcludeConfirm}>
            确认结束
          </button>
          <button className="btn btn--ghost btn--sm" onClick={onConcludeReject}>
            继续讨论
          </button>
        </div>
      )}
    </div>
  );
}
