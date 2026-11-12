// 구르는 가이드 흐름 시험: 에뮬레이터의 차 앱과 그룹 서버를 실제로 돌려 시나리오를 처음부터 끝까지 확인함
// 준비: Pleos Connect 에뮬레이터에 앱 설치, adb forward tcp:8088 tcp:8088 (Virtual Console),
//       앱 [점검] 화면에서 [시연 명령 받기] 켜기 (release 빌드일 때),
//       그룹 서버를 자료 폴더와 함께 켜기: cd companion && DATA_DIR=/tmp/rg-flow-data npm start
//       Virtual Console 은 브라우저가 붙어 있을 때만 조작을 받으므로, 이 도구가 브라우저처럼 1초마다 신호를 보냄
// 사용: DATA_DIR=/tmp/rg-flow-data node tools/demo/flow.mjs gyeongju | jeju | seoul
//       (휴대폰 흉내를 내려고 서버 자료 폴더의 그룹 키를 읽음. 결과와 캡처는 tools/demo/out/flow)
//       에뮬레이터가 느리면 TIME_SCALE=3 처럼 기다리는 시간을 늘림
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';

const REPO = path.resolve(path.dirname(new URL(import.meta.url).pathname), '../..');
const require = createRequire(path.join(REPO, 'companion', 'package.json'));
const WebSocket = require('ws');

const ADB = process.env.ADB || path.join(process.env.HOME, 'Library/Android/sdk/platform-tools/adb');
const SRV = process.env.SRV || 'http://127.0.0.1:8787';
const WS_URL = SRV.replace(/^http/, 'ws');
const VC = 'http://127.0.0.1:8088';
const DATA = path.resolve(process.env.DATA_DIR || path.join(REPO, 'companion', 'data'));
const OUT = path.resolve(process.env.OUT || path.join(REPO, 'tools', 'demo', 'out', 'flow'));
fs.mkdirSync(OUT, { recursive: true });
const SCN = Object.fromEntries(['gyeongju', 'jeju', 'seoul'].map(id => [id, JSON.parse(fs.readFileSync(path.join(REPO, 'app/src/main/assets/scenarios', id + '.json'), 'utf8'))]));

const sleep = ms => new Promise(r => setTimeout(r, ms));
// 에뮬레이터가 느릴 때(안내 음성 한 문장에 10초 넘게 걸릴 때) 기다리는 시간을 늘림. 예: TIME_SCALE=3
const SCALE = Math.max(1, Number(process.env.TIME_SCALE) || 1);
const t0 = Date.now();
const stamp = () => ((Date.now() - t0) / 1000).toFixed(1).padStart(6);
const results = [];
const log = (...a) => console.log(stamp(), ...a);
const check = (name, ok, extra) => { results.push((ok ? 'PASS ' : 'FAIL ') + name + (extra ? '  | ' + extra : '')); log(ok ? 'PASS' : 'FAIL', name, extra || ''); };
const adb = (...a) => execFileSync(ADB, a, { encoding: 'utf8', maxBuffer: 64 << 20 });
// adb shell 은 인자를 공백으로 이어 붙이므로 띄어쓰기가 있는 값은 작은따옴표로 감쌈
const q1 = a => /[\s?']/.test(a) ? "'" + a.replace(/'/g, "'\\''") + "'" : a;
const demo = (cmd, ...extra) => { try { adb('shell', 'am', 'start', '-n', 'app.gureuneun.guide/.MainActivity', '--es', 'demo', cmd, ...extra.map(q1)); } catch (e) { log('demo 실패', cmd, e.message); } };
// 조작이 한 번에 안 받아들여질 때가 있어 200 이 올 때까지 몇 번 다시 보냄
const vc = async p => {
  let last = 'ERR';
  for (let i = 0; i < 8; i++) {
    try { last = (await fetch(VC + p, { method: 'POST' })).status; if (last === 200) return last; } catch { last = 'ERR'; }
    await sleep(600);
  }
  return last;
};
// Virtual Console 은 브라우저 세션이 살아 있어야 차량 조작 API 를 엶 (없으면 503 emulator_inactive)
setInterval(() => fetch(VC + '/browser/session?client=gg-flow', { method: 'POST' }).catch(() => {}), 1000);
async function vcReady() {
  for (let i = 0; i < 20; i++) { if (await vc('/vehicle/turn-signal?mode=Off') === 200) return true; await sleep(700); }
  return false;
}
const shot = name => { try { fs.writeFileSync(path.join(OUT, name + '.png'), execFileSync(ADB, ['exec-out', 'screencap', '-p'], { maxBuffer: 64 << 20 })); log('캡처', name); } catch (e) { log('캡처 실패', name); } };
// 화면에 보이는 글자 (물음과 단추가 제대로 떴는지 확인하는 데 씀).
// uiautomator 는 화면이 멈춰 있어야 읽을 수 있어, 장면이 움직이는 동안(명소 장면, 듣는 중 표시)에는 빈 목록이 옴
function screenTexts() {
  try {
    adb('shell', 'uiautomator', 'dump', '/sdcard/rg-ui.xml');
    const xml = adb('shell', 'cat', '/sdcard/rg-ui.xml');
    return (xml.match(/ (?:text|content-desc)="[^"]+"/g) || []).map(t => t.replace(/^ (?:text|content-desc)="/, '').slice(0, -1));
  } catch { return []; }
}
// 내비게이션이 잠깐 앞에 나와 있을 수 있어 몇 번 다시 봄
async function waitScreen(re, ms = 12000) {
  const end = Date.now() + ms * SCALE;
  while (Date.now() < end) {
    const texts = screenTexts();
    if (texts.some(t => re.test(t))) return texts;
    await sleep(1200);
  }
  return null;
}

// 앱 로그: 시작할 때 비우고, 지금까지 본 줄 수를 기억해 새 줄만 봄
let seen = 0;
function appLines() { return adb('logcat', '-d', '-s', 'Gureuneun:*').split('\n').filter(l => l.includes('Gureuneun')); }
function fresh() { const all = appLines(); const out = all.slice(seen); seen = all.length; return out; }
const history = [];
function pull() { const f = fresh(); history.push(...f); return f; }
async function waitLog(re, ms = 20000, label) {
  const end = Date.now() + ms * SCALE;
  // 이미 들어온 줄도 봄 (기다리기 시작한 뒤 들어온 것만 보려면 mark 를 씀)
  while (Date.now() < end) {
    pull();
    const hit = history.find(l => re.test(l) && !l.__used);
    if (hit) return hit;
    await sleep(600);
  }
  return null;
}
function since(mark) { return history.slice(mark); }
async function waitNew(re, ms = 20000) {
  const mark = history.length;
  const end = Date.now() + ms * SCALE;
  while (Date.now() < end) {
    pull();
    const hit = since(mark).find(l => re.test(l));
    if (hit) return hit;
    await sleep(600);
  }
  return null;
}
const mark = () => { pull(); return history.length; };
async function waitAfter(m, re, ms = 20000) {
  const end = Date.now() + ms * SCALE;
  while (Date.now() < end) {
    pull();
    const hit = history.slice(m).find(l => re.test(l));
    if (hit) return hit;
    await sleep(600);
  }
  return null;
}

// 그룹 서버 자료에서 그룹 키를 읽음 (휴대폰 흉내용)
function groupFile(code) {
  for (const f of [path.join(DATA, code, 'group.json'), path.join(DATA, code + '.json')]) if (fs.existsSync(f)) return JSON.parse(fs.readFileSync(f, 'utf8'));
  const dir = path.join(DATA, code);
  if (fs.existsSync(dir)) for (const f of fs.readdirSync(dir)) if (f.endsWith('.json')) { try { const j = JSON.parse(fs.readFileSync(path.join(dir, f), 'utf8')); if (j.phoneKey) return j; } catch {} }
  return null;
}
function newestCode(after) {
  if (!fs.existsSync(DATA)) return null;
  const list = fs.readdirSync(DATA).filter(n => /^\d{4}$/.test(n)).map(n => ({ n, t: fs.statSync(path.join(DATA, n)).mtimeMs })).filter(x => x.t >= after - 2000).sort((a, b) => b.t - a.t);
  return list[0] ? list[0].n : null;
}

// 휴대폰 흉내: 그룹 키로 들어옴. autoFind 를 켜 두면 차가 찾을 것을 냈을 때 [찾았다]를 누름
function phone(code, k, scenarioId, lang = 'ko', name = '하준') {
  const ws = new WebSocket(WS_URL + '/ws?code=' + code + (k ? '&k=' + k : ''));
  const p = { ws, msgs: [], hunt: 0, found: 0, autoFind: false };
  ws.on('error', () => {});
  ws.on('message', d => {
    let m; try { m = JSON.parse(String(d)); } catch { return; }
    p.msgs.push(m);
    if (m.type === 'found') p.found++;
    if (m.type === 'hunt') {
      p.hunt++;
      if (p.autoFind) setTimeout(() => { ws.send(JSON.stringify({ type: 'hunt_found', placeId: m.placeId, by: name })); log('휴대폰 [찾았다]', m.placeId); }, 3500);
    }
  });
  return new Promise(res => ws.on('open', () => res(p)));
}

async function resetCar() {
  if (!(await vcReady())) log('Virtual Console 이 조작을 받지 않아요 (adb forward tcp:8088 tcp:8088 확인)');
  await vc('/vehicle/gear?gear=Park');
  await vc('/vehicle/turn-signal?mode=Off');
  await vc('/vehicle/body?front_wiper=Off');
  await vc('/vehicle/lights?feature=headlamp_mode&value=Off');
  demo('end'); await sleep(1500);
  demo('clear'); await sleep(600);
  demo('home'); await sleep(2500);
}

async function startTrip(scenario, lang) {
  const before = Date.now();
  const m = mark();
  demo('start', '--es', 'scenario', scenario, '--es', 'lang', lang);
  const started = await waitAfter(m, /여행 시작:/, 20000);
  check(scenario + ': 여행 시작', !!started, started && started.split('Gureuneun: ')[1]);
  let code = null;
  for (let i = 0; i < 20 && !code; i++) { await sleep(700); code = newestCode(before); }
  check(scenario + ': 그룹 서버에 그룹 생김', !!code, code);
  const g = code ? groupFile(code) : null;
  return { code, k: g && g.phoneKey, g };
}

const tailOf = l => l ? l.split('] ').pop().slice(0, 80) : '';
const ackFor = async (ph, n) => { for (let i = 0; i < 20; i++) { const a = ph.msgs.filter(x => x.type === 'pick_ack'); if (a.length >= n) return a[n - 1]; await sleep(500); } return null; };

// ------------------------------------------------------------ 경주 (가족, 차 한 대)
async function gyeongju() {
  await resetCar();
  // 뒷자리에 아이가 탄 것으로
  await vc('/vehicle/seat?kind=occupied&area=rear_left&value=true');
  const m0 = mark();
  const { code, k } = await startTrip('gyeongju', 'ko');
  if (!code) return;
  const ph = await phone(code, k, 'gyeongju');
  check('경주: 휴대폰(QR 키) 접속', ph.ws.readyState === 1);
  // 뒷자리에 누가 앉았으니 아이 모드로 갈지 물음 → "응"
  const kidAsk = await waitAfter(m0, /\[Speak\].*아이 모드로 갈까요/, 30000);
  check('경주: 뒷자리에 누가 앉으면 아이 모드로 갈지 물음', !!kidAsk, tailOf(kidAsk));
  await sleep(1500);
  shot('g00_kidoffer');
  let mk = mark();
  demo('asktext', '--es', 'q', '응');
  check('경주: "응" 하면 아이 모드', !!(await waitAfter(mk, /\[Speak\].*아이 모드로 갈게요/, 20000)));
  await sleep(3000);
  shot('g01_before');
  const pre = await waitScreen(/휴대폰으로 같이 봐요/, 6000);
  if (pre) check('경주: 출발 전에는 QR과 오늘 코스를 보여 줌', pre.some(t => /그룹 번호/.test(t)) && pre.some(t => /대릉원/.test(t)));
  else log('화면 글자를 읽지 못함 (움직이는 화면). 캡처 g01_before.png 로 확인');

  // 기어를 D 로 바꾸면 따로 누르지 않아도 출발
  let m = mark();
  await vc('/vehicle/gear?gear=Drive');
  check('경주: 차가 움직이면 출발 안내', !!(await waitAfter(m, /\[Speak\].*출발/, 30000)));
  check('경주: 여행지에 들어서면 소개', !!(await waitAfter(m, /영역 진입: 경주/, 60000)));
  await sleep(4000);
  shot('g02_driving');

  // 첫 명소: 어느 쪽에 보이는지 먼저, 창밖에서 찾을 것, "찾았다"에 스티커, 이야기, 아이 눈높이의 한마디
  m = mark();
  demo('jump');
  const ann = await waitAfter(m, /\[Speak\].*잠시 후 (오른쪽|왼쪽|앞쪽)에 대릉원/, 60000);
  check('경주: 명소가 어느 쪽에 보이는지 먼저 알림', !!ann, tailOf(ann));
  const hunt = await waitAfter(m, /\[Speak\].*둥근 언덕을 찾아보세요/, 40000);
  check('경주: 아이 모드에서는 창밖에서 찾을 것을 냄', !!hunt, tailOf(hunt));
  for (let i = 0; i < 20 && ph.hunt < 1; i++) await sleep(500);
  check('경주: 찾을 것이 휴대폰에도 뜸', ph.hunt >= 1, 'hunt ' + ph.hunt);
  await sleep(3500);
  shot('g03_hunt');
  // 말로 "찾았다"
  demo('asktext', '--es', 'q', '찾았다');
  const cheer = await waitAfter(m, /\[Speak\].*스티커 한 장 드릴게요/, 20000);
  check('경주: "찾았다"에 차가 스티커를 줌', !!cheer, tailOf(cheer));
  for (let i = 0; i < 20 && ph.found < 1; i++) await sleep(500);
  check('경주: 모은 스티커가 휴대폰에도 감', ph.found >= 1, 'found ' + ph.found);
  await sleep(1500);
  shot('g03_story');
  const kid = await waitAfter(m, /\[Speak\].*임금님들이 잠든 무덤/, 90000);
  check('경주: 이야기 끝에 아이 눈높이의 한마디를 덧붙임', !!kid, tailOf(kid));
  // 다음 곳부터는 휴대폰의 [찾았다]로
  ph.autoFind = true;

  // 창밖 묻기 (코스에 없는 곳)
  await sleep(3000);
  m = mark();
  demo('asktext', '--es', 'q', '오른쪽에 보이는 거 뭐야?');
  const askA = await waitAfter(m, /\[Speak\].*(오른쪽|보이는 곳)/, 20000);
  check('경주: "오른쪽에 보이는 거 뭐야?"에 답함', !!askA, tailOf(askA));
  await sleep(1500);
  shot('g04_ask');

  // 휴대폰에서 고른 카페 → "가는 길에 들를까요?" → "응"
  await sleep(6000);
  m = mark();
  ph.ws.send(JSON.stringify({ type: 'pick', placeId: 'hwangridan' }));
  const ask = await waitAfter(m, /\[Speak\].*들를까요/, 40000);
  check('경주: 휴대폰에서 고른 카페를 차가 물음', !!ask, tailOf(ask));
  const listen1 = await waitAfter(m, /듣기 요청/, 15000);
  check('경주: 물은 뒤 마이크를 엶', !!listen1);
  await sleep(900);
  shot('g05_pick');
  const docked = await waitScreen(/^들르기$/, 4000);
  if (docked) check('경주: 달리는 동안의 답은 둘뿐 (들르기, 나중에)', docked.some(t => /^나중에$/.test(t)) && !docked.some(t => /목적지/.test(t)), docked.filter(t => /들르기|나중에|목적지/.test(t)).join(' / '));
  else log('화면 글자를 읽지 못함. 캡처 g05_pick.png 로 확인');
  const afterYes = mark();
  m = afterYes;
  demo('answer', '--es', 'q', '응');
  const wp = await waitAfter(m, /경유지 다시 묶음|경유지 추가|목적지 요청/, 15000);
  check('경주: "응" 한마디로 경유지에 넣음', !!wp, wp && wp.split('Gureuneun: ')[1]);
  const ack = await ackFor(ph, 1);
  check('경주: 휴대폰에 운전자 답이 감', !!ack && ack.accepted === true, ack && JSON.stringify({ mode: ack.mode, accepted: ack.accepted }));

  // 다음 명소까지는 차가 알아서 감. 이야기 도중에 방향지시등을 켜면 멈추고 끄면 이어 감
  const cheom = await waitAfter(afterYes, /\[Speak\].*첨성대예요/, 120000);
  check('경주: 다음 명소 이야기로 이어 감', !!cheom, tailOf(cheom));
  await sleep(1200);
  m = mark();
  await vc('/vehicle/turn-signal?mode=BlinkRight');
  check('경주: 방향지시등을 켜면 이야기를 멈춤', !!(await waitAfter(m, /방향지시등 켜짐/, 10000)));
  await sleep(2500);
  await vc('/vehicle/turn-signal?mode=Off');
  check('경주: 끄면 이어 감', !!(await waitAfter(m, /방향지시등 꺼짐/, 10000)));

  // 휴대폰에서 누른 [찾았다]도 스티커가 됨
  check('경주: 휴대폰의 [찾았다]도 받음', history.slice(afterYes).some(l => /찾았다: cheomseongdae 하준/.test(l)));
  // 이야기를 건너뛰면 아이에게 하는 한마디도 덧붙이지 않음
  await sleep(3500);
  m = mark();
  demo('next');
  const skipped = await waitAfter(m, /이야기 건너뜀: 첨성대/, 10000);
  await sleep(3000 * SCALE);
  pull();
  if (skipped) check('경주: 건너뛴 이야기에는 아이에게 하는 말을 덧붙이지 않음', !history.slice(m).some(l => /\[Speak\].*별을 보던 돌탑/.test(l)));
  else log('첨성대 이야기가 이미 끝나 건너뛰기를 확인하지 못함');

  // 안내 밖의 부탁
  await sleep(2000);
  m = mark();
  demo('asktext', '--es', 'q', '배터리 얼마나 남았어?');
  const outside = await waitAfter(m, /\[Speak\].*잘 못 알아들었어요/, 15000);
  check('경주: 여행 안내 밖의 부탁은 받지 않음', !!outside, tailOf(outside));

  // 마지막 목적지에 세우면 이야기가 끝난 뒤 여행을 끝낼지 먼저 물음
  const last = await waitAfter(afterYes, /영역 진입: 동궁과 월지/, 150000);
  check('경주: 마지막 목적지에 닿음', !!last);
  m = mark();
  await sleep(6000);
  await vc('/vehicle/gear?gear=Park');
  await sleep(3000);
  shot('g06_parked');
  const offer = await waitAfter(m, /\[Speak\].*오늘 여행을 끝낼까요/, 150000);
  check('경주: 목적지에 세우면 여행을 끝낼지 물음', !!offer);
  await sleep(2500);
  shot('g07_end_ask');
  m = mark();
  demo('answer', '--es', 'q', '응');
  const fin = await waitAfter(m, /\[Speak\].*여기까지예요/, 20000);
  check('경주: "응"이면 여행을 끝냄', !!fin, tailOf(fin));
  check('경주: 차 화면에서 여행 영상이 시작됨', !!(await waitAfter(m, /\[Reel\] 여행 영상 시작/, 30000)));
  check('경주: 앱이 바꾼 실내 조명을 원래대로', !!(await waitAfter(m, /앱이 바꾼 차 상태를 모두 되돌림/, 15000)));
  await sleep(5000);
  shot('g08_record');
  const before = JSON.stringify(groupFile(code) || {});
  check('경주: 보내기 전에는 기록이 서버로 가지 않음', !/"record":\{/.test(before));
  demo('share');
  await sleep(4000);
  const after = JSON.stringify(groupFile(code) || {});
  check('경주: [휴대폰으로 보내기]로 기록 올림', /"record":\{/.test(after));
  const recMsg = ph.msgs.find(x => x.type === 'record');
  check('경주: 휴대폰에 기록이 옴', !!recMsg);
  check('경주: 기록에 찾은 것(스티커)이 들어 있음', !!(recMsg && recMsg.record.events.some(e => e.type === 'found')));
  shot('g09_record_sent');
  const page = await fetch(SRV + '/g/' + code + '/record?k=' + k + '&car=1').then(r => r.status).catch(() => 'ERR');
  check('경주: 휴대폰 기록 페이지 열림', page === 200, 'status ' + page);
  ph.ws.close();
  return code;
}

// ------------------------------------------------------------ 제주 (두 대)
async function jeju() {
  await resetCar();
  const { code, k } = await startTrip('jeju', 'ko');
  if (!code) return;
  const ph = await phone(code, k, 'jeju', 'ko', '민지');
  // 두 번째 차: 네 자리 번호로 들어와 받은 차 키로 접속
  const join = await (await fetch(SRV + '/api/groups/' + code + '/join', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ carName: '뒤차' }) })).json();
  check('제주: 2호차가 번호로 들어옴', join.carNo === 2 && join.carName === '2호차', join.carName);
  const car2 = new WebSocket(WS_URL + '/ws?code=' + code + '&role=car&key=' + encodeURIComponent(join.carKey));
  const car2msgs = [];
  car2.on('message', d => { try { car2msgs.push(JSON.parse(String(d))); } catch {} });
  car2.on('error', () => {});
  await new Promise(r => car2.on('open', r));
  await sleep(2500);
  let m = mark();
  await vc('/vehicle/gear?gear=Drive');
  check('제주: 차가 움직이면 출발', !!(await waitAfter(m, /\[Speak\].*출발/, 30000)));
  await sleep(8000);
  shot('j01_driving');

  // 휴대폰이 바닷가 카페를 고름 → 두 차가 물음 → "응"
  m = mark();
  ph.ws.send(JSON.stringify({ type: 'pick', placeId: 'aewol_cafe' }));
  const ask = await waitAfter(m, /\[Speak\].*들를까요/, 40000);
  check('제주: 고른 카페를 "가는 길에 들를까요?"로 물음', !!ask, tailOf(ask));
  const car2pick = car2msgs.find(x => x.type === 'pick');
  check('제주: 2호차에도 같은 장소가 감', !!car2pick, car2pick && car2pick.name);
  await sleep(1500);
  shot('j02_pick');
  m = mark();
  demo('answer', '--es', 'q', '응');
  const wp = await waitAfter(m, /경유지 다시 묶음/, 15000);
  check('제주: "응"으로 경유지를 길 순서대로 다시 묶음', !!wp, wp && wp.split('Gureuneun: ')[1]);
  check('제주: 순정 내비게이션에 경로를 다시 요청', !!(await waitAfter(m, /목적지 요청/, 15000)));

  // 주차장은 "그리로 안내할까요?" → "응"이면 목적지로
  await sleep(22000);
  m = mark();
  const lot = SCN.jeju.pickables.find(p => p.kind === 'parking');
  ph.ws.send(JSON.stringify({ type: 'pick', placeId: lot.id }));
  const park = await waitAfter(m, /\[Speak\].*안내할까요/, 40000);
  check('제주: 고른 주차장은 "그리로 안내할까요?"로 물음', !!park, tailOf(park));
  await sleep(1500);
  m = mark();
  demo('answer', '--es', 'q', '응');
  const dest = await waitAfter(m, /목적지 요청: 협재해수욕장 주차장/, 15000);
  check('제주: "응"이면 목적지로 바꾸고 앞서 넣은 카페는 그대로 둠', !!dest && /경유지 [12]곳/.test(dest), dest && dest.split('Gureuneun: ')[1]);

  // 답이 없으면 30초 뒤 담아 두고, 차를 세우면 다시 보여 줌
  await sleep(22000);
  m = mark();
  ph.ws.send(JSON.stringify({ type: 'pick', placeId: 'iho_spot' }));
  await waitAfter(m, /\[Speak\].*들를까요/, 40000);
  const later = await (async () => { for (let i = 0; i < 60 * SCALE; i++) { const a = ph.msgs.filter(x => x.type === 'pick_ack').pop(); if (a && a.mode === 'later') return a; await sleep(1000); } return null; })();
  check('제주: 달리는 동안 답이 없으면 담아 둠', !!later);
  await vc('/vehicle/gear?gear=Park');
  await sleep(4000);
  shot('j03_parked_saved');
  const saved = await waitScreen(/담아 둔 곳/, 8000);
  if (saved) check('제주: 차를 세우면 담아 둔 곳을 다시 보여 줌', saved.some(t => /이호테우 해변/.test(t)), saved.filter(t => /담아|이호|들르기/.test(t)).join(' / '));
  else log('화면 글자를 읽지 못함. 캡처 j03_parked_saved.png 로 확인');
  await vc('/vehicle/gear?gear=Drive');
  await sleep(3000);

  // 차 조작은 하지 않음
  m = mark();
  demo('asktext', '--es', 'q', '창문 열어 줘');
  const refuse = await waitAfter(m, /\[Speak\].*잘 못 알아들었어요/, 15000);
  check('제주: "창문 열어 줘" 같은 차 조작은 받지 않음', !!refuse);
  // 차가 다른 차에 장소를 보내는 길은 없음 (장소는 휴대폰에서만 고름)
  await sleep(4000);
  m = mark();
  car2.send(JSON.stringify({ type: 'pick', id: lot.id, placeId: lot.id, name: lot.name.ko, lat: lot.lat, lon: lot.lon, kind: 'parking' }));
  await sleep(6000 * SCALE);
  pull();
  check('제주: 차가 보낸 장소는 다른 차에 가지 않음', !history.slice(m).some(l => /\[Speak\].*골랐어요/.test(l)));
  car2.close(); ph.ws.close();
  return code;
}

// ------------------------------------------------------------ 서울 (손님에게는 영어로, 기사 화면은 그대로)
async function seoul() {
  await resetCar();
  const { code, k } = await startTrip('seoul', 'en');
  if (!code) return;
  const ph = await phone(code, k, 'seoul', 'en', 'Alex');
  await sleep(3000);
  let m = mark();
  await vc('/vehicle/gear?gear=Drive');
  const dep = await waitAfter(m, /\[Speak\] \[en\]/, 30000);
  check('서울: 영어로 안내', !!dep, tailOf(dep));
  m = mark();
  demo('jump');
  await waitAfter(m, /영역 진입: 63 Building/, 60000);
  await waitAfter(m, /\[Speak\] \[en\].*is the 63 Building/, 30000);
  await sleep(2500);
  shot('s01_story');
  const scr = await waitScreen(/물어보기/, 5000);
  if (scr) check('서울: 기사 화면은 한국어 그대로', scr.some(t => /63빌딩/.test(t)), scr.filter(t => /63|물어보기|Ask/.test(t)).join(' / '));
  else log('화면 글자를 읽지 못함. 캡처 s01_story.png 로 확인');
  await sleep(4000);
  m = mark();
  demo('asktext', '--es', 'q', "what's that on the right");
  const ans = await waitAfter(m, /\[Speak\] \[en\]/, 20000);
  check('서울: 영어로 물으면 영어로 답', !!ans, tailOf(ans));
  await sleep(6000);
  // 손님이 고른 곳은 기사에게 한국어로 묻고 "응"으로 넣음
  m = mark();
  ph.ws.send(JSON.stringify({ type: 'pick', placeId: 'thehyundai' }));
  const picked = await waitAfter(m, /\[Speak\] \[ko\].*더현대 서울을 골랐어요/, 40000);
  const ask = await waitAfter(m, /\[Speak\] \[ko\].*들를까요/, 15000);
  check('서울: 손님이 고른 곳을 기사에게는 한국어로 물음', !!picked && !!ask, tailOf(picked));
  await waitAfter(m, /듣기 요청 \(한국어\)/, 15000);
  await sleep(900);
  shot('s02_pick');
  m = mark();
  demo('answer', '--es', 'q', '응');
  const wp = await waitAfter(m, /경유지 다시 묶음|목적지 요청/, 15000);
  check('서울: "응"으로 경유지에 넣음', !!wp, wp && wp.split('Gureuneun: ')[1]);
  const ack = await ackFor(ph, 1);
  check('서울: 손님 휴대폰에 답이 감', !!ack && ack.accepted === true);
  // 안내 밖의 부탁
  await sleep(3000);
  m = mark();
  demo('asktext', '--es', 'q', "It's too hot");
  const outside = await waitAfter(m, /\[Speak\] \[en\].*didn't get that/i, 15000);
  check('서울: 안내 밖의 부탁은 받지 않음', !!outside, tailOf(outside));
  // 달리는 중 말로 여행 끝: 한 번 더 묻고, "아니"면 계속 감
  await sleep(3000);
  m = mark();
  demo('asktext', '--es', 'q', 'end the trip');
  const sure = await waitAfter(m, /\[Speak\] \[ko\].*여행을 끝낼까요/, 20000);
  check('서울: 여행을 끝내자는 말에는 기사에게 한 번 더 물음', !!sure);
  await sleep(1500);
  shot('s03_end_ask');
  m = mark();
  demo('answer', '--es', 'q', '아니');
  await sleep(5000);
  pull();
  check('서울: "아니"면 여행을 끝내지 않음', !history.slice(m).some(l => /\[Speak\].*(end of our|That's the end)/i.test(l)));
  ph.ws.close();
  return code;
}

const which = process.argv[2] || 'gyeongju';
adb('logcat', '-c');
seen = 0;
try {
  if (which === 'gyeongju') await gyeongju();
  else if (which === 'jeju') await jeju();
  else if (which === 'seoul') await seoul();
} catch (e) { log('오류', e.stack); }
fs.writeFileSync(path.join(OUT, which + '_log.txt'), history.join('\n'));
fs.writeFileSync(path.join(OUT, which + '_result.txt'), results.join('\n'));
console.log('\n' + results.join('\n'));
process.exit(0);
