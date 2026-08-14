// 综合 E2E：整体 workflow 流转测试（群12）
// 链路：DISCUSS(续谈活跃话题) -> CONCLUDE(收束关题) -> WORK(任务执行) -> CHAT(闲聊)
// 阶段完成判定：收到事件后静默 5s 视为该阶段流程结束，自动推进下一阶段
const ws = new WebSocket('ws://localhost:8080/ws/chat?groupId=12');
const t0 = Date.now();
const phases = [
  { key: 'DISCUSS', content: '关于分布式锁，大家觉得 Redis 实现和数据库实现哪个更可靠？' },
  { key: 'CONCLUDE', content: '我觉得讨论得差不多了，@小林 辛苦你总结一下这个话题吧' },
  { key: 'WORK', content: '帮我整理一份分布式锁选型对比清单，包括适用场景和优缺点' },
  { key: 'CHAT', content: '哈哈今天天气真不错，适合喝杯咖啡' },
];
const results = {};
let phaseIdx = 0;
let idleTimer = null;
let flowNodes = []; // 当前阶段 FLOW_EVENT 节点序列
let closedTopic = null;
const SLEEP_MS = 5000;

function log(msg) { console.log('+' + String(Date.now() - t0).padStart(6) + 'ms ' + msg); }

function resetIdle() {
  clearTimeout(idleTimer);
  idleTimer = setTimeout(onPhaseIdle, SLEEP_MS);
}
function armIdle() {
  clearTimeout(idleTimer);
  idleTimer = setTimeout(onPhaseIdle, 30000);
}

function onPhaseIdle() {
  const ph = phases[phaseIdx];
  if (!ph) return;
  const passed = closedTopic && ph.key === 'CONCLUDE';
  results[ph.key] = {
    nodes: [...flowNodes],
    closed: passed,
    errored: results[ph.key]?.errored || false,
  };
  log(`[IDLE] 阶段 ${ph.key} 静默结束，节点序列: ${flowNodes.join(' -> ') || '(无)'}${passed ? ' [已收束]' : ''}`);
  phaseIdx++;
  if (phaseIdx >= phases.length) {
    finish();
    return;
  }
  flowNodes = [];
  const next = phases[phaseIdx];
  log(`>>> 发送 ${next.key} 消息: ${next.content.slice(0, 30)}...`);
  ws.send(JSON.stringify({ type: 'SEND_MESSAGE', data: { content: next.content } }));
  armIdle();
}

function finish() {
  log('===== 汇总 =====');
  let allPass = true;
  for (const p of phases) {
    const r = results[p.key];
    const status = r.errored ? 'ERROR' : (p.key === 'CONCLUDE' ? (r.closed ? 'PASS' : 'WARN(未收束)') : 'PASS');
    if (r.errored || (p.key === 'CONCLUDE' && !r.closed)) allPass = false;
    log(`${p.key}: ${status} | 节点: ${(r.nodes || []).join(' -> ')}`);
  }
  log(allPass ? 'WORKFLOW E2E PASS' : 'WORKFLOW E2E 部分不顺利，详见上方汇总');
  ws.close();
  clearTimeout(idleTimer);
  process.exit(0);
}

ws.onopen = () => {
  log('已连接群12，开始阶段 DISCUSS');
  ws.send(JSON.stringify({ type: 'SEND_MESSAGE', data: { content: phases[0].content } }));
  armIdle();
};
ws.onmessage = (e) => {
  const msg = JSON.parse(e.data);
  const d = msg.data;
  const ph = phases[phaseIdx];
  if (msg.type === 'FLOW_EVENT') {
    const name = d.nodeName || d.node;
    if (name !== '__START__') flowNodes.push(name + '(' + d.status + ')');
    if (d.status === 'ERROR') {
      results[ph.key] = { ...(results[ph.key] || {}), errored: true };
      log(`[flow-ERROR] ${ph.key}: ${d.message}`);
    }
    log(`[flow] ${name} ${d.status} ${d.elapsedMs}ms`);
    if (ph) resetIdle();
  } else if (msg.type === 'NEW_MESSAGE') {
    log(`[msg] ${(d.senderName || d.senderId)}(${d.senderType}): ${String(d.content).slice(0, 50)}`);
    if (ph) resetIdle();
  } else if (msg.type === 'MESSAGE_COMPLETE') {
    log(`[COMPLETE] ${d.message.senderName}: ${String(d.message.content).slice(0, 50)}`);
    if (ph) resetIdle();
  } else if (msg.type === 'TOPIC_CLOSED') {
    closedTopic = { topicId: d.topicId, title: d.title };
    log(`[CLOSED] topicId=${d.topicId} title=${d.title}`);
    if (ph) resetIdle();
  } else if (msg.type === 'TOPIC_STATUS_CHANGED') {
    log(`[status] ${d.previousStatus} -> ${d.status}`);
    if (ph) resetIdle();
  } else if (msg.type === 'WORK_TASK_STARTED') {
    log(`[WORK_STARTED] agent=${d.agentName} supervisorMode=${d.supervisorMode}`);
    if (ph) resetIdle();
  } else if (msg.type === 'ERROR') {
    log(`[error] ${JSON.stringify(d)}`);
  }
};
ws.onerror = (e) => { log('WS error ' + e.message); };
setTimeout(() => { log('全局超时'); process.exit(2); }, 240000);
