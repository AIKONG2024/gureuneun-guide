// 구르는 가이드 흐름 시험: 에뮬레이터의 차 앱과 그룹 서버를 실제로 돌려 시나리오를 처음부터 끝까지 확인함
// 준비: Pleos Connect 에뮬레이터에 앱 설치, adb forward tcp:8088 tcp:8088 (Virtual Console),
//       앱 [점검] 화면에서 [시연 명령 받기] 켜기 (release 빌드일 때),
//       그룹 서버를 자료 폴더와 함께 켜기: cd companion && DATA_DIR=/tmp/rg-flow-data npm start
// 사용: DATA_DIR=/tmp/rg-flow-data node tools/demo/flow.mjs gyeongju | jeju | seoul | care
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
const shot = name => { try { fs.writeFileSync(path.join(OUT, name + '.png'), execFileSync(ADB, ['exec-out', 'screencap', '-p'], { maxBuffer: 64 << 20 })); log('캡처', name); } catch (e) { log('캡처 실패', name); } };

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
  await vc('/vehicle/gear?gear=Park');
  await vc('/vehicle/turn-signal?mode=Off');
  await vc('/vehicle/body?front_wiper=Off');
  await vc('/vehicle/lights?feature=headlamp_mode&value=Off');
  await vc('/vehicle/body?window_fl=0&window_fr=0&window_rl=0&window_rr=0');
  await vc('/vehicle/hvac?recirc=true');
  await vc('/vehicle/ev-battery?field=soc&value=80');
  await vc('/vehicle/odometer?remaining_range_m=380000');
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

  // 먼저 챙기기: 두 시간 넘게 달림 → 쉬어 가기 → "응"
  m = mark();
  demo('drivetime', '--ei', 'min', '125');
  const rest = await waitAfter(m, /\[Speak\].*쉬어 갈까요/, 40000);
  check('경주: 두 시간 넘게 달리면 쉬어 가자고 함', !!rest, rest && rest.split('] ').pop());
  const listen1 = await waitAfter(m, /듣기 요청/, 20000);
  check('경주: 물은 뒤 마이크를 엶', !!listen1, listen1 && listen1.split('Gureuneun: ')[1]);
  await sleep(900);
  shot('g03_rest_offer');
  m = mark();
  demo('answer', '--es', 'q', '응');
  const restNav = await waitAfter(m, /휴게소/, 15000);
  check('경주: "응"으로 휴게소 검색', !!restNav, restNav && restNav.split('Gureuneun: ')[1]);
  await sleep(3000);
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
  await waitAfter(m, /듣기 요청/, 15000);
  await sleep(900);
  shot('g04_pick_docked');
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

  // 여행 끝 → 한 번 더 확인 → 기록 동의 → 보내기
  await sleep(2000);
  m = mark();
  demo('endask');
  await sleep(2500);
  shot('g07_end_confirm');
  demo('answer', '--es', 'q', '응');
  const fin = await waitAfter(m, /\[Speak\].*(마치며|다녀왔|오늘|또 만나)/, 20000);
  check('경주: 끝내자는 확인 뒤 여행 끝', !!fin, fin && fin.split('] ').pop().slice(0, 60));
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
  m = mark();
  demo('asktext', '--es', 'q', '창문 닫아 줘');
  const refuse = await waitAfter(m, /\[Speak\].*달리는 동안에는 창문을 움직이지 않아요/, 15000);
  check('제주: 달리는 중 "창문 닫아 줘"는 스위치로 안내', !!refuse);
  m = mark();
  demo('asktext', '--es', 'q', '창문 열지 마');
  const keep = await waitAfter(m, /\[Speak\].*창문은 그대로/, 15000);
  check('제주: "창문 열지 마"는 열지 않음', !!keep);
  // 세운 뒤 닫기
  m = mark();
  await vc('/vehicle/gear?gear=Park');
  await sleep(2500);
  demo('asktext', '--es', 'q', '창문 닫아 줘');
  const closed = await waitAfter(m, /창문 0\/10/, 15000);
  check('제주: 서 있을 때 "창문 닫아 줘"는 1.5초 뒤 닫음', !!closed);
  // 2호차가 주차장을 보냄 → "그리로 안내할까요?"
  m = mark();
  const sc = SCN.jeju.pickables.find(p => p.kind === 'parking');
  car2.send(JSON.stringify({ type: 'pick', id: sc.id, placeId: sc.id, name: sc.name.ko, lat: sc.lat, lon: sc.lon, kind: 'parking' }));
  const park = await waitAfter(m, /\[Speak\].*안내할까요/, 20000);
  check('제주: 뒤차가 보낸 주차장을 "그리로 안내할까요?"로 물음', !!park, park && park.split('] ').pop());
  m = mark();
  demo('answer', '--es', 'q', '응');
  const dest = await waitAfter(m, /목적지 요청/, 15000);
  check('제주: "응"이면 목적지로', !!dest, dest && dest.split('Gureuneun: ')[1]);
  car2.close(); ph.ws.close();
  return code;
}

// ------------------------------------------------------------ 서울 (영어)
async function seoul() {
  await resetCar();
  const { code } = await startTrip('seoul', 'en');
  let m = mark();
  demo('go');
  const dep = await waitAfter(m, /\[Speak\] \[en\]|\[Speak\]\s*\[en\]|\[en\]/, 25000);
  check('서울: 영어로 출발 안내', !!dep, dep && dep.split('] ').pop().slice(0, 70));
  await vc('/vehicle/gear?gear=Drive');
  await sleep(6000);
  m = mark();
  demo('asktext', '--es', 'q', "what's that on the right");
  const ans = await waitAfter(m, /\[Speak\] \[en\]/, 20000);
  check('서울: 영어로 물으면 영어로 답', !!ans, ans && ans.split('] ').pop().slice(0, 80));
  m = mark();
  demo('drivetime', '--ei', 'min', '125');
  const rest = await waitAfter(m, /\[Speak\] \[en\].*break/i, 30000);
  check('서울: 영어로 쉬어 가기 제안', !!rest, rest && rest.split('] ').pop());
  m = mark();
  demo('answer', '--es', 'q', 'yes');
  const nav = await waitAfter(m, /휴게소|rest area|검색 화면/i, 15000);
  check('서울: "yes"로 답함', !!nav, nav && nav.split('Gureuneun: ')[1]);
  shot('s01_english');
  return code;
}

// ------------------------------------------------------------ 충전
async function care() {
  await resetCar();
  await startTrip('gyeongju', 'ko');
  let m = mark();
  demo('go');
  await vc('/vehicle/gear?gear=Drive');
  await sleep(6000);
  m = mark();
  await vc('/vehicle/ev-battery?field=soc&value=15');
  const off = await waitAfter(m, /\[Speak\].*(충전소|충전하)/, 40000);
  check('충전: 배터리가 빠듯하면 충전하자고 함', !!off, off && off.split('] ').pop());
  const mic = await waitAfter(m, /듣기 요청/, 20000);
  check('충전: 제안을 말한 뒤 마이크를 엶', !!mic);
  await sleep(800);
  shot('c01_charge_listening');
  m = mark();
  demo('answer', '--es', 'q', '응');
  const nav = await waitAfter(m, /충전소 화면 요청/, 15000);
  check('충전: "응"이면 순정 내비 주변 충전소', !!nav);
  // 순정 내비의 주변 충전소 화면이 뜰 때까지 잠깐 기다렸다가 찍음
  await sleep(6000);
  shot('c02_chargers');
  m = mark();
  demo('asktext', '--es', 'q', '기름 얼마나 남았어?');
  const st = await waitAfter(m, /\[Speak\].*배터리는/, 15000);
  check('충전: 전기차는 기름을 물어도 배터리로 답', !!st, st && st.split('] ').pop());
  await vc('/vehicle/ev-battery?field=soc&value=80');
}

const which = process.argv[2] || 'gyeongju';
adb('logcat', '-c');
seen = 0;
try {
  if (which === 'gyeongju') await gyeongju();
  else if (which === 'jeju') await jeju();
  else if (which === 'seoul') await seoul();
  else if (which === 'care') await care();
} catch (e) { log('오류', e.stack); }
fs.writeFileSync(path.join(OUT, which + '_log.txt'), history.join('\n'));
fs.writeFileSync(path.join(OUT, which + '_result.txt'), results.join('\n'));
console.log('\n' + results.join('\n'));
process.exit(0);

