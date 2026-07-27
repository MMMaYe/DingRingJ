// E2E test 1: user intent triggers conclusion (@Agent + LLM intent detection)
const ws = new WebSocket('ws://localhost:8080/ws/chat?groupId=3');
const deadline = setTimeout(() => { console.log('E2E-1 FAIL: timeout, no TOPIC_CLOSED in 120s'); process.exit(1); }, 120000);

ws.onopen = () => {
  console.log('[open] connected group3, sending conclude request');
  ws.send(JSON.stringify({ type: 'SEND_MESSAGE', data: { content: '@苏教授 我觉得讨论得差不多了，辛苦你总结一下吧' } }));
};
ws.onmessage = (e) => {
  const msg = JSON.parse(e.data);
  if (msg.type === 'NEW_MESSAGE') {
    const d = msg.data;
    console.log('[msg] ' + (d.senderName || d.senderId) + '(' + d.senderType + '): ' + String(d.content).slice(0, 60));
  } else if (msg.type === 'AGENT_TYPING') {
    console.log('[typing] ' + msg.data.agentName + ' isTyping=' + msg.data.isTyping);
  } else if (msg.type === 'TOPIC_STATUS_CHANGED') {
    console.log('[status] ' + msg.data.previousStatus + ' -> ' + msg.data.status);
  } else if (msg.type === 'TOPIC_CLOSED') {
    console.log('[closed] topicId=' + msg.data.topicId + ' title=' + msg.data.title);
    console.log('--- conclusion head ---');
    console.log(String(msg.data.conclusion).slice(0, 300));
    console.log('E2E-1 PASS');
    clearTimeout(deadline);
    ws.close();
    process.exit(0);
  } else if (msg.type === 'ERROR') {
    console.log('[error]', JSON.stringify(msg.data));
  }
};
ws.onerror = (e) => { console.log('WS error', e.message); };
