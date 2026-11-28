// 글꾸 원본(src)에 글자마다 인라인 색을 넣어 완성본(out)을 만들고, 가로 넘침을 확인합니다.
// 사용: node notes/post/inline.cjs <원본.html> <완성본.html> <바깥 class>
//   예) node notes/post/inline.cjs notes/post/updates-src.html notes/post/kkangtong-updates.html dlu
// playwright-core 와 이 컴퓨터의 Edge(또는 Chrome)를 씁니다. 없으면: npm i playwright-core (아무 폴더에서) 후 NODE_PATH 로 지정
const { chromium } = require('playwright-core');
const fs = require('fs');
const path = require('path');
const EXE = ['C:/Program Files/Google/Chrome/Application/chrome.exe', 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'].find(f => fs.existsSync(f));
(async () => {
  const [src, out, cls] = process.argv.slice(2);
  const html = fs.readFileSync(src, 'utf8');
  const css = html.slice(0, html.indexOf('</style>') + 8);
  const base = 'file:///' + path.resolve(path.dirname(src)).replace(/\\/g, '/') + '/';
  const b = await chromium.launch({ executablePath: EXE });
  const page = await b.newPage({ viewport: { width: 800, height: 900 } });
  await page.setContent(`<!doctype html><meta charset=utf-8><base href="${base}"><body style="background:#f6f8ff">` + html, { waitUntil: 'load' });
  const r = await page.evaluate((cls) => {
    const hex = c => '#' + c.match(/\d+(\.\d+)?/g).map(Number).slice(0, 3).map(v => Math.round(v).toString(16).padStart(2, '0')).join('');
    const root = document.querySelector('.' + cls); let n = 0;
    for (const el of [root, ...root.querySelectorAll('*')]) {
      if (![...el.childNodes].some(c => c.nodeType === 3 && c.textContent.trim())) continue;
      const c = hex(getComputedStyle(el).color);
      el.setAttribute('style', (el.getAttribute('style') ? el.getAttribute('style').replace(/;?\s*$/, ';') : '') + `color:${c};color:${c} !important;-webkit-text-fill-color:${c} !important;`); n++;
    }
    return { n, out: root.outerHTML };
  }, cls);
  const done = css + '\n\n' + r.out.replace(/<!--[\s\S]*?-->\s*/g, '') + '\n';
  fs.writeFileSync(out, done);
  console.log('인라인 색을 넣은 요소', r.n);
  // 가로 넘침 확인 (모바일 · PC)
  for (const w of [320, 360, 414, 800]) {
    const p = await b.newPage({ viewport: { width: w, height: 900 } });
    // 사진은 아직 올리기 전일 수 있어서, 확인할 때는 이 폴더의 img/ 를 씀
    const local = done.split('https://raw.githubusercontent.com/IY-0320/webview-app/main/notes/post/').join('');
    const tmp = path.join(path.dirname(src), '_check.html');
    fs.writeFileSync(tmp, `<!doctype html><meta charset=utf-8><meta name=viewport content="width=device-width"><body style="margin:0">` + local);
    await p.goto('file:///' + path.resolve(tmp).replace(/\\/g, '/'), { waitUntil: 'load' });
    fs.unlinkSync(tmp);
    await p.waitForTimeout(300);
    const o = await p.evaluate((cls) => {
      const W = document.documentElement.clientWidth; const bad = [];
      for (const n of document.querySelectorAll('.' + cls + ' *')) { const rc = n.getBoundingClientRect(); if (rc.width && (rc.right > W + 0.5 || rc.left < -0.5)) bad.push(n.className || n.tagName); }
      return { over: document.documentElement.scrollWidth - W, bad: [...new Set(bad)].slice(0, 6) };
    }, cls);
    console.log(w + 'px 가로 넘침', o.over, o.bad.join(' '));
    if (process.env.SHOT) await p.screenshot({ path: out.replace(/\.html$/, `-${w}.png`), fullPage: true });
    await p.close();
  }
  await b.close();
})();
