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
const vc = async p => { try { return (await fetch(VC + p, { method: 'POST' })).status; } catch { return 'ERR'; } };
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

// 휴대폰 흉내: 그룹 키로 들어와 퀴즈는 시나리오 자료의 정답 글자로 맞힘
function phone(code, k, scenarioId, lang = 'ko', name = '하준') {
  const ws = new WebSocket(WS_URL + '/ws?code=' + code + (k ? '&k=' + k : ''));
  const p = { ws, msgs: [], quiz: 0, answered: 0 };
  ws.on('error', () => {});
  ws.on('message', d => {
    let m; try { m = JSON.parse(String(d)); } catch { return; }
    p.msgs.push(m);
    if (m.type === 'quiz') {
      p.quiz++;
      const q = SCN[scenarioId].quiz[m.placeId || m.id];
      const right = q ? q.choices[lang][q.answer] : null;
      const idx = right ? m.choices.indexOf(right) : 0;
      if ('answer' in m) log('!! 퀴즈 정답이 휴대폰에 들어옴', JSON.stringify(m));
      setTimeout(() => { ws.send(JSON.stringify({ type: 'quiz_answer', id: m.id, choice: Math.max(0, idx), by: name })); p.answered++; log('휴대폰 퀴즈 답', m.placeId, '→', m.choices[idx]); }, 1500);
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
  await vc('/vehicle/body?window_fl=0&window_fr=0&window_rl=0&window_rr=0');
  await vc('/vehicle/seat?kind=occupied&area=rear_left&value=false');
  await vc('/vehicle/seat?kind=occupied&area=rear_right&value=false');
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

async function answerVoice(q, label) {
  const m = mark();
  demo('answer', '--es', 'q', q);
  return m;
}

const say = l => l && l.includes('[Speak]');

// ------------------------------------------------------------ 경주
async function gyeongju() {
  await resetCar();
  const { code, k } = await startTrip('gyeongju', 'ko');
  if (!code) return;
  const ph = await phone(code, k, 'gyeongju');
  check('경주: 휴대폰(그룹 키) 접속', ph.ws.readyState === 1);
  await sleep(3000);
  shot('g01_rollcall');
  let m = mark();
  demo('go');
  check('경주: 출발 안내', !!(await waitAfter(m, /\[Speak\].*(출발|가 볼까요|떠나)/, 25000)));
  await vc('/vehicle/gear?gear=Drive');
  m = mark();
  check('경주: 도시 진입 오프닝', !!(await waitAfter(m, /영역 진입|도시|경주/, 60000)));
  await sleep(4000);
  shot('g02_driving');

  demo('jump');

  // 대릉원 앞에서 창밖 묻기 (코스 밖 천마총)
  await sleep(6000);
  m = mark();
  demo('asktext', '--es', 'q', '오른쪽에 보이는 거 뭐야?');
  const askA = await waitAfter(m, /\[Speak\].*(오른쪽|보이는 곳)/, 20000);
  check('경주: 대릉원 앞에서 창밖 묻기', !!askA, askA && askA.split('] ').pop().slice(0, 70));
  await sleep(1500);
  shot('g03b_ask');

  // 휴대폰에서 카페 고르기 → "가는 길에 들를까요?" → "응"
  await sleep(4000);
  m = mark();
  ph.ws.send(JSON.stringify({ type: 'pick', placeId: 'hwangridan' }));
  const ask = await waitAfter(m, /\[Speak\].*(들를까요|안내할까요)/, 30000);
  check('경주: 휴대폰에서 고른 카페를 차가 물음', !!ask, ask && ask.split('] ').pop());
  const listen1 = await waitAfter(m, /듣기 요청/, 15000);
  check('경주: 물은 뒤 마이크를 엶', !!listen1, listen1 && listen1.split('Gureuneun: ')[1]);
  await sleep(900);
  shot('g04_pick_docked');
  const docked = await waitScreen(/가는 길에 들르기/, 4000);
  if (docked) check('경주: 달리는 동안 고른 장소의 답은 둘뿐 (들르기, 나중에)', docked.some(t => /^나중에$/.test(t)) && !docked.some(t => /목적지로 바꾸기/.test(t)), docked.filter(t => /들르기|나중에|목적지|말로 답/.test(t)).join(' / '));
  else log('화면 글자를 읽지 못함 (움직이는 화면). 캡처 g04_pick_docked.png 로 확인');
  m = mark();
  demo('answer', '--es', 'q', '응');
  const wp = await waitAfter(m, /경유지 다시 묶음|경유지 추가|목적지 요청/, 15000);
  check('경주: "응" 한마디로 경유지에 넣음', !!wp, wp && wp.split('Gureuneun: ')[1]);
  const ack = await (async () => { for (let i = 0; i < 10; i++) { const a = ph.msgs.find(x => x.type === 'pick_ack'); if (a) return a; await sleep(500); } return null; })();
  check('경주: 휴대폰에 운전자 답이 감', !!ack && ack.accepted === true, ack && JSON.stringify({ mode: ack.mode, accepted: ack.accepted }));

  // 아이 퀴즈 두 개 → 별 2개
  for (let i = 0; i < 3 && ph.answered < 2; i++) {
    m = mark();
    demo('jump');
    await waitAfter(m, /이야기 끝|퀴즈|afterStory|quiz/i, 90000);
    for (let j = 0; j < 40 && ph.answered < i + 1; j++) await sleep(1000);
  }
  check('경주: 휴대폰에 아이 퀴즈가 옴', ph.quiz >= 1, 'quiz ' + ph.quiz + ', answered ' + ph.answered);
  const leak = ph.msgs.filter(x => x.type === 'quiz' && 'answer' in x).length;
  check('경주: 퀴즈 정답이 휴대폰에 미리 가지 않음', leak === 0);
  await sleep(3000);
  const treasureMsgs = ph.msgs.filter(x => x.type === 'treasure' || x.stars != null);
  log('별 관련 메시지', JSON.stringify(treasureMsgs.slice(-2)));

  // 세우면 보물 상자
  m = mark();
  await vc('/vehicle/gear?gear=Park');
  const tre = await waitAfter(m, /\[Speak\].*보물 상자를 열어 볼까요/, 30000);
  check('경주: 별을 모으고 세우면 보물 상자를 물음', !!tre, tre && tre.split('] ').pop());
  if (tre) {
    await sleep(2500);
    shot('g05_treasure_ask');
    const dialog = await waitScreen(/열어 주세요/, 4000);
    if (dialog) check('경주: 서 있을 때 보물 상자 물음 창과 단추', dialog.some(t => /보물 상자를 열어 볼까요/.test(t)) && dialog.some(t => /^나중에$/.test(t)), dialog.filter(t => /보물|열어|나중에|별/.test(t)).join(' / '));
    else log('화면 글자를 읽지 못함 (움직이는 화면). 캡처 g05_treasure_ask.png 로 확인');
    m = mark();
    demo('answer', '--es', 'q', '응');
    const open = await waitAfter(m, /\[Speak\].*(보물 상자가 열렸어요|직접 열어)/, 20000);
    check('경주: "응"이면 글로브 박스를 풀고 결과를 말함', !!open, open && open.split('] ').pop());
    await sleep(2000);
    shot('g05_treasure');
  }
  shot('g06_parked');

  // 말로 묻기 (문장만 넣어)
  m = mark();
  demo('asktext', '--es', 'q', '오른쪽에 보이는 거 뭐야?');
  const ans = await waitAfter(m, /\[Speak\].*(오른쪽|왼쪽|앞쪽|보이는 곳)/, 20000);
  check('경주: 말로 묻기 답', !!ans, ans && ans.split('] ').pop().slice(0, 80));
  m = mark();
  demo('answer', '--es', 'q', '응');
  const stray = await waitAfter(m, /\[Speak\].*여쭤본 게 없어요/, 12000);
  check('경주: 물은 게 없을 때 "응"', !!stray);
  // 여행 안내 밖의 부탁: 엉뚱한 일을 하지 않고 할 수 있는 것을 알려 줌
  m = mark();
  demo('asktext', '--es', 'q', '배터리 얼마나 남았어?');
  const outside = await waitAfter(m, /\[Speak\].*잘 못 알아들었어요/, 12000);
  check('경주: 안내 밖의 부탁("배터리 얼마나 남았어")에는 할 수 있는 것을 알려 줌', !!outside, outside && outside.split('] ').pop());

  // 여행 끝 → 한 번 더 확인 → 기록 동의 → 보내기
  await sleep(2000);
  m = mark();
  demo('endask');
  await sleep(2500);
  shot('g07_end_confirm');
  demo('answer', '--es', 'q', '응');
  const fin = await waitAfter(m, /\[Speak\].*(마치며|다녀왔|오늘|또 만나)/, 20000);
  check('경주: 끝내자는 확인 뒤 여행 끝', !!fin, fin && fin.split('] ').pop().slice(0, 60));
  const restored = await waitAfter(m, /앱이 바꾼 차 상태를 모두 되돌림/, 15000);
  check('경주: 여행이 끝나면 앱이 바꾼 조명과 글로브 박스 잠금을 원래대로', !!restored);
  await sleep(4000);
  shot('g08_record_consent');
  const before = fs.existsSync(path.join(DATA, code)) ? JSON.stringify(groupFile(code) || {}).includes('"record":{') : false;
  check('경주: 동의 전에는 기록을 올리지 않음', !before);
  demo('share');
  await sleep(4000);
  const after = JSON.stringify(groupFile(code) || {});
  check('경주: [보내기]로 기록 올림', after.includes('"record"') && !after.includes('"record":null'));
  const recMsg = ph.msgs.find(x => x.type === 'record');
  check('경주: 휴대폰에 기록이 옴', !!recMsg, recMsg ? Object.keys(recMsg.record || {}).join(',') : '');
  shot('g09_record_shared');
  const page = await fetch(SRV + '/g/' + code + '/record?k=' + k + '&car=1').then(r => r.status).catch(() => 'ERR');
  check('경주: 휴대폰 기록 페이지(키 포함) 열림', page === 200, 'status ' + page);
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
  demo('go');
  await vc('/vehicle/gear?gear=Drive');
  await sleep(8000);
  shot('j01_driving');
  // 휴대폰이 바닷가 카페를 고름 → 두 차가 물음
  m = mark();
  ph.ws.send(JSON.stringify({ type: 'pick', placeId: 'aewol_cafe' }));
  const ask = await waitAfter(m, /\[Speak\].*들를까요/, 30000);
  check('제주: 고른 카페를 "가는 길에 들를까요?"로 물음', !!ask, ask && ask.split('] ').pop());
  const car2pick = car2msgs.find(x => x.type === 'pick');
  check('제주: 2호차에도 같은 장소가 감', !!car2pick, car2pick && car2pick.name);
  await sleep(1500);
  m = mark();
  demo('answer', '--es', 'q', '응');
  const wp = await waitAfter(m, /경유지 다시 묶음/, 15000);
  check('제주: "응"으로 경유지를 길 순서대로 다시 묶음', !!wp, wp && wp.split('Gureuneun: ')[1]);
  const navi = await waitAfter(m, /목적지 요청.*경유지 2곳|목적지 요청/, 15000);
  check('제주: 순정 내비에 경로 다시 요청', !!navi, navi && navi.split('Gureuneun: ')[1]);

  // 협재 바닷가에 세우면 창문
  m = mark();
  demo('jump', '--es', 'to', 'hyeopjae');
  await waitAfter(m, /영역 진입.*협재|협재/, 60000);
  await sleep(3000);
  m = mark();
  await vc('/vehicle/gear?gear=Park');
  const win = await waitAfter(m, /\[Speak\].*창문을 조금 내려/, 30000);
  check('제주: 바닷가에 세우면 창문을 열지 물음', !!win, win && win.split('] ').pop());
  await sleep(2500);
  shot('j02_window_ask');
  m = mark();
  demo('answer', '--es', 'q', '응');
  const moved = await waitAfter(m, /창문 2\/10/, 15000);
  check('제주: "응"이면 앞 창문만 2/10', !!moved && !/ROW_2/.test(moved), moved && moved.split('Gureuneun: ')[1]);
  await sleep(3000);
  shot('j03_window_open');
  // 달리기 시작하면 앱이 올리지 않고 알림
  m = mark();
  await vc('/vehicle/gear?gear=Drive');
  const note = await waitAfter(m, /\[Speak\].*창문 스위치/, 20000);
  check('제주: 출발할 때 창문을 저절로 올리지 않고 알림', !!note, note && note.split('] ').pop());
  // 창문을 움직여 달라는 말은 받지 않음 (창문은 차가 서서 물었을 때 "응"으로만 움직임)
  m = mark();
  demo('asktext', '--es', 'q', '창문 닫아 줘');
  const refuse = await waitAfter(m, /\[Speak\].*잘 못 알아들었어요/, 15000);
  pull();
  check('제주: 달리는 중 "창문 닫아 줘"로는 창문을 움직이지 않음', !!refuse && !history.slice(m).some(l => /창문 \d+\/10/.test(l)));
  // 운전자가 스위치로 닫으면 앱도 '열려 있음' 표시를 거둠
  m = mark();
  await vc('/vehicle/body?window_fl=0&window_fr=0');
  const closed = await waitAfter(m, /앞 창문이 닫힘/, 15000);
  check('제주: 운전자가 창문을 닫으면 열림 표시를 거둠', !!closed);
  // 휴대폰이 주차장을 고름 → "그리로 안내할까요?" → "응"이면 목적지로
  m = mark();
  const lot = SCN.jeju.pickables.find(p => p.kind === 'parking');
  ph.ws.send(JSON.stringify({ type: 'pick', placeId: lot.id }));
  const park = await waitAfter(m, /\[Speak\].*안내할까요/, 30000);
  check('제주: 휴대폰에서 고른 주차장은 "그리로 안내할까요?"로 물음', !!park, park && park.split('] ').pop());
  await sleep(1500);
  m = mark();
  demo('answer', '--es', 'q', '응');
  const dest = await waitAfter(m, /목적지 요청/, 15000);
  check('제주: "응"이면 목적지로', !!dest, dest && dest.split('Gureuneun: ')[1]);
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

// ------------------------------------------------------------ 서울 (영어)
async function seoul() {
  await resetCar();
  const { code, k } = await startTrip('seoul', 'en');
  if (!code) return;
  const ph = await phone(code, k, 'seoul', 'en', 'Alex');
  await sleep(3000);
  shot('s00_rollcall');
  // 점호 창이 떠 있는 채로 차가 움직이면 창을 닫고 안내를 시작함 (달리는 동안 창을 띄워 두지 않음)
  let m = mark();
  await vc('/vehicle/gear?gear=Drive');
  const off = await waitAfter(m, /점호 끝, 출발/, 15000);
  check('서울: 점호 중에 차가 움직이면 점호를 닫고 출발', !!off);
  const dep = await waitAfter(m, /\[Speak\] \[en\]/, 30000);
  check('서울: 영어로 안내', !!dep, dep && dep.split('] ').pop().slice(0, 70));
  await sleep(6000);
  m = mark();
  demo('asktext', '--es', 'q', "what's that on the right");
  const ans = await waitAfter(m, /\[Speak\] \[en\]/, 20000);
  check('서울: 영어로 물으면 영어로 답', !!ans, ans && ans.split('] ').pop().slice(0, 80));
  await sleep(5000);
  // 휴대폰에서 고른 곳을 영어로 묻고 "yes"로 넣음
  m = mark();
  ph.ws.send(JSON.stringify({ type: 'pick', placeId: 'thehyundai' }));
  const ask = await waitAfter(m, /\[Speak\] \[en\].*(stop there on the way|take us there)/i, 40000);
  check('서울: 휴대폰에서 고른 곳을 영어로 물음', !!ask, ask && ask.split('] ').pop());
  await waitAfter(m, /듣기 요청/, 15000);
  await sleep(900);
  shot('s01_english');
  m = mark();
  demo('answer', '--es', 'q', 'yes');
  const wp = await waitAfter(m, /경유지 다시 묶음|목적지 요청/, 15000);
  check('서울: "yes"로 경유지에 넣음', !!wp, wp && wp.split('Gureuneun: ')[1]);
  // 안내 밖의 부탁
  await sleep(3000);
  m = mark();
  demo('asktext', '--es', 'q', "It's too hot");
  const outside = await waitAfter(m, /\[Speak\] \[en\].*didn't get that/i, 15000);
  check('서울: 안내 밖의 부탁에는 할 수 있는 것을 영어로 알려 줌', !!outside, outside && outside.split('] ').pop());
  // 달리는 중 말로 여행 끝: 한 번 더 묻고, "no"면 계속 감
  await sleep(3000);
  m = mark();
  demo('asktext', '--es', 'q', 'end the trip');
  const sure = await waitAfter(m, /\[Speak\] \[en\].*end today's trip/i, 20000);
  check('서울: "end the trip"에는 한 번 더 물음', !!sure, sure && sure.split('] ').pop());
  await sleep(1500);
  shot('s02_end_docked');
  m = mark();
  demo('answer', '--es', 'q', 'no');
  await sleep(5000);
  pull();
  check('서울: "no"면 여행을 끝내지 않음', !history.slice(m).some(l => /\[Speak\] \[en\].*(end of our|That's the end)/i.test(l)));
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

