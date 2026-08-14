const ws = new WebSocket('ws://localhost:8080/ws/chat?groupId=10');
const t0 = Date.now();
const deadline = setTimeout(() => { console.log('DISCUSS E2E FAIL: timeout in 90s'); process.exit(1); }, 90000);

ws.onopen = () => {
  console.log('+0ms [open] sending DISCUSS message');
  ws.send(JSON.stringify({ type: 'SEND_MESSAGE', data: { content: '我们项目该用 Redis 还是本地缓存，大家给点建议' } }));
};
ws.onmessage = (e) => {
  const msg = JSON.parse(e.data);
  const d = msg.data;
  if (msg.type === 'NEW_MESSAGE') {
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
