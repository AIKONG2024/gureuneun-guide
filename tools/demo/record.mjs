// 구르는 가이드 시연 영상 녹화 도구
// 에뮬레이터 화면(adb screenrecord), 동승자 휴대폰 화면, Virtual Console 화면을 같은 시각 기준으로 녹화하고
// 장면별 자막 시각을 timeline.json 에 남긴다. 합치기는 compose.mjs 가 한다.
//
// 준비: Pleos Connect 에뮬레이터 실행, 앱 설치, adb forward tcp:8088 tcp:8088, companion 서버 실행
// 사용: node record.mjs ./out
import { chromium } from 'playwright-core';
import { spawn, execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const HOME = process.env.HOME;
const ADB = process.env.ADB || path.join(HOME, 'Library/Android/sdk/platform-tools/adb');
const VC = 'http://127.0.0.1:8088';
const SRV = 'http://127.0.0.1:8787';
const OUT = path.resolve(process.argv[2] || 'out');
const ONLY = (process.argv[3] || '').split(',').filter(Boolean);
const env = { ...process.env, ADB_LIBUSB: '1' };
const sleep = ms => new Promise(r => setTimeout(r, ms));
const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a);

fs.mkdirSync(path.join(OUT, 'emu'), { recursive: true });
fs.mkdirSync(path.join(OUT, 'phone'), { recursive: true });
fs.mkdirSync(path.join(OUT, 'vc'), { recursive: true });

function adb(...a) { return execFileSync(ADB, a, { env, encoding: 'utf8' }); }
function demo(cmd, ...more) { return adb('shell', 'am', 'start', '-n', 'app.gureuneun.guide/.MainActivity', '--es', 'demo', cmd, ...more); }
function tap(x, y) { adb('shell', 'input', 'tap', String(x), String(y)); }
async function vc(p) { try { const r = await fetch(VC + p, { method: 'POST' }); return r.ok; } catch { return false; } }

// Virtual Console 은 브라우저가 붙어 있어야 조작 API 가 열린다. 녹화 내내 하트비트를 보낸다
const hb = setInterval(() => fetch(VC + '/browser/session?client=gg-demo', { method: 'POST' }).catch(() => {}), 1000);

function groupCode() {
  const out = adb('logcat', '-d', '-s', 'Gureuneun:*');
  const m = [...out.matchAll(/그룹 서버 연결됨 \((\d{4})\)/g)].pop();
  return m ? m[1] : null;
}

async function waitFor(fn, ms = 20000, step = 500) {
  const end = Date.now() + ms;
  while (Date.now() < end) { const v = await fn(); if (v) return v; await sleep(step); }
  return null;
}

// ------------------------------------------------------------ 브라우저 (휴대폰, Virtual Console)
const browser = await chromium.launch({ channel: 'chrome', headless: true, args: ['--use-angle=metal', '--ignore-gpu-blocklist', '--autoplay-policy=no-user-gesture-required'] });
const phoneCtx = await browser.newContext({ viewport: { width: 412, height: 880 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true, recordVideo: { dir: path.join(OUT, 'phone'), size: { width: 412, height: 880 } } });
await phoneCtx.addInitScript(() => { localStorage.setItem('gg_me', '민지'); });
const T = { scenes: [], phoneStart: Date.now() };
const phone = await phoneCtx.newPage();
await phone.goto(SRV + '/', { waitUntil: 'domcontentloaded' });

let scene = null, vcCtx = null, vcPage = null, emuRec = null, driving = false;

function caption(title, body = '') {
  scene.captions.push({ at: Date.now() - scene.start, title, body });
  log('[자막]', title, body);
}

async function beginScene(name) {
  log('=== 장면', name);
  adb('logcat', '-c');
  vcCtx = await browser.newContext({ viewport: { width: 1280, height: 720 }, recordVideo: { dir: path.join(OUT, 'vc', name), size: { width: 960, height: 540 } } });
  const vcStart = Date.now();
  vcPage = await vcCtx.newPage();
  await vcPage.goto(VC + '/', { waitUntil: 'domcontentloaded' }).catch(() => {});
  const dev = '/sdcard/gg_' + name + '.mp4';
  const proc = spawn(ADB, ['shell', 'screenrecord', '--size', '1280x720', '--bit-rate', '8000000', '--time-limit', '178', dev], { env });
  emuRec = { proc, dev, startedAt: Date.now() + 700 };
  await sleep(1500);
  scene = { name, start: Date.now(), captions: [], vcStart, emuStart: emuRec.startedAt };
}

async function endScene() {
  driving = false;
  scene.end = Date.now();
  try { adb('shell', 'pkill', '-INT', 'screenrecord'); } catch {}
  await new Promise(r => { emuRec.proc.on('exit', r); setTimeout(r, 4000); });
  await sleep(1200);
  const emuFile = path.join(OUT, 'emu', scene.name + '.mp4');
  try { adb('pull', emuRec.dev, emuFile); } catch (e) { log('pull 실패', e.message); }
  const video = vcPage.video();
  await vcCtx.close();
  scene.vcFile = video ? await video.path() : null;
  scene.emuFile = emuFile;
  T.scenes.push(scene);
  fs.writeFileSync(path.join(OUT, 'timeline.json'), JSON.stringify(T, null, 2));
  log('=== 장면 끝', scene.name, Math.round((scene.end - scene.start) / 1000) + 's');
}

// 시연 주행 중에는 Virtual Console 가속 페달을 가끔 밟아 속도를 낸다 (계기판과 1인칭 화면이 움직임)
(async function pedalLoop() {
  while (true) {
    await sleep(5000);
    if (!driving || !vcPage) continue;
    try {
      await vcPage.locator('#acceleratorPad').dispatchEvent('pointerdown');
      await sleep(1100);
      await vcPage.locator('#acceleratorPad').dispatchEvent('pointerup');
    } catch {}
  }
})();

async function park() {
  driving = false;
  try { await vcPage.locator('#brakePad').dispatchEvent('pointerdown'); await sleep(2500); await vcPage.locator('#brakePad').dispatchEvent('pointerup'); } catch {}
  await vc('/vehicle/gear?gear=Park');
}

async function drive() {
  await vc('/vehicle/gear?gear=Drive');
  driving = true;
}

async function resetCar() {
  await vc('/vehicle/gear?gear=Park');
  await vc('/vehicle/turn-signal?mode=Off');
  await vc('/vehicle/body?front_wiper=Off');
  await vc('/vehicle/lights?feature=headlamp_mode&value=Off');
  await vc('/vehicle/body?window_fl=0&window_fr=0&window_rl=0&window_rr=0');
  await vc('/vehicle/body?ambient_color=%23ffffff&ambient_brightness=70');
  try { demo('end'); } catch {}
  await sleep(1500);
  try { demo('clear'); } catch {}
  await sleep(500);
  try { demo('home'); } catch {}
  await sleep(2500);
}

// 화면의 여행지 카드와 [여행 시작]을 눌러 출발. 시작 로그가 안 보이면 명령으로 다시 시작
async function startTrip(cardX, scenario, lang) {
  tap(cardX, 402); await sleep(800);
  tap(1652, 723);
  const ok = await waitFor(() => /여행 시작:/.test(adb('logcat', '-d', '-s', 'Gureuneun:*')), 4000);
  if (!ok) { log('탭으로 시작이 안 되어 명령으로 시작'); demo('start', '--es', 'scenario', scenario, '--es', 'lang', lang); }
}

async function phoneJoin(code) {
  await phone.goto(SRV + '/g/' + code, { waitUntil: 'domcontentloaded' });
  await sleep(2500);
}

async function phonePick(id) {
  await phone.click('#tPick');
  await sleep(2200);
  await phone.click('#picklist button[data-id="' + id + '"]');
  await sleep(1200);
  await phone.click('#sendPick');
}

const want = n => ONLY.length === 0 || ONLY.includes(n);
adb('shell', 'settings', 'put', 'system', 'show_touches', '1');

// ------------------------------------------------------------ 장면 1, 2: 경주 가족 여행
if (want('gyeongju')) {
  await resetCar();
  await beginScene('gyeongju1');
  caption('경주 가족 여행', '가족이 차 한 대로 경주에 가요. 출발 전에 [여행 시작] 하나만 누르면 돼요');
  await sleep(2500);
  await startTrip(1125, 'gyeongju', 'ko');
  await sleep(2500);
  caption('순정 내비게이션에 목적지가 바로 들어가요', '오늘 목적지 동궁과 월지와 경유지 두 곳 (NaviHelper)');
  await sleep(9000);
  const code = await waitFor(groupCode, 8000);
  caption('출발 전 좌석 점호', '모두 안전벨트를 매면 출발 안내가 나와요. 가족은 차 화면의 QR로 그룹 페이지에 들어와요');
  if (code) await phoneJoin(code);
  await sleep(4000);
  demo('go');
  await sleep(6000);
  await drive();
  caption('D단으로 바꾸면 화면은 소리 위주로', '달리는 동안에는 영상과 QR, 긴 글을 숨겨요 (CarUxRestrictions)');
  await sleep(9000);
  caption('경주에 들어서면 조명이 바뀌고 오프닝', '여행지 영역에 들어서는 순간 실내 조명 색이 바뀌고 이야기가 시작돼요');
  await sleep(22000);
  await endScene();

  await beginScene('gyeongju2');
  demo('jump');
  caption('명소에 가까워지면 먼저 알려 줘요', '"잠시 후 왼쪽에 대릉원이 보여요". 같은 순간 휴대폰에는 명소 영상이 떠요');
  await sleep(16000);
  demo('jump');
  await sleep(9000);
  caption('방향지시등을 켜면 잠시 멈춤', '차선을 바꾸는 동안은 이야기를 멈추고');
  await vc('/vehicle/turn-signal?mode=BlinkRight');
  await sleep(4500);
  await vc('/vehicle/turn-signal?mode=Off');
  caption('불이 꺼지면 이어서', '멈췄던 문장부터 다시 들려줘요');
  await sleep(7000);
  caption('와이퍼와 전조등에 맞춘 이야기', '비가 오면 비 오는 날 이야기, 전조등을 켜면 밤 이야기로 바꿔요');
  await vc('/vehicle/body?front_wiper=Low');
  await vc('/vehicle/lights?feature=headlamp_mode&value=LowBeam');
  await sleep(10000);
  demo('jump');
  caption('동궁과 월지, 밤 이야기', '휴대폰에는 불이 켜진 동궁과 월지 장면이 떠요');
  await sleep(16000);
  await park();
  caption('주차하면 차 화면에도 크게', '차가 서면 명소 장면과 QR을 크게 보여 줘요');
  await sleep(8000);
  await vc('/vehicle/body?front_wiper=Off');
  await vc('/vehicle/lights?feature=headlamp_mode&value=Off');
  await endScene();
}

// ------------------------------------------------------------ 장면 3: 제주, 렌터카 두 대
let jejuStart = 0, jejuCode = null;
if (want('jeju')) {
  await resetCar();
  await beginScene('jeju');
  jejuStart = Date.now();
  caption('제주, 친구들이 렌터카 두 대로', '두 차를 네 자리 그룹 번호 하나로 묶어요');
  await startTrip(1654, 'jeju', 'ko');
  await sleep(12000);
  jejuCode = await waitFor(groupCode, 8000);
  let car2 = null;
  if (jejuCode) {
    car2 = new WebSocket('ws://127.0.0.1:8787/ws?code=' + jejuCode + '&role=car&name=' + encodeURIComponent('2호차'));
    await phoneJoin(jejuCode);
  }
  caption('뒤차도 같은 그룹', '친구들은 각자 QR을 찍어 같은 그룹 페이지를 봐요');
  await sleep(3000);
  demo('go');
  await sleep(5000);
  await drive();
  await sleep(9000);
  caption('휴대폰 지도에서 바닷가 카페를 찍으면', '그룹 차 모두에 같은 장소가 떠요');
  await phonePick('aewol_cafe');
  await sleep(4000);
  caption('운전자는 확인만', '한 번 누르면 순정 내비게이션 목적지가 바뀌어요');
  demo('pick', '--es', 'mode', 'destination');
  await sleep(11000);
  caption('먼저 도착한 차가 주차장을 보내면', '뒤차도 바로 그 주차장으로 안내받아요');
  if (car2 && car2.readyState === 1) car2.send(JSON.stringify({ type: 'pick', id: 'hyeopjae_parking', name: '협재해수욕장 주차장', lat: 33.3946, lon: 126.2406, kind: 'parking', by: '2호차', fromCar: true }));
  await sleep(5000);
  demo('pick', '--es', 'mode', 'waypoint');
  await sleep(5000);
  demo('jump'); await sleep(7000);
  demo('jump'); await sleep(7000);
  demo('jump');
  caption('협재해수욕장', '바닷가 명소에 다가가면 그 바다 이야기가 시작돼요');
  await sleep(13000);
  await park();
  await sleep(4000);
  caption('바닷가에 세우면 창문을 열지 물어요', '확인을 누르면 창문이 한 뼘쯤 내려가 파도 소리를 들여요 (Vehicle 창문)');
  await sleep(4000);
  demo('window', '--ez', 'open', 'true');
  await sleep(9000);
  caption('여행이 끝나면 하루 기록 카드', '지나온 길, 명소를 지난 시각, 들은 이야기가 모여요');
  demo('end');
  await sleep(9000);
  if (car2) car2.close();
  await endScene();
}

// ------------------------------------------------------------ 장면 4: 휴대폰 기록 (사진, 빌트인 캠 하이라이트)
if (want('record') && jejuCode) {
  await beginScene('record');
  caption('휴대폰에 남는 오늘의 기록', '사진을 올리면 찍은 시각에 맞춰 지나온 길 위에 붙어요');
  await phone.click('#tRecord');
  await sleep(3000);
  // 시연용 사진: 3D 장면 컷을 여행 중 시각으로 바꿔 올림
  const web = path.resolve(path.dirname(new URL(import.meta.url).pathname), '../../companion/web/posters');
  const photos = ['horse_lighthouse_1.jpg', 'beach_1.jpg', 'hyeopjae_2.jpg'].map((f, i) => {
    const dst = path.join(OUT, 'photo_' + (i + 1) + '.jpg');
    fs.copyFileSync(path.join(web, f), dst);
    const t = new Date(jejuStart + (60 + i * 50) * 1000);
    fs.utimesSync(dst, t, t);
    return dst;
  });
  await phone.setInputFiles('#photoInput', photos);
  await sleep(7000);
  // 시연용 빌트인 캠 영상: 제주 장면 동안 녹화한 Virtual Console 1인칭 주행 화면
  const jeju = T.scenes.find(s => s.name === 'jeju');
  if (jeju && jeju.vcFile) {
    const d = new Date(jeju.vcStart);
    const p2 = n => String(n).padStart(2, '0');
    const name = 'BUILTIN_' + d.getFullYear() + p2(d.getMonth() + 1) + p2(d.getDate()) + '_' + p2(d.getHours()) + p2(d.getMinutes()) + p2(d.getSeconds()) + '_front.mp4';
    const cam = path.join(OUT, name);
    execFileSync('ffmpeg', ['-y', '-loglevel', 'error', '-i', jeju.vcFile, '-vf', 'crop=960:338:0:0,scale=1280:-2', '-c:v', 'libx264', '-preset', 'veryfast', '-crf', '24', '-an', cam]);
    caption('빌트인 캠 영상을 올리면', '명소를 지난 장면만 골라 짧은 여행 영상으로 만들어요');
    await phone.setInputFiles('#camInput', cam);
    await waitFor(async () => (await (await fetch(SRV + '/api/groups/' + jejuCode)).json()).highlight?.status === 'done', 60000, 1500);
    await sleep(2000);
    await phone.evaluate(() => { const v = document.getElementById('hl'); v.scrollIntoView({ block: 'center' }); v.muted = true; v.play(); });
    caption('여행 영상 완성', '두고두고 다시 꺼내 보는 여행이 돼요');
    await sleep(14000);
  }
  await endScene();
}

// ------------------------------------------------------------ 장면 5: 서울, 외국인 손님 영어 안내
if (want('seoul')) {
  await resetCar();
  await beginScene('seoul');
  caption('서울, 외국인 손님을 태운 택시', '안내 언어를 영어로 바꾸면 차도 휴대폰도 영어로 안내해요');
  await startTrip(2182, 'seoul', 'en');
  await sleep(12000);
  const code = await waitFor(groupCode, 8000);
  if (code) await phoneJoin(code);
  demo('go');
  await sleep(4000);
  await drive();
  await sleep(10000);
  demo('jump');
  caption('"On your right is the 63 Building"', '기사가 영어를 잘하지 못해도 차가 대신 안내해요');
  await sleep(22000);
  await park();
  await sleep(7000);
  await endScene();
}

const pv = phone.video();
await phoneCtx.close();
T.phoneFile = pv ? await pv.path() : null;
fs.writeFileSync(path.join(OUT, 'timeline.json'), JSON.stringify(T, null, 2));
adb('shell', 'settings', 'put', 'system', 'show_touches', '0');
clearInterval(hb);
await browser.close();
log('녹화 끝', OUT);
