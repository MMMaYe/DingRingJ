const ws = new WebSocket('ws://localhost:8080/ws/chat?groupId=11');
const t0 = Date.now();
let sawReply = false;
const deadline = setTimeout(() => { console.log('FAIL: no agent reply in 60s'); process.exit(1); }, 60000);

ws.onopen = () => {
  console.log('[open] sending DISCUSS message');
  ws.send(JSON.stringify({ type: 'SEND_MESSAGE', data: { content: 'Redis 做分布式缓存主要解决什么问题？大家聊聊' } }));
};
ws.onmessage = (e) => {
  const msg = JSON.parse(e.data);
  if (msg.type === 'NEW_MESSAGE') {
    const d = msg.data;
    console.log('+' + (Date.now() - t0) + 'ms [msg] ' + (d.senderName || d.senderId) + '(' + d.senderType + '): ' + String(d.content).slice(0, 100));
    if (d.senderType === 'AGENT') sawReply = true;
  } else if (msg.type === 'AGENT_TYPING') {
    console.log('+' + (Date.now() - t0) + 'ms [typing] ' + msg.data.agentName + ' isTyping=' + msg.data.isTyping);
  } else if (msg.type === 'TOPIC_CREATED') {
    console.log('+' + (Date.now() - t0) + 'ms [topic] ' + msg.data.topicTitle);
  } else if (msg.type === 'ERROR') {
    console.log('+' + (Date.now() - t0) + 'ms [error]', JSON.stringify(msg.data));
  }
};
ws.onerror = (e) => { console.log('WS error', e.message); };
