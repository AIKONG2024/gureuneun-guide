// 시연 이벤트(DEMO_EVT)마다, 그리고 일정 간격으로 차량 화면을 캡처함. 화면 점검과 시연 영상 재료로 씀
// 실행: node tools/demo/capture.mjs <출력 폴더> [이벤트 뒤 대기 ms]
//   EVERY=3500  일정 간격 캡처(ms, 0이면 끔)   NO_SHOTS=1  캡처 없이 이벤트만 기록
//   EMU=1       에뮬레이터 콘솔(adb emu screenrecord screenshot)로 찍음. 화면 밖에서 찍어 빠르고(0.3초 안팎) WebView 도 찍힘
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const ADB = process.env.ADB || process.env.HOME + '/Library/Android/sdk/platform-tools/adb';
const out = process.argv[2] || '/tmp/cap';
const wait = Number(process.argv[3] || 1600);
const noShots = process.env.NO_SHOTS === '1';
const every = Number(process.env.EVERY || 0);
const emu = process.env.EMU === '1';
fs.mkdirSync(out, { recursive: true });
const rawDir = path.join(out, '.raw');
if (emu) fs.mkdirSync(rawDir, { recursive: true });
let n = 0;
let busy = false;
const queue = [];
const t0 = Date.now();
const log = fs.createWriteStream(path.join(out, 'events.tsv'));
const shotLog = fs.createWriteStream(path.join(out, 'shots.tsv'));

// 캡처는 한 번에 하나씩. 찍기 시작한 시각을 그 장면의 시각으로 기록
function run() {
  if (busy || !queue.length) return;
  busy = true;
  const name = queue.shift();
  const file = path.join(out, String(++n).padStart(3, '0') + '_' + name.replace(/[^\w가-힣-]+/g, '_').slice(0, 40) + '.png');
  const at = Date.now();
  if (emu) {
    const p = spawn(ADB, ['emu', 'screenrecord', 'screenshot', rawDir]);
    p.on('close', () => {
      const got = fs.readdirSync(rawDir).filter((f) => f.endsWith('.png'));
      if (got.length) {
        fs.renameSync(path.join(rawDir, got[0]), file);
        for (const f of got.slice(1)) fs.renameSync(path.join(rawDir, f), path.join(rawDir, f + '.dup'));
        shotLog.write(at + '\t' + Date.now() + '\t' + path.basename(file) + '\n');
      }
      busy = false;
      run();
    });
    return;
  }
  const p = spawn(ADB, ['exec-out', 'screencap', '-p']);
  const ws = fs.createWriteStream(file);
  p.stdout.pipe(ws);
  p.on('close', () => {
    ws.end(() => {
      shotLog.write(at + '\t' + Date.now() + '\t' + path.basename(file) + '\n');
      busy = false;
      run();
    });
  });
}
function shot(name, delay) {
  if (noShots) return;
  setTimeout(() => { queue.push(name); run(); }, delay);
}
if (every > 0 && !noShots) setInterval(() => { if (!busy && !queue.length) shot('tick', 0); }, every);

const lc = spawn(ADB, ['logcat', '-v', 'epoch', '-T', '1', 'Gureuneun:I', '*:S']);
let buf = '';
lc.stdout.on('data', (d) => {
  buf += d.toString();
  let i;
  while ((i = buf.indexOf('\n')) >= 0) {
    const line = buf.slice(0, i);
    buf = buf.slice(i + 1);
    const m = line.match(/^\s*(\d+\.\d+).*\[DEMO_EVT\] (.*)$/);
    if (!m) continue;
    const ev = m[2].trim();
    log.write(m[1] + '\t' + ((Date.now() - t0) / 1000).toFixed(2) + '\t' + ev + '\t' + Date.now() + '\n');
    const key = ev.split(':')[0];
    if (['approach', 'enter', 'offer', 'reply', 'sight', 'pause', 'resume', 'ask', 'arrive', 'record', 'toast', 'visit', 'listen', 'phone_add'].includes(key)) {
      shot(key + '_' + (ev.split(':')[1] || ''), key === 'toast' ? 700 : wait);
      if (key === 'enter') { shot('enter_mid', 4500); shot('enter_late', 8000); shot('enter_line', 11500); }
      if (key === 'offer') shot('offer_late', 4500);
      if (key === 'sight') { shot('sight_mid', 3800); shot('sight_late', 6500); }
      if (key === 'record') { shot('record_a', 5000); shot('record_b', 10000); shot('record_c', 16000); shot('record_d', 24000); }
      if (key === 'arrive') shot('arrive_late', 3500);
    }
  }
});
console.log('캡처 대기', out, every ? '간격 ' + every + 'ms' : '');

