const ws = new WebSocket('ws://localhost:8080/ws/chat?groupId=10');
const t0 = Date.now();
const deadline = setTimeout(() => { console.log('WORK E2E FAIL: timeout in 120s'); process.exit(1); }, 120000);

ws.onopen = () => {
  console.log('+0ms [open] sending WORK request');
  ws.send(JSON.stringify({ type: 'SEND_MESSAGE', data: { content: '帮我整理一份 Java 面试高频题 TOP10 的清单，包括每个题目的核心考点' } }));
};
ws.onmessage = (e) => {
  const msg = JSON.parse(e.data);
  const d = msg.data;
  if (msg.type === 'NEW_MESSAGE') {
    console.log('+' + (Date.now() - t0) + 'ms [msg] ' + (d.senderName || d.senderId) + '(' + d.senderType + '): ' + String(d.content).slice(0, 60));
  } else if (msg.type === 'WORK_TASK_STARTED') {
    console.log('+' + (Date.now() - t0) + 'ms [WORK_STARTED] agent=' + d.agentName + ' supervisorMode=' + d.supervisorMode);
  } else if (msg.type === 'MESSAGE_COMPLETE') {
    console.log('+' + (Date.now() - t0) + 'ms [COMPLETE] ' + d.message.senderName + ': ' + String(d.message.content).slice(0, 80));
  } else if (msg.type === 'MESSAGE_DELTA') {
    console.log('+' + (Date.now() - t0) + 'ms [delta] ' + String(d.delta).slice(0, 40));
  } else if (msg.type === 'AGENT_TYPING') {
    console.log('+' + (Date.now() - t0) + 'ms [typing] ' + d.agentName + ' isTyping=' + d.isTyping);
  } else if (msg.type === 'ERROR') {
    console.log('+' + (Date.now() - t0) + 'ms [error]', JSON.stringify(d));
  }
};
ws.onerror = (e) => { console.log('WS error', e.message); };
