// 综合 E2E v2：整体 workflow 流转测试（群12）
// 改进点：每条消息等待收到 __END__ FLOW_EVENT 并静默 3s 后才发下一条，
// 不再用固定 5s 静默判定（DISCUSS 阶段 LLM 发言可达 20s，固定时长会误判）。
// 阶段：CONCLUDE(收束) -> WORK(任务) -> CHAT(闲聊) -> DISCUSS(新建话题)
const ws = new WebSocket('ws://localhost:8080/ws/chat?groupId=12');
const t0 = Date.now();
const phases = [
  { key: 'CONCLUDE', content: '我觉得讨论得差不多了，@小林 辛苦你总结一下这个话题吧' },
  { key: 'WORK', content: '帮我整理一份分布式锁选型对比清单，包括适用场景和优缺点' },
  { key: 'CHAT', content: '哈哈今天天气真不错，适合喝杯咖啡' },
  { key: 'DISCUSS', content: '我们来聊聊 MySQL 索引失效的场景吧' },
];
const results = {};
let phaseIdx = 0;
let inPhase = false;
let endSeen = false;
let endTimer = null;
let flowNodes = [];
let closedTopic = null;
let errors = [];
let pendingQueue = [];

function log(msg) { console.log('+' + String(Date.now() - t0).padStart(6) + 'ms ' + msg); }

function sendPhase(idx) {
  const ph = phases[idx];
  if (!ph) return finish();
  inPhase = true;
  endSeen = false;
  flowNodes = [];
  log(`>>> 发送 ${ph.key} 消息: ${ph.content.slice(0, 30)}...`);
  ws.send(JSON.stringify({ type: 'SEND_MESSAGE', data: { content: ph.content } }));
  // 阶段级兜底超时 90s（DISCUSS 阶段 LLM 发言可能很长）
  setTimeout(() => {
    if (inPhase) {
      log(`[TIMEOUT] 阶段 ${ph.key} 90s 未收束，强制结束`);
      endPhase(idx, false);
    }
  }, 90000);
}

function endPhase(idx, closed) {
  const ph = phases[idx];
  inPhase = false;
  results[ph.key] = {
    nodes: [...flowNodes],
    closed,
    errored: errors.length > 0,
    errors: [...errors],
    closedTopic,
  };
  log(`[END] 阶段 ${ph.key} 结束 | 节点: ${flowNodes.join(' -> ') || '(无)'}${closed ? ' [已收束]' : ''}${errors.length ? ' | ERRORS: ' + errors.length : ''}`);
  phaseIdx++;
  sendPhase(phaseIdx);
}

function finish() {
  log('===== 汇总 =====');
  let allPass = true;
  for (const p of phases) {
    const r = results[p.key];
    if (!r) { log(`${p.key}: SKIP`); continue; }
    const status = r.errored ? 'ERROR' : (p.key === 'CONCLUDE' ? (r.closed ? 'PASS' : 'WARN(未收束)') : 'PASS');
    if (r.errored || (p.key === 'CONCLUDE' && !r.closed)) allPass = false;
    log(`${p.key}: ${status} | 节点: ${(r.nodes || []).join(' -> ')}`);
    if (r.errors.length) r.errors.forEach(e => log(`  error: ${e}`));
  }
  log(allPass ? 'WORKFLOW E2E PASS' : 'WORKFLOW E2E 部分不顺利，详见上方汇总');
  ws.close();
  process.exit(0);
}

ws.onopen = () => {
  log('已连接群12');
  sendPhase(0);
};

ws.onmessage = (e) => {
  let msg;
  try { msg = JSON.parse(e.data); } catch { return; }
  const d = msg.data;
  if (msg.type === 'FLOW_EVENT') {
    const name = d.nodeName || d.node;
    if (d.node !== '__START__' && d.node !== '__END__') flowNodes.push(name + '(' + d.status + ')');
    log(`[flow] ${name} ${d.status} ${d.elapsedMs}ms`);
    if (d.node === '__END__' || d.status === 'END') {
      // 收到 END：3s 静默确认该阶段结束（缓冲后续 MESSAGE_COMPLETE/TOPIC_CLOSED 等事件）
      clearTimeout(endTimer);
      endTimer = setTimeout(() => {
        if (inPhase) endPhase(phaseIdx, !!closedTopic);
      }, 3000);
    } else if (d.status === 'ERROR') {
      errors.push(d.message || 'flow-error');
      log(`[flow-ERROR] ${d.message}`);
    }
  } else if (msg.type === 'NEW_MESSAGE') {
    log(`[msg] ${d.senderName}(${d.senderType}): ${String(d.content).slice(0, 60)}`);
  } else if (msg.type === 'MESSAGE_COMPLETE') {
    log(`[COMPLETE] ${d.message.senderName}: ${String(d.message.content).slice(0, 60)}`);
  } else if (msg.type === 'TOPIC_CLOSED') {
    closedTopic = { topicId: d.topicId, title: d.title };
    log(`[CLOSED] topicId=${d.topicId} title=${d.title}`);
  } else if (msg.type === 'TOPIC_STATUS_CHANGED') {
    log(`[status] ${d.previousStatus} -> ${d.status}`);
  } else if (msg.type === 'WORK_TASK_STARTED') {
    log(`[WORK_STARTED] agent=${d.agentName} supervisorMode=${d.supervisorMode}`);
  } else if (msg.type === 'ERROR') {
    errors.push(d.message || JSON.stringify(d));
    log(`[error] ${JSON.stringify(d)}`);
  } else if (msg.type === 'MESSAGE_DELTA') {
    // 流式 delta：只打点不刷屏
  } else if (msg.type === 'AGENT_TYPING') {
    // 忽略打字状态
  } else {
    log(`[event:${msg.type}] ${JSON.stringify(d).slice(0, 100)}`);
  }
};

ws.onerror = (e) => { log('WS error ' + e.message); };
setTimeout(() => { log('全局超时 300s'); process.exit(2); }, 300000);
