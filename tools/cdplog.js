// node cdplog.js <port> <urlSubstring> [seconds]
// adb forward 된 WebView devtools 에 붙어서 그 페이지의 콘솔 · 오류 · 네트워크 실패를 몇 초 동안 받아 적는다 (디버그용)
const [port, match, secs = '120'] = process.argv.slice(2);
(async () => {
  const list = await (await fetch(`http://127.0.0.1:${port}/json`)).json();
  const t = list.find(p => p.url.includes(match));
  if (!t) { console.log('pages:', list.map(p => p.url)); process.exit(1); }
  const ws = new WebSocket(t.webSocketDebuggerUrl);
  let id = 0;
  const send = (method, params = {}) => ws.send(JSON.stringify({ id: ++id, method, params }));
  const now = () => new Date().toTimeString().slice(0, 8);
  ws.onopen = () => { send('Runtime.enable'); send('Log.enable'); send('Network.enable'); console.log(now(), 'listening', t.url); };
  const reqs = {}, pending = {};
  ws.onmessage = m => {
    const d = JSON.parse(m.data);
    const p = d.params || {};
    if (d.method === 'Runtime.consoleAPICalled')
      console.log(now(), '[console.' + p.type + ']', p.args.map(a => a.value ?? a.description ?? a.type).join(' ').slice(0, 400));
    else if (d.method === 'Runtime.exceptionThrown')
      console.log(now(), '[exception]', (p.exceptionDetails.exception?.description || p.exceptionDetails.text || '').slice(0, 400));
    else if (d.method === 'Log.entryAdded')
      console.log(now(), '[log.' + p.entry.level + ']', p.entry.text.slice(0, 300));
    else if (d.id && pending[d.id]) {
      // 드림랜드 중계(/api/proxy) 응답 앞부분 (오류 문구를 보려고. 소리 파일이면 크기만)
      const r = d.result || {}, body = r.base64Encoded ? `(binary ${Math.round((r.body || '').length * 0.75)}B)` : String(r.body || '').slice(0, 300);
      console.log(now(), '[proxy reply]', pending[d.id], body); delete pending[d.id];
    }
    else if (d.method === 'Network.requestWillBeSent') {
      let extra = '';
      // 드림랜드 중계: 실제로 어디로 가는지 (주소의 호스트 · 경로만. 열쇠 · 본문은 적지 않는다)
      if (/\/api\/proxy$/.test(p.request.url) && p.request.postData) { try { const u = new URL(JSON.parse(p.request.postData).url); extra = ' → ' + u.host + u.pathname.slice(0, 60); } catch { } }
      reqs[p.requestId] = p.request.method + ' ' + p.request.url.slice(0, 120) + extra;
      // ALL=1 이면 정적 파일이 아닌 요청을 모두 적는다
      if (process.env.ALL && !/\.(js|css|png|webp|svg|woff2?|ogg|json)(\?|$)/.test(p.request.url)) console.log(now(), '[req]', reqs[p.requestId]);
    }
    else if (d.method === 'Network.loadingFinished' && process.env.ALL && reqs[p.requestId]) {
      console.log(now(), '[done]', reqs[p.requestId], p.encodedDataLength + 'B');
      if (/\/api\/proxy/.test(reqs[p.requestId])) { pending[id + 1] = reqs[p.requestId]; send('Network.getResponseBody', { requestId: p.requestId }); }
    }
    else if (d.method === 'Network.responseReceived' && p.response.status >= 400)
      console.log(now(), '[http ' + p.response.status + ']', reqs[p.requestId] || p.response.url.slice(0, 120));
    else if (d.method === 'Network.loadingFailed')
      console.log(now(), '[net fail]', p.errorText, reqs[p.requestId] || '');
  };
  ws.onerror = e => { console.error('ws error', e.message); process.exit(1); };
  setTimeout(() => { console.log(now(), 'done'); process.exit(0); }, +secs * 1000);
})();
