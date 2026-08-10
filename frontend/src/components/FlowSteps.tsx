import { Fragment } from 'react';
import type { FlowEventPayload } from '../types';
import './FlowSteps.css';

/**
 * 图流程步骤条：把后端 FLOW_EVENT 节点事件渲染成横向步骤链。
 * <p>节点按图内固定顺序展示，仅渲染本次流程实际经过的节点；
 * 最后一个节点为"正在执行"（脉冲高亮 + 耗时），收到 END 后整链定格为已完成。
 */
const NODE_ORDER = [
  'preprocess',
  'intent-classify',
  'ensure-topic',
  'chat',
  'discuss',
  'work',
  'conclude',
  'sediment',
  'profile-extract',
];

export default function FlowSteps({ steps }: { steps: FlowEventPayload[] }) {
  if (!steps.length) return null;

  // 按事件顺序去重，保留节点最后一次状态（error/__END__ 不参与步骤展示）
  const visited = new Map<string, FlowEventPayload>();
  steps.forEach(s => { if (s.node !== 'error' && s.node !== '__END__') visited.set(s.node, s); });

  const last = steps[steps.length - 1];
  const errored = last.status === 'ERROR';
  // 后端 END 节点事件 node="__END__"、status="END"，以 status 判断流程是否结束
  const done = steps.some(s => s.status === 'END');
  const nodes = NODE_ORDER.filter(n => visited.has(n));
  if (nodes.length === 0) return null;

  return (
    <div className={`flow-steps${errored ? ' flow-steps--error' : ''}`}>
      {nodes.map((node, idx) => {
        const ev = visited.get(node)!;
        const isLast = idx === nodes.length - 1;
        const active = isLast && !done && !errored;
        return (
          <Fragment key={node}>
            <div className={`flow-step${active ? ' is-active' : ''}`}>
              <span className="flow-step__dot" />
              <span className="flow-step__name">{ev.nodeName}</span>
              {active && <span className="flow-step__ms">{ev.elapsedMs}ms</span>}
            </div>
            {idx < nodes.length - 1 && <span className="flow-step__arrow">→</span>}
          </Fragment>
        );
      })}
      {errored && <div className="flow-steps__error">流程异常：{last.message ?? '未知错误'}</div>}
    </div>
  );
}
