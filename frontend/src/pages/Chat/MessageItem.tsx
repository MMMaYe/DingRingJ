import { memo, useMemo } from 'react';
import Avatar from '../../components/Avatar';
import { escapeHtml, formatTime } from './utils';
import type { MessageDTO } from '../../types';

interface MessageItemProps {
  m: MessageDTO;
  /** 发送者是否为专家 Agent */
  expert: boolean;
  /** 是否命中聊天内搜索 */
  isMatch: boolean;
  /** 是否为当前定位的搜索结果 */
  isCurrent: boolean;
  /** 群内 Agent 花名列表（父组件 memo 化，引用稳定） */
  agentNames: string[];
  onReply: (m: MessageDTO) => void;
}

/**
 * 单条消息（memo 化）。
 *
 * 性能关键：输入框每次击键都会触发 ChatPage 重渲染，
 * memo + 稳定 props 让未变化的消息跳过 @提及正则替换与 DOM diff，
 * 长列表（200 条）下输入不再掉帧。
 */
const MessageItem = memo(function MessageItem({ m, expert, isMatch, isCurrent, agentNames, onReply }: MessageItemProps) {
  const self = m.senderType === 'USER';

  // @提及高亮 HTML 只在内容或成员名单变化时重算
  const html = useMemo(() => {
    if (m.senderType === 'SYSTEM') return '';
    let h = escapeHtml(m.content);
    agentNames.forEach(name => {
      h = h.replaceAll('@' + escapeHtml(name), `<span class="mention">@${escapeHtml(name)}</span>`);
    });
    return h;
  }, [m.senderType, m.content, agentNames]);

  if (m.senderType === 'SYSTEM') {
    return (
      <div className={`msg-system${m.content.includes('【讨论结论】') ? ' msg-system--conclusion' : ''}`}>
        {m.content}
      </div>
    );
  }

  return (
    <div data-msg-id={m.id} className={`msg${self ? ' msg--self' : ''}${isCurrent ? ' msg--search-current' : isMatch ? ' msg--search-match' : ''}`}>
      <Avatar name={m.senderName} />
      <div className="msg__main">
        <div className="msg__meta">
          <span className="msg__sender">
            {m.senderName}
            {expert && <span className="tag tag--warning">专家</span>}
          </span>
          <span className="msg__time">{formatTime(m.createTime)}</span>
        </div>
        {m.replyToMessageId && (
          <div className="msg__reply">↩ {m.replyToSenderName}: {m.replyToContent}</div>
        )}
        <div className="msg__bubble" dangerouslySetInnerHTML={{ __html: html }} />
        {!self && (
          <div className="msg__actions">
            <button className="msg__action-btn" onClick={() => onReply(m)}>引用回复</button>
          </div>
        )}
      </div>
    </div>
  );
});

export default MessageItem;
