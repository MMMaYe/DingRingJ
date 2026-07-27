// E2E test 2: agent autonomous conclusion via [[CONCLUDE]] marker (group 4)
const ws = new WebSocket('ws://localhost:8080/ws/chat?groupId=4');
const deadline = setTimeout(() => { console.log('E2E-2 FAIL: timeout, no TOPIC_CLOSED in 300s'); process.exit(1); }, 300000);

let agentMsgCount = 0;
let hintSent = 0;

function send(content) {
  ws.send(JSON.stringify({ type: 'SEND_MESSAGE', data: { content } }));
}

ws.onopen = () => {
  console.log('[open] connected group4, creating topic');
  ws.send(JSON.stringify({ type: 'CREATE_TOPIC', data: { title: 'Redis 缓存穿透与击穿的应对' } }));
  setTimeout(() => send('大家聊聊 Redis 缓存穿透和击穿分别怎么应对？简短说重点就行'), 1000);
};
ws.onmessage = (e) => {
  const msg = JSON.parse(e.data);
  if (msg.type === 'NEW_MESSAGE') {
    const d = msg.data;
    console.log('[msg] ' + (d.senderName || d.senderId) + '(' + d.senderType + '): ' + String(d.content).slice(0, 50));
    if (d.senderType === 'AGENT') {
      agentMsgCount++;
      if (agentMsgCount >= 2 && hintSent === 0) {
        hintSent = 1;
        setTimeout(() => {
          console.log('>>> sending wrap-up hint (no @, free schedule)');
          send('明白了，讲得很清楚，我没有其他问题了，这个话题差不多可以收尾了');
        }, 1500);
      } else if (agentMsgCount >= 4 && hintSent === 1) {
        hintSent = 2;
        setTimeout(() => {
          console.log('>>> sending stronger wrap-up hint');
          send('大家都同意的话就到这里吧，讨论可以结束了');
        }, 1500);
      }
    }
  } else if (msg.type === 'TOPIC_CREATED') {
    console.log('[topic-created] ' + JSON.stringify(msg.data).slice(0, 120));
  } else if (msg.type === 'TOPIC_STATUS_CHANGED') {
    console.log('[status] ' + msg.data.previousStatus + ' -> ' + msg.data.status);
  } else if (msg.type === 'TOPIC_CLOSED') {
    console.log('[closed] topicId=' + msg.data.topicId + ' title=' + msg.data.title);
    console.log('--- conclusion head ---');
    console.log(String(msg.data.conclusion).slice(0, 300));
    console.log('E2E-2 PASS');
    clearTimeout(deadline);
    ws.close();
    process.exit(0);
  } else if (msg.type === 'ERROR') {
    console.log('[error]', JSON.stringify(msg.data));
  }
};
ws.onerror = (e) => { console.log('WS error', e.message); };
