import { memo, useMemo } from 'react';
import Avatar from '../../components/Avatar';
import { formatTime, renderMarkdown } from './utils';
import type { MessageDTO } from '../../types';

interface MessageItemProps {
  m: MessageDTO;
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
const MessageItem = memo(function MessageItem({ m, isMatch, isCurrent, agentNames, onReply }: MessageItemProps) {
  const self = m.senderType === 'USER';

  // Markdown/HTML 渲染 + @提及高亮，只在内容或成员名单变化时重算
  const html = useMemo(() => {
    if (m.senderType === 'SYSTEM') return '';
    return renderMarkdown(m.content, agentNames);
  }, [m.senderType, m.content, agentNames]);

  if (m.senderType === 'SYSTEM') {
    const isConclusion = m.content.includes('【讨论结论】');
    // 结论系统消息含 STAR Markdown，同样走富文本渲染
    if (isConclusion) {
      return (
        <div
          className="msg-system msg-system--conclusion md-body"
          dangerouslySetInnerHTML={{ __html: renderMarkdown(m.content) }}
        />
      );
    }
    return <div className="msg-system">{m.content}</div>;
  }

  return (
    <div data-msg-id={m.id} className={`msg${self ? ' msg--self' : ''}${isCurrent ? ' msg--search-current' : isMatch ? ' msg--search-match' : ''}`}>
      <Avatar name={m.senderName} />
      <div className="msg__main">
        <div className="msg__meta">
          <span className="msg__sender">
            {m.senderName}
          </span>
          <span className="msg__time">{formatTime(m.createTime)}</span>
        </div>
        {m.replyToMessageId && (
          <div className="msg__reply">↩ {m.replyToSenderName}: {m.replyToContent}</div>
        )}
        <div className="msg__bubble md-body" dangerouslySetInnerHTML={{ __html: html }} />
        {/* 允许引用任何消息（含自己的）：SYSTEM 已在上方提前返回；流式半成品由外层 .msg-streaming 隐藏 */}
        <div className="msg__actions">
          <button className="msg__action-btn" onClick={() => onReply(m)}>引用回复</button>
        </div>
      </div>
    </div>
  );
});

export default MessageItem;
