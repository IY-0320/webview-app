// node cdpcrash.js <port> <urlSubstring> : 그 페이지의 렌더러를 일부러 죽인다 (디버그 시험용)
const [port, match] = process.argv.slice(2);
(async () => {
  const list = await (await fetch(`http://127.0.0.1:${port}/json`)).json();
  const t = list.find(p => p.url.includes(match)); if (!t) { console.log('no page'); process.exit(1); }
  const ws = new WebSocket(t.webSocketDebuggerUrl);
  ws.onopen = () => { ws.send(JSON.stringify({ id: 1, method: 'Page.crash' })); setTimeout(() => { console.log('sent'); process.exit(0); }, 1500); };
  ws.onerror = () => { console.log('ws closed'); process.exit(0); };
})();
