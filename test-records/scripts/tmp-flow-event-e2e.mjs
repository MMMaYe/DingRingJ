// 临时回归测试：验证 checkpoint 禁用后普通消息不再被误路由崩溃 + FLOW_EVENT 步骤条广播
const ws = new WebSocket('ws://localhost:8080/ws/chat?groupId=12');
const t0 = Date.now();
const deadline = setTimeout(() => { console.log('E2E FAIL: timeout in 120s'); process.exit(1); }, 120000);

ws.onopen = () => {
  console.log('+0ms [open] sending message (复用上次崩溃的消息)');
  ws.send(JSON.stringify({ type: 'SEND_MESSAGE', data: { content: '我们来聊聊分布式锁吧' } }));
};
ws.onmessage = (e) => {
  const msg = JSON.parse(e.data);
  const d = msg.data;
  if (msg.type === 'FLOW_EVENT') {
    console.log('+' + (Date.now() - t0) + 'ms [flow] ' + (d.nodeName || d.node) + ' status=' + d.status + ' elapsed=' + d.elapsedMs + 'ms' + (d.message ? ' msg=' + d.message : ''));
  } else if (msg.type === 'NEW_MESSAGE') {
    console.log('+' + (Date.now() - t0) + 'ms [msg] ' + (d.senderName || d.senderId) + '(' + d.senderType + '): ' + String(d.content).slice(0, 60));
  } else if (msg.type === 'TOPIC_CREATED') {
    console.log('+' + (Date.now() - t0) + 'ms [topic] ' + d.topicTitle);
  } else if (msg.type === 'MESSAGE_COMPLETE') {
    console.log('+' + (Date.now() - t0) + 'ms [COMPLETE] ' + d.message.senderName + ': ' + String(d.message.content).slice(0, 60));
  } else if (msg.type === 'AGENT_TYPING') {
    console.log('+' + (Date.now() - t0) + 'ms [typing] ' + d.agentName + ' isTyping=' + d.isTyping);
  } else if (msg.type === 'ERROR') {
    console.log('+' + (Date.now() - t0) + 'ms [error]', JSON.stringify(d));
  }
};
ws.onerror = (e) => { console.log('WS error', e.message); };
