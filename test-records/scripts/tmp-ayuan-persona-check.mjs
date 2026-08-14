// 临时验证脚本：阿源 persona 是否生效
// 连群12，发一条 @阿源 的 JVM 源码问题，观察阿源发言风格是否符合新配置
const ws = new WebSocket('ws://localhost:8080/ws/chat?groupId=12');
const t0 = Date.now();
let sent = false;
let ayuanReplies = [];
let allMessages = [];
const SLEEP_MS = 5000;

function log(msg) { console.log('+' + String(Date.now() - t0).padStart(6) + 'ms ' + msg); }

ws.onopen = () => {
  log('已连接群12');
  const content = '@阿源 阿源，帮我从源码层面讲讲 volatile 关键字是怎么保证可见性的？';
  ws.send(JSON.stringify({ type: 'SEND_MESSAGE', data: { content } }));
  sent = true;
  log('已发送: ' + content);
};

ws.onmessage = (e) => {
  const msg = JSON.parse(e.data);
  const d = msg.data;
  if (msg.type === 'FLOW_EVENT') {
    const name = d.nodeName || d.node;
    log(`[flow] ${name} ${d.status} ${d.elapsedMs}ms`);
    if (d.status === 'ERROR') log(`[flow-ERROR] ${d.message}`);
  } else if (msg.type === 'NEW_MESSAGE') {
    const line = `[msg] ${d.senderName}(${d.senderType}): ${String(d.content).slice(0, 80)}`;
    allMessages.push(line);
    if (d.senderName === '阿源') ayuanReplies.push(String(d.content));
    if (d.senderName !== '我') log(line);
  } else if (msg.type === 'MESSAGE_COMPLETE') {
    const line = `[COMPLETE] ${d.message.senderName}: ${String(d.message.content).slice(0, 80)}`;
    allMessages.push(line);
    if (d.message.senderName === '阿源') ayuanReplies.push(String(d.message.content));
    log(line);
  } else if (msg.type === 'AGENT_TYPING') {
    log(`[typing] ${d.agentName}`);
  } else if (msg.type === 'TOPIC_STATUS_CHANGED') {
    log(`[status] ${d.previousStatus} -> ${d.status} topic=${d.topicId}`);
  } else if (msg.type === 'WORK_TASK_STARTED') {
    log(`[WORK_STARTED] agent=${d.agentName}`);
  } else if (msg.type === 'WORK_PROGRESS') {
    log(`[progress] ${String(d.content || JSON.stringify(d)).slice(0, 60)}`);
  } else if (msg.type === 'WORK_RESULT') {
    log(`[result] ${String(d.content || JSON.stringify(d)).slice(0, 80)}`);
  } else if (msg.type === 'ERROR') {
    log(`[error] ${JSON.stringify(d)}`);
  }
};

ws.onerror = (e) => { log('WS error ' + e.message); };

setTimeout(() => {
  log('===== 汇总 =====');
  log('阿源收到消息条数: ' + ayuanReplies.length);
  const full = ayuanReplies.join('\n---\n');
  log('===== 阿源发言全文 =====');
  log(full || '(无发言)');
  ws.close();
  process.exit(0);
}, 120000);
