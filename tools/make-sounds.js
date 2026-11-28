// 앱의 기본 알림 소리 · 벨소리를 만든다 (사인파로 합성 → res/raw/*.wav, 저작권 걱정 없음).
// 다시 만들 때: node tools/make-sounds.js
const fs = require('fs');
const path = require('path');

const RATE = 22050;
const OUT = path.join(__dirname, '..', 'app', 'src', 'main', 'res', 'raw');

/** 종소리 하나: 기본음 + 배음, 빠르게 올라가고 천천히 사라진다 */
function bell(buf, at, freq, dur, vol, { partials = [[1, 1], [2.76, 0.35], [5.4, 0.12]], decay = 3.2, attack = 0.004 } = {}) {
  const s0 = Math.floor(at * RATE), n = Math.floor(dur * RATE);
  for (let i = 0; i < n && s0 + i < buf.length; i++) {
    const t = i / RATE;
    const env = Math.min(1, t / attack) * Math.exp(-decay * t / dur * 3);
    let v = 0;
    for (const [k, a] of partials) v += a * Math.sin(2 * Math.PI * freq * k * t);
    buf[s0 + i] += v * env * vol;
  }
}

/** 부드러운 사인 (벨소리의 몸통) */
function tone(buf, at, freq, dur, vol, vib = 0) {
  const s0 = Math.floor(at * RATE), n = Math.floor(dur * RATE);
  for (let i = 0; i < n && s0 + i < buf.length; i++) {
    const t = i / RATE;
    const env = Math.min(1, t / 0.03) * Math.min(1, (dur - t) / 0.08);
    const f = freq * (1 + vib * Math.sin(2 * Math.PI * 5 * t));
    buf[s0 + i] += Math.sin(2 * Math.PI * f * t) * env * vol;
  }
}

function make(name, secs, draw) {
  const buf = new Float32Array(Math.floor(secs * RATE));
  draw(buf);
  // 꼭대기를 0.8 에 맞추고 끝을 살짝 줄여 딸깍 소리가 나지 않게
  let peak = 0; for (const v of buf) peak = Math.max(peak, Math.abs(v));
  const k = peak ? 0.8 / peak : 1, fade = Math.floor(0.02 * RATE);
  const pcm = Buffer.alloc(buf.length * 2);
  for (let i = 0; i < buf.length; i++) {
    const f = i > buf.length - fade ? (buf.length - i) / fade : 1;
    pcm.writeInt16LE(Math.max(-32767, Math.min(32767, Math.round(buf[i] * k * f * 32767))), i * 2);
  }
  const head = Buffer.alloc(44);
  head.write('RIFF', 0); head.writeUInt32LE(36 + pcm.length, 4); head.write('WAVE', 8);
  head.write('fmt ', 12); head.writeUInt32LE(16, 16); head.writeUInt16LE(1, 20); head.writeUInt16LE(1, 22);
  head.writeUInt32LE(RATE, 24); head.writeUInt32LE(RATE * 2, 28); head.writeUInt16LE(2, 32); head.writeUInt16LE(16, 34);
  head.write('data', 36); head.writeUInt32LE(pcm.length, 40);
  fs.writeFileSync(path.join(OUT, name + '.wav'), Buffer.concat([head, pcm]));
  console.log(name, ((44 + pcm.length) / 1024).toFixed(0) + 'KB');
}

fs.mkdirSync(OUT, { recursive: true });

// ── 메시지 ──
// 달빛 종: 맑은 종 두 번 (높게 → 조금 낮게)
make('msg_moonbell', 1.4, b => { bell(b, 0, 1318.5, 1.1, .8); bell(b, .16, 987.8, 1.2, .6); });
// 별똥별: 위로 흩어지는 작은 종 네 개
make('msg_star', 1.3, b => { [1568, 1760, 2093, 2637].forEach((f, i) => bell(b, i * .07, f, .9, .5 - i * .06, { decay: 4 })); });
// 물방울: 짧고 동글한 두 방울
make('msg_drop', .8, b => {
  for (const [at, f0] of [[0, 900], [.13, 1250]]) {
    const s0 = Math.floor(at * RATE), n = Math.floor(.18 * RATE);
    let ph = 0;
    for (let i = 0; i < n; i++) { const t = i / RATE, f = f0 * (1 + 1.2 * t / .18); ph += 2 * Math.PI * f / RATE; b[s0 + i] += Math.sin(ph) * Math.exp(-t * 22) * .9; }
  }
});
// 오르골: 세 음짜리 작은 가락
make('msg_musicbox', 1.6, b => { [[0, 1568], [.18, 1975.5], [.36, 2349.3]].forEach(([at, f]) => bell(b, at, f, 1.1, .5, { partials: [[1, 1], [3, .18], [4.2, .08]], decay: 2.6 })); });

// ── 전화 (반복 재생되는 한 마디) ──
// 꿈결 벨: 오르골 같은 가락 + 낮은 울림, 2.6초
make('ring_dream', 2.6, b => {
  const notes = [1318.5, 1568, 1975.5, 1568, 1318.5, 1760];
  notes.forEach((f, i) => bell(b, i * .2, f, 1, .45, { partials: [[1, 1], [3, .15]], decay: 2.4 }));
  tone(b, 0, 329.6, 1.3, .12);
});
// 고요한 벨: 두 음이 번갈아 울리는 부드러운 벨, 2.4초
make('ring_calm', 2.4, b => {
  for (let i = 0; i < 4; i++) tone(b, i * .26, i % 2 ? 659.3 : 880, .22, .45, .004);
  bell(b, 1.1, 880, 1, .25);
});
