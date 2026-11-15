// 시연 영상 재료 만들기: 차량 화면은 에뮬레이터 콘솔로 이어서 캡처하고(EMU=1 EVERY=200, 초당 2~3장),
// 휴대폰(phone_rec.mjs)은 같은 시각에 영상으로 녹화하고 이벤트 시각을 남김
// 실행: EMU=1 EVERY=200 node tools/demo/record_all.mjs  → /tmp/rec 에 car/(캡처, shots.tsv, events.tsv), phone.webm, *_start.txt
import { spawn, execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const ADB = process.env.HOME + '/Library/Android/sdk/platform-tools/adb';
const NODE = process.execPath;
const REC = '/tmp/rec';
const ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname), '../..');
fs.mkdirSync(REC, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const adb = (...a) => { try { return execFileSync(ADB, a, { encoding: 'utf8' }); } catch (e) { return ''; } };
const demo = (cmd, extra = []) => adb('shell', 'am', 'start', '-n', 'app.gureuneun.guide/.MainActivity', '--es', 'demo', cmd, ...extra);

for (const f of ['car.webm', 'phone.webm', 'car_start.txt', 'phone_start.txt', 'car_events.tsv', 'phone_events.tsv']) {
  const p = path.join(REC, f);
  if (fs.existsSync(p)) fs.renameSync(p, p + '.old');
}

// 1. 처음 상태로
await fetch('http://localhost:8787/api/trip/GJ1016/reset', { method: 'POST' }).catch(() => {});
adb('emu', 'geo', 'fix', '127.1250', '37.4420');
adb('shell', 'am', 'force-stop', 'app.gureuneun.guide');
adb('logcat', '-c');
adb('shell', 'am', 'start', '-n', 'app.gureuneun.guide/.MainActivity');
await sleep(7000);
// 녹화 중에는 순정 내비게이션을 띄우지 않음 (에뮬레이터 내비 지도가 비어 있어 화면이 하얗게 뜸)
demo('navi', ['--es', 'on', '0']);
await sleep(1500);

// 2. 이벤트 기록과 차량 화면 캡처
if (fs.existsSync(path.join(REC, 'car'))) fs.renameSync(path.join(REC, 'car'), path.join(REC, 'car.old.' + Date.now()));
const cap = spawn(NODE, [path.join(ROOT, 'tools/demo/capture.mjs'), path.join(REC, 'car'), '1500'], { env: { ...process.env, EVERY: process.env.EVERY || '2600' }, stdio: 'ignore' });
await sleep(1500);
const evFile = path.join(REC, 'car', 'events.tsv');
const seen = () => (fs.existsSync(evFile) ? fs.readFileSync(evFile, 'utf8') : '');
async function waitEv(re, timeout) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeout) {
    if (re.test(seen())) return true;
    await sleep(300);
  }
  return false;
}

fs.writeFileSync(path.join(REC, 'car_start.txt'), String(Date.now()));
console.log('차량 캡처 시작');

// 4. 휴대폰 녹화 시작 (담기 → 출발 → … → 기록까지 스스로 진행)
const phone = spawn(NODE, [path.join(ROOT, 'tools/demo/phone_rec.mjs')], { stdio: 'inherit' });
const phoneDone = new Promise((r) => phone.on('exit', r));

// 5. 휴대폰이 장소를 담으면 6초 뒤 안내 시작
const added = await waitEv(/phone_add/, 60000);
console.log('담기', added);
await sleep(6000);
demo('start');
console.log('안내 시작');

// 6. 기록이 나오면 28초 더 찍고 멈춤
const done = await waitEv(/\trecord\t/, 12 * 60 * 1000);
console.log('기록', done);
await sleep(30000);
await phoneDone;
cap.kill();
fs.copyFileSync(evFile, path.join(REC, 'car_events.tsv'));
console.log('끝', REC);

