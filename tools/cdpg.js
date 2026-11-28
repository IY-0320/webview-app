// node tools/cdpg.js <port> <urlSubstring> <expression> : cdp.js 와 같지만 "사용자가 누른 것처럼"(userGesture) 계산한다 (window.open · 클립보드 · 파일 고르기 시험용)
const [port, match, expr] = process.argv.slice(2);
(async () => {
  const list = await (await fetch(`http://127.0.0.1:${port}/json`)).json();
  const t = list.find(p => p.url.includes(match));
  if (!t) { console.log('pages:', list.map(p => p.url)); process.exit(1); }
  const ws = new WebSocket(t.webSocketDebuggerUrl);
  ws.onopen = () => ws.send(JSON.stringify({ id: 1, method: 'Runtime.evaluate', params: { expression: expr, returnByValue: true, awaitPromise: true, userGesture: true } }));
  ws.onmessage = m => { const d = JSON.parse(m.data); if (d.id === 1) { console.log(JSON.stringify(d.result.result.value ?? d.result, null, 1)); ws.close(); } };
  ws.onerror = e => { console.error('ws error', e.message); process.exit(1); };
})();
