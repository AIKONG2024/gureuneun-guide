// 구르는 가이드 시연 영상용 휴대폰 화면 녹화
//   node tools/demo/phone_rec.mjs
// 차량 앱이 붙은 서버(기본 http://localhost:8787)의 여행 GJ1016 을 300ms 마다 읽어 차량 진행에 맞춰 휴대폰 웹 '우리 여행'을
// 엄마 휴대폰으로 조작하고, 화면(390x844, 2배)을 780x1688 영상으로 녹화한다. 서버 reset 은 하지 않는다(녹화 직전에 따로).
//
// 결과(/tmp/rec, 같은 이름이 있으면 .old 로 옮겨 둠)
//   phone.webm        휴대폰 화면 영상. 누른 자리에 반투명 원이 퍼짐
//   phone_start.txt   녹화를 시작한 순간(새 페이지를 연 직후)의 Date.now(). 영상 0초와 0.1초 안쪽으로 맞음
//   phone_events.tsv  epoch ms <탭> 동작. 휴대폰에서 한 동작과 '차:' 로 시작하는 차량 상태 변화
//
// 순서
//   1) 지금 탭 3초 → 담기 탭 → [붙여 넣기](블로그 링크) → 미리보기 뜨고 1.5초 → [우리 여행에 담기] → 담김 표시 2초 → 일정 탭 4초 → 지금 탭
//   2) 차가 출발하면 지금 탭에 머묾(여행 모드, 제안, 명소 안내가 뜨는 그대로)
//   3) 황리단길 카페가 visited 가 되면 2초 뒤 기록 탭에서 사진 1장(daereungwon_2.jpg)을 올리고 지금 탭으로
//   4) 계림 안내(now.id gyerim)가 오면 그 카드를 화면 가운데로
//   5) 여행 영상이 오면 알림 2초 → 기록 탭 → 영상 8초 → 여행 티켓이 보이게 스크롤 → 6초 뒤 끝
//   9분이 지나거나 Ctrl+C 를 누르면 그때까지 찍은 영상을 저장하고 끝냄
//
// 환경 변수: BASE(서버 주소), REC_DIR(결과 폴더), HEADED=1(창을 띄워 보면서 녹화), CHROME, PLAYWRIGHT_CORE
// 붙여 넣기: 블로그 앱에서 링크를 복사해 둔 상황으로, [붙여 넣기]가 읽는 클립보드 값만 이 링크로 바꿔 둠(시스템 클립보드는 건드리지 않음)
import { createRequire } from 'node:module';
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_CORE || 'playwright-core');   // tools/demo 에서 npm install
const CHROME = process.env.CHROME || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const BASE = String(process.env.BASE || 'http://localhost:8787').replace(/\/+$/, '');
const REC = process.env.REC_DIR || '/tmp/rec';
const HEADED = process.env.HEADED === '1';
const CODE = 'GJ1016';
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const PHOTO = path.join(ROOT, 'companion/web/photos/daereungwon_2.jpg');
const LINK = 'https://blog.naver.com/gyeongju_trip/경주-황리단길-카페-후기';
const MAX_MS = 9 * 60 * 1000;
const POLL_MS = 300;
const VIEW = { width: 390, height: 844 };
const VIDEO = { width: 780, height: 1688 };
const OUT = { video: path.join(REC, 'phone.webm'), start: path.join(REC, 'phone_start.txt'), events: path.join(REC, 'phone_events.tsv') };
const RAW = path.join(REC, '.phone_raw');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const STOP = new Error('stop');
let stopping = '';
let deadline = Infinity;
let startMs = 0;

// ------------------------------------------------------------ 기록
function ev(action) {
  const t = Date.now();
  if (startMs) fs.appendFileSync(OUT.events, t + '\t' + action + '\n');
  console.log(((t - (startMs || t)) / 1000).toFixed(1).padStart(6) + 's  ' + action);
}
function note(msg) {
  console.log('        (' + msg + ')');
}

// ------------------------------------------------------------ 멈춤 처리(9분, Ctrl+C)
function check() {
  if (!stopping && Date.now() >= deadline) stopping = '9분 지남';
  if (stopping) throw STOP;
}
async function nap(ms) {
  const end = Date.now() + ms;
  for (;;) {
    check();
    const left = end - Date.now();
    if (left <= 0) return;
    await sleep(Math.min(100, left));
  }
}
async function until(fn, ms, every) {
  const end = Date.now() + ms;
  for (;;) {
    check();
    try { if (await fn()) return true; } catch (e) { }
    if (Date.now() >= end) return false;
    await sleep(every || 150);
  }
}
for (const sig of ['SIGINT', 'SIGTERM']) {
  process.on(sig, () => {
    if (stopping === sig) process.exit(130);
    if (!startMs) process.exit(130);
    stopping = sig;
    console.log('\n멈추는 중이에요. 지금까지 찍은 영상을 저장해요(한 번 더 누르면 바로 끝)');
  });
}

// ------------------------------------------------------------ 서버(차량 상태)
async function getTrip() {
  const r = await fetch(BASE + '/api/trip/' + CODE, { cache: 'no-store', signal: AbortSignal.timeout(2500) });
  if (!r.ok) throw new Error('HTTP ' + r.status);
  const j = await r.json();
  if (!j || !j.trip) throw new Error('trip 없음');
  return j.trip;
}
const NOW_WORD = { sight: '명소 안내', offer: '들를지 제안', mode: '여행 모드', arrive: '도착' };
const STATUS_WORD = { pending: '전달 중', planned: '일정', open: '제안 예정', next: '가는 길', visited: '들름', skipped: '건너뜀' };
const isCafe = (p) => p && (p.id === 'hwangridan' || /황리단/.test(p.name || ''));
const nowKey = (n) => (n ? (n.type || 'sight') + '|' + (n.id || n.title || '') : '');
let trip = null;
const flags = { driving: false, mode: false, nowKey: '', cafe: '', cafeVisitedAt: 0, recordAt: 0 };

function onState(t, quiet) {
  trip = t;
  const say = (s) => { if (!quiet) ev(s); };
  const driving = !!(t.car && t.car.driving === true);
  if (driving !== flags.driving) { flags.driving = driving; say(driving ? '차: 달림' : '차: 멈춤'); }
  const mode = !!(t.mode && t.mode.active);
  if (mode !== flags.mode) { flags.mode = mode; if (mode) say('차: 여행 모드 켜짐'); }
  const nk = nowKey(t.now);
  if (nk !== flags.nowKey) {
    flags.nowKey = nk;
    if (t.now) say('차: ' + (NOW_WORD[t.now.type] || t.now.type || '안내') + ' ' + (t.now.title || t.now.id || ''));
  }
  const cafe = (Array.isArray(t.places) ? t.places : []).find(isCafe);
  const st = cafe ? cafe.status || '' : '';
  if (st !== flags.cafe) {
    flags.cafe = st;
    if (st) say('차: 황리단길 카페 ' + (STATUS_WORD[st] || st));
    if (st === 'visited' && !flags.cafeVisitedAt) flags.cafeVisitedAt = Date.now();
  }
  if (t.record && t.record.ready && !flags.recordAt) { flags.recordAt = Date.now(); say('차: 여행 영상 준비됨'); }
}
let polling = true;
async function pollLoop() {
  while (polling) {
    try { onState(await getTrip()); } catch (e) { }
    await sleep(POLL_MS);
  }
}

// ------------------------------------------------------------ 페이지에 넣는 스크립트
// 누른 자리에 반투명 원이 잠깐 퍼짐(손가락을 끌면 따라감). 페이지의 동작에는 끼어들지 않음(pointer-events:none)
function touchViz() {
  if (window.__ggTouch) return;
  window.__ggTouch = true;
  const css = '.gg-touch{position:fixed;z-index:2147483647;left:0;top:0;width:48px;height:48px;margin:-24px 0 0 -24px;border-radius:50%;pointer-events:none;' +
    'background:rgba(20,23,28,.24);box-shadow:inset 0 0 0 2.5px rgba(255,255,255,.9),0 0 0 1.5px rgba(20,23,28,.22);' +
    'transition:transform .5s cubic-bezier(.2,.7,.3,1),opacity .5s ease-out}.gg-touch.up{transform:scale(1.9);opacity:0}';
  const dots = new Map();
  let styled = false;
  let lastTouch = 0;
  const host = () => document.documentElement;
  function down(id, x, y) {
    if (!host()) return;
    if (!styled) {
      const s = document.createElement('style');
      s.textContent = css;
      host().appendChild(s);
      styled = true;
    }
    const d = document.createElement('div');
    d.className = 'gg-touch';
    d.style.left = x + 'px';
    d.style.top = y + 'px';
    host().appendChild(d);
    dots.set(id, { d, t: performance.now() });
  }
  function move(id, x, y) {
    const o = dots.get(id);
    if (o) { o.d.style.left = x + 'px'; o.d.style.top = y + 'px'; }
  }
  function up(id) {
    const o = dots.get(id);
    if (!o) return;
    dots.delete(id);
    setTimeout(() => { o.d.classList.add('up'); setTimeout(() => o.d.remove(), 650); }, Math.max(0, 170 - (performance.now() - o.t)));
  }
  const opt = { capture: true, passive: true };
  const each = (e, f) => { for (const t of Array.from(e.changedTouches)) f('t' + t.identifier, t.clientX, t.clientY); };
  addEventListener('touchstart', (e) => { lastTouch = Date.now(); each(e, down); }, opt);
  addEventListener('touchmove', (e) => each(e, move), opt);
  addEventListener('touchend', (e) => each(e, up), opt);
  addEventListener('touchcancel', (e) => each(e, up), opt);
  addEventListener('pointerdown', (e) => { if (e.pointerType !== 'touch' && Date.now() - lastTouch > 1000) down('p' + e.pointerId, e.clientX, e.clientY); }, opt);
  addEventListener('pointermove', (e) => { if (e.pointerType !== 'touch') move('p' + e.pointerId, e.clientX, e.clientY); }, opt);
  addEventListener('pointerup', (e) => { if (e.pointerType !== 'touch') up('p' + e.pointerId); }, opt);
}
// 블로그 앱에서 링크를 복사해 둔 상황: [붙여 넣기]가 읽는 값만 이 링크로(시스템 클립보드는 그대로)
function fakeClipboard(link) {
  const readText = async () => link;
  try {
    if (navigator.clipboard) Object.defineProperty(navigator.clipboard, 'readText', { value: readText, configurable: true });
    else Object.defineProperty(navigator, 'clipboard', { value: { readText, writeText: async () => { } }, configurable: true });
  } catch (e) { }
}

// ------------------------------------------------------------ 시작
if (!fs.existsSync(PHOTO)) {
  console.error('올릴 사진이 없어요: ' + PHOTO);
  process.exit(1);
}
try {
  onState(await getTrip(), true);
} catch (e) {
  console.error('서버에 닿지 않아요: ' + BASE + ' (' + e.message + ')');
  process.exit(1);
}
if (flags.recordAt) console.warn('주의: 여행 영상이 이미 준비된 상태예요. 서버를 reset 했는지 확인하세요');
else if (flags.cafe) console.warn('주의: 황리단길 카페가 이미 여행에 있어요(' + (STATUS_WORD[flags.cafe] || flags.cafe) + '). 서버를 reset 했는지 확인하세요');

fs.mkdirSync(RAW, { recursive: true });
for (const f of Object.values(OUT)) if (fs.existsSync(f)) fs.renameSync(f, f + '.old');

// Ctrl+C 는 이 스크립트가 받아서 영상을 저장한 뒤 끝냄(Playwright 기본 처리는 저장 전에 프로세스를 끝내 버림)
// --force-device-scale-factor=2: 이것이 없으면 headless 화면 캡처가 390x844 로 와서 780x1688 영상의 왼쪽 위 1/4 에만 그려지고 나머지는 회색이 됨
const browser = await chromium.launch({ executablePath: CHROME, headless: !HEADED, handleSIGINT: false, handleSIGTERM: false, args: ['--autoplay-policy=no-user-gesture-required', '--force-device-scale-factor=2'] });
const context = await browser.newContext({
  viewport: VIEW, deviceScaleFactor: 2, isMobile: true, hasTouch: true, locale: 'ko-KR', timezoneId: 'Asia/Seoul',
  recordVideo: { dir: RAW, size: VIDEO },
});
await context.addInitScript(touchViz);
await context.addInitScript(fakeClipboard, LINK);
const page = await context.newPage();
startMs = Date.now();
deadline = startMs + MAX_MS;
fs.writeFileSync(OUT.start, String(startMs));
fs.writeFileSync(OUT.events, '');
console.log('휴대폰 녹화 시작 · ' + BASE + '/t/' + CODE + '?me=mom');
ev('녹화 시작');
const cdp = await context.newCDPSession(page);
page.on('crash', () => { stopping = stopping || '페이지 멈춤'; });
const poller = pollLoop();

// ------------------------------------------------------------ 동작
async function tap(sel, label) {
  check();
  const loc = page.locator(sel).first();
  try {
    await loc.waitFor({ state: 'visible', timeout: 6000 });
    await loc.tap({ timeout: 6000 });
  } catch (e) {
    note('누르지 못함 ' + sel + ' · ' + String(e.message || e).split('\n')[0]);
    return false;
  }
  if (label) ev(label);
  return true;
}
const tabNow = () => page.evaluate(() => document.body.dataset.tab).catch(() => '');
async function toTab(tab, label) {
  if ((await tabNow()) !== tab) await tap('#tabbar [data-tab="' + tab + '"]', label);
}

// 손가락으로 쓸어 올리듯 스크롤해서 target 아래 끝(과 그 아래 버튼)이 보이게 함
async function swipeToShow(panelSel, targetSel, label) {
  const need = await page.evaluate(([ps, ts]) => {
    const p = document.querySelector(ps);
    const t = document.querySelector(ts);
    if (!p || !t) return 0;
    const pr = p.getBoundingClientRect();
    const tr = t.getBoundingClientRect();
    const want = Math.max(0, tr.bottom - (pr.bottom - 14));
    return Math.min(want, p.scrollHeight - p.clientHeight - p.scrollTop);
  }, [panelSel, targetSel]);
  if (need < 6) { ev(label + '(이미 다 보여요)'); return; }
  const before = await page.evaluate((ps) => document.querySelector(ps).scrollTop, panelSel);
  try {
    await cdp.send('Input.synthesizeScrollGesture', { x: VIEW.width / 2, y: Math.round(VIEW.height * 0.62), xDistance: 0, yDistance: -Math.round(need + 24), speed: 700, gestureSourceType: 'touch', preventFling: true });
  } catch (e) { }
  await nap(250);
  const moved = await page.evaluate((ps) => document.querySelector(ps).scrollTop, panelSel) - before;
  if (moved < need - 4) {
    await page.evaluate(([ps, d]) => { const p = document.querySelector(ps); p.scrollTo({ top: p.scrollTop + d, behavior: 'smooth' }); }, [panelSel, need - moved]);
  }
  ev(label);
}

// 1) 담기
async function addPlace() {
  await nap(3000);
  await tap('#tabbar [data-tab="add"]', '담기 탭');
  await nap(1300);
  await tap('#inpBox [data-act="paste"]', '붙여 넣기 누름');
  const pasted = await until(() => page.evaluate(() => document.querySelector('#inpBox').classList.contains('has')), 2000);
  if (!pasted) {
    note('붙여 넣기 버튼이 동작하지 않아 링크 칸에 직접 넣어요');
    await tap('#link', '링크 칸 누름');
    await nap(500);
    await page.keyboard.insertText(LINK);
    ev('링크 붙여 넣음');
  }
  const shown = await until(() => page.evaluate(() => !!document.querySelector('#pv .pv-bd')), 10000);
  ev(shown ? '미리보기 뜸' : '미리보기가 안 떠요');
  await nap(1500);
  if (await page.locator('#addBtn').isDisabled().catch(() => true)) {
    ev('이미 담긴 장소라 담기를 건너뜀');
  } else {
    await tap('#addBtn', '우리 여행에 담기 누름');
    if (await until(() => page.evaluate(() => document.querySelector('#toast').classList.contains('on')), 4000)) ev('담김 표시');
  }
  await nap(2000);
  await tap('#tabbar [data-tab="plan"]', '일정 탭');
  await nap(4000);
  await tap('#tabbar [data-tab="now"]', '지금 탭');
}

// 3) 사진 올리기
async function uploadPhoto() {
  await toTab('rec', '기록 탭');
  await nap(1000);
  if (!(await page.locator('#recTop [data-act="upload"]').count())) {
    note('사진 올리기 버튼이 없어요(여행 영상이 이미 왔어요)');
    return;
  }
  const chooser = page.waitForEvent('filechooser', { timeout: 6000 }).catch(() => null);
  await tap('#recTop [data-act="upload"]', '사진 올리기 누름');
  const fc = await chooser;
  await nap(700);
  if (fc) await fc.setFiles(PHOTO);
  else await page.setInputFiles('#file', PHOTO);
  ev('사진 고름');
  const done = await until(() => page.evaluate(() => !!document.querySelector('#upGrid:not([hidden]) .gi img')), 15000);
  ev(done ? '사진 올림' : '사진이 올라갔는지 확인하지 못함');
  await nap(2500);
  await toTab('now', '지금 탭');
}

// 4) 계림 카드를 화면 가운데로
async function centerNowCard(name) {
  await toTab('now', '지금 탭');
  const shown = await until(() => page.evaluate((n) => ((document.querySelector('#nowBox .ttl') || {}).textContent || '').includes(n), name), 5000);
  if (!shown) { note(name + ' 카드가 화면에 아직 없어요'); return; }
  await nap(700);
  await page.evaluate(() => {
    const c = document.querySelector('#nowBox .nowcard');
    if (c) c.scrollIntoView({ block: 'center', behavior: 'smooth' });
  });
  ev(name + ' 카드 가운데로');
}

// 5) 여행 영상과 티켓
async function finale() {
  if ((await tabNow()) !== 'rec') {
    if (await until(() => page.evaluate(() => document.querySelector('#pill').classList.contains('on')), 4000)) ev('알림 뜸');
    await nap(2000);
  }
  await toTab('rec', '기록 탭');
  await until(() => page.evaluate(() => !!document.querySelector('#recTop .vcard')), 5000);
  await nap(1500);
  await tap('#recTop .vcard', '영상 재생 누름');
  const reelFrame = () => page.frames().find((f) => /\/reel\//.test(f.url()));
  if (await until(async () => { const f = reelFrame(); return !!f && (await f.evaluate(() => !!(window.reel && window.reel.playing))); }, 6000)) ev('영상 나오는 중');
  await nap(8000);
  await tap('#player [data-act="close-player"]', '영상 닫음');
  await nap(900);
  await swipeToShow('#p-rec', '#recTop .bt', '여행 티켓 보이게 스크롤');
  await nap(6000);
}

async function followCar() {
  const did = { photo: false, center: '' };
  for (;;) {
    check();
    if (flags.recordAt) { ev('여행 영상 받음'); await finale(); return; }
    if (flags.cafeVisitedAt && !did.photo && Date.now() - flags.cafeVisitedAt >= 2000) {
      did.photo = true;
      await uploadPhoto();
      continue;
    }
    if (trip && trip.now && trip.now.id === 'gyerim' && did.center !== flags.nowKey) {
      did.center = flags.nowKey;
      await centerNowCard('계림');
      continue;
    }
    await nap(100);
  }
}

let failed = false;
try {
  await page.goto(BASE + '/t/' + CODE + '?me=mom', { waitUntil: 'domcontentloaded', timeout: 20000 });
  await until(() => page.evaluate(() => /명/.test((document.querySelector('#hdSub') || {}).textContent || '') && document.fonts.status === 'loaded'), 15000);
  ev('앱 열림');
  await addPlace();
  await followCar();
} catch (e) {
  if (e !== STOP) {
    failed = true;
    console.error('오류: ' + (e && e.stack ? e.stack : e));
  }
}

// ------------------------------------------------------------ 저장
polling = false;
ev('녹화 끝' + (stopping ? ' (' + stopping + ')' : failed ? ' (오류)' : ''));
const video = page.video();
await context.close().catch(() => { });
let raw = null;
try { raw = await video.path(); } catch (e) { }
await browser.close().catch(() => { });
await poller.catch(() => { });
if (raw && fs.existsSync(raw)) fs.renameSync(raw, OUT.video);
try { fs.rmdirSync(RAW); } catch (e) { }

// 영상 0초의 정확한 시각: Playwright 가 영상 머리(creation_time)에 남긴 녹화 기준 시각 + 첫 프레임 위치(start_time)
function ffprobeBin() {
  for (const d of String(process.env.PATH || '').split(':').concat('/opt/homebrew/bin', '/usr/local/bin')) {
    const f = path.join(d, 'ffprobe');
    if (d && fs.existsSync(f)) return f;
  }
  return null;
}
let info = null;
const probeBin = ffprobeBin();
if (probeBin && fs.existsSync(OUT.video)) {
  try {
    const j = JSON.parse(execFileSync(probeBin, ['-v', 'error', '-show_entries', 'format=start_time,duration:format_tags=creation_time:stream=width,height', '-of', 'json', OUT.video], { encoding: 'utf8' }));
    const m = /^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)\.(\d+)Z$/.exec(String((j.format.tags || {}).creation_time || ''));
    info = {
      created: m && /[1-9]/.test(m[2]) ? Date.parse(m[1] + '.' + (m[2] + '00').slice(0, 3) + 'Z') : NaN,
      start: parseFloat(j.format.start_time) || 0,
      duration: parseFloat(j.format.duration) || 0,
      size: j.streams && j.streams[0] ? j.streams[0].width + 'x' + j.streams[0].height : '',
    };
  } catch (e) { }
}
const startNote = '녹화를 시작한 순간';
const lines = fs.existsSync(OUT.events) ? fs.readFileSync(OUT.events, 'utf8').trim().split('\n').length : 0;
console.log('\n휴대폰 녹화 끝');
console.log('  영상  ' + OUT.video + (info ? '  (' + info.duration.toFixed(1) + '초, ' + info.size + ')' : fs.existsSync(OUT.video) ? '' : '  (만들지 못함)'));
console.log('  시작  ' + OUT.start + '  ' + fs.readFileSync(OUT.start, 'utf8').trim() + ' (' + startNote + ')');
console.log('  동작  ' + OUT.events + '  (' + lines + '줄)');
process.exit(failed || !fs.existsSync(OUT.video) ? 1 : 0);
