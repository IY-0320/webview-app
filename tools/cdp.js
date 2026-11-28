// node cdp.js <port> <urlSubstring> <expression>
// adb forward 된 WebView devtools 에 붙어서 해당 페이지에서 식을 계산해 출력한다 (디버그용)
const [port, match, expr] = process.argv.slice(2);
(async () => {
  const list = await (await fetch(`http://127.0.0.1:${port}/json`)).json();
  const t = list.find(p => p.url.includes(match));
  if (!t) { console.log('pages:', list.map(p => p.url)); process.exit(1); }
  const ws = new WebSocket(t.webSocketDebuggerUrl);
  ws.onopen = () => ws.send(JSON.stringify({ id: 1, method: 'Runtime.evaluate', params: { expression: expr, returnByValue: true, awaitPromise: true } }));
  ws.onmessage = m => { const d = JSON.parse(m.data); if (d.id === 1) { console.log(JSON.stringify(d.result.result.value ?? d.result, null, 1)); ws.close(); } };
  ws.onerror = e => { console.error('ws error', e.message); process.exit(1); };
})();
