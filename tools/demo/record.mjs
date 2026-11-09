// 구르는 가이드 시연 영상 녹화 도구
// 에뮬레이터 화면(scrcpy, 없으면 adb screenrecord), 차 안내 음성, 동승자 휴대폰 화면, Virtual Console 화면을
// 같은 시각 기준으로 녹화하고 장면별 자막 시각을 timeline.json 에 남긴다. 합치기는 compose.py 가 한다.
//
// 준비: Pleos Connect 에뮬레이터 실행, 앱 설치, adb forward tcp:8088 tcp:8088, companion 서버 실행
//       차 소리까지 담으려면 에뮬레이터를 소리 파일 출력으로 띄운다
//       QEMU_AUDIO_DRV=wav QEMU_WAV_PATH=/tmp/rgdev/emu_audio.wav emulator -avd <AVD> -audio wav
// 사용: node record.mjs ./out              모든 장면
//       node record.mjs ./out jeju,record  일부 장면만 다시 녹화 (같은 폴더의 다른 장면은 그대로 둠)
import { chromium } from 'playwright-core';
import { spawn, execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const HOME = process.env.HOME;
const ADB = process.env.ADB || path.join(HOME, 'Library/Android/sdk/platform-tools/adb');
const SCRCPY = process.env.SCRCPY || '/opt/homebrew/bin/scrcpy';
const USE_SCRCPY = process.env.REC !== 'screenrecord' && fs.existsSync(SCRCPY);
const VC = 'http://127.0.0.1:8088';
const SRV = 'http://127.0.0.1:8787';
const OUT = path.resolve(process.argv[2] || 'out');
const ONLY = (process.argv[3] || '').split(',').filter(Boolean);
const env = { ...process.env, ADB_LIBUSB: '1', ADB };
const sleep = ms => new Promise(r => setTimeout(r, ms));
const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a);

fs.mkdirSync(path.join(OUT, 'emu'), { recursive: true });
fs.mkdirSync(path.join(OUT, 'phone'), { recursive: true });
fs.mkdirSync(path.join(OUT, 'vc'), { recursive: true });
fs.mkdirSync(path.join(OUT, 'audio'), { recursive: true });
fs.mkdirSync(path.join(OUT, 'shots'), { recursive: true });

// ------------------------------------------------------------ 차 안내 음성
// 에뮬레이터는 소리가 날 때만 WAV 파일에 실시간으로 덧붙인다. 그래서 시각과 파일 크기를 짧은 간격으로 적어 두고
// 장면이 끝나면 그 구간의 소리를 원래 시각 자리에 맞춰 장면별 WAV 로 잘라 낸다
const WAV = process.env.EMU_WAV || '/tmp/rgdev/emu_audio.wav';
const WAV_HDR = 44, WAV_RATE = 44100, WAV_BPS = WAV_RATE * 4; // 16비트 스테레오
const amap = [];
let lastWavSize = -1;
const wavPoll = setInterval(() => {
  try { const s = fs.statSync(WAV).size; if (s !== lastWavSize) { amap.push([Date.now(), s]); lastWavSize = s; } } catch {}
}, 40);

function wavHeader(bytes) {
  const h = Buffer.alloc(44);
  h.write('RIFF', 0); h.writeUInt32LE(36 + bytes, 4); h.write('WAVE', 8);
  h.write('fmt ', 12); h.writeUInt32LE(16, 16); h.writeUInt16LE(1, 20); h.writeUInt16LE(2, 22);
  h.writeUInt32LE(WAV_RATE, 24); h.writeUInt32LE(WAV_BPS, 28); h.writeUInt16LE(4, 32); h.writeUInt16LE(16, 34);
  h.write('data', 36); h.writeUInt32LE(bytes, 40);
  return h;
}

function sceneAudio(S, E, file) {
  if (amap.length < 2 || !fs.existsSync(WAV)) return null;
  const total = Math.floor(((E - S) / 1000) * WAV_BPS / 4) * 4;
  const out = Buffer.alloc(total);
  const fd = fs.openSync(WAV, 'r');
  let expected = null, placed = 0;
  for (let k = 1; k < amap.length; k++) {
    const [t0, s0] = amap[k - 1], [t1, s1] = amap[k];
    const from = Math.max(s0, WAV_HDR), len = Math.floor((s1 - from) / 4) * 4;
    if (len <= 0) continue;
    const d = (len / WAV_BPS) * 1000;
    const observed = t1 - d;
    // 이어서 나오는 소리면 바로 뒤에 붙이고(끊김 방지), 시각이 많이 어긋나면 관측 시각으로 다시 맞춘다
    const at = expected !== null && Math.abs(expected - observed) <= 120 ? expected : observed;
    expected = at + d;
    if (at + d < S || at > E) continue;
    const chunk = Buffer.alloc(len);
    fs.readSync(fd, chunk, 0, len, from);
    let dst = Math.floor(((at - S) / 1000) * WAV_BPS / 4) * 4, src = 0;
    if (dst < 0) { src = -dst; dst = 0; }
    const n = Math.min(len - src, total - dst);
    if (n > 0) { chunk.copy(out, dst, src, src + n); placed += n; }
  }
  fs.closeSync(fd);
  fs.writeFileSync(file, Buffer.concat([wavHeader(total), out]));
  log('장면 소리', path.basename(file), Math.round(placed / WAV_BPS) + '초 분량');
  return file;
}

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
// 같은 폴더에 이전 녹화가 있으면 장면 목록을 이어 쓴다. 휴대폰 영상은 실행마다 한 파일이라 장면마다 기록한다
const TL = path.join(OUT, 'timeline.json');
const prev = fs.existsSync(TL) ? JSON.parse(fs.readFileSync(TL, 'utf8')) : { scenes: [] };
const T = { scenes: prev.scenes || [], phoneStart: Date.now() };
const runScenes = [];
const phone = await phoneCtx.newPage();
await phone.goto(SRV + '/', { waitUntil: 'domcontentloaded' });

let scene = null, vcCtx = null, vcPage = null, emuRec = null, driving = false, curCode = null;

function caption(title, body = '') {
  scene.captions.push({ at: Date.now() - scene.start, title, body });
  log('[자막]', title, body);
}

// 에뮬레이터 화면 녹화. scrcpy 는 안드로이드 쪽 소리(차 안내 음성, 효과음)까지 함께 담는다
function startEmuRec(name) {
  if (USE_SCRCPY) {
    const file = path.join(OUT, 'emu', name + '.mkv');
    const proc = spawn(SCRCPY, ['-s', 'emulator-5554', '--no-window', '--no-playback', '--max-size=1280', '--max-fps=30',
      '--video-bit-rate=8M', '--no-audio', '--record=' + file], { env });
    const rec = { kind: 'scrcpy', proc, file, startedAt: null, spawnedAt: Date.now(), log: '' };
    const onData = d => { const s = String(d); rec.log += s; if (!rec.startedAt && /Recording started/i.test(s)) rec.startedAt = Date.now(); };
    proc.stdout.on('data', onData); proc.stderr.on('data', onData);
    return rec;
  }
  const dev = '/sdcard/gg_' + name + '.mp4';
  const proc = spawn(ADB, ['shell', 'screenrecord', '--size', '1280x720', '--bit-rate', '8000000', '--time-limit', '178', dev], { env });
  return { kind: 'screenrecord', proc, dev, file: path.join(OUT, 'emu', name + '.mp4'), startedAt: Date.now() + 700 };
}

async function stopEmuRec(rec) {
  const exited = new Promise(r => { if (rec.proc.exitCode !== null) r(); else rec.proc.on('exit', r); });
  if (rec.kind === 'scrcpy') {
    rec.proc.kill('SIGINT');
    await Promise.race([exited, sleep(10000)]);
    if (rec.proc.exitCode === null) { log('scrcpy 가 끝나지 않아 강제 종료'); rec.proc.kill('SIGKILL'); }
    if (!rec.startedAt) log('scrcpy 로그:', rec.log.slice(-600));
    return;
  }
  try { adb('shell', 'pkill', '-INT', 'screenrecord'); } catch {}
  await Promise.race([exited, sleep(4000)]);
  await sleep(1200);
  try { adb('pull', rec.dev, rec.file); } catch (e) { log('pull 실패', e.message); }
}

async function beginScene(name) {
  log('=== 장면', name);
  adb('logcat', '-c');
  vcCtx = await browser.newContext({ viewport: { width: 1280, height: 720 }, recordVideo: { dir: path.join(OUT, 'vc', name), size: { width: 960, height: 540 } } });
  const vcStart = Date.now();
  vcPage = await vcCtx.newPage();
  await vcPage.goto(VC + '/', { waitUntil: 'domcontentloaded' }).catch(() => {});
  emuRec = startEmuRec(name);
  await waitFor(() => emuRec.startedAt, 8000, 200);
  await sleep(800);
  scene = { name, start: Date.now(), captions: [], vcStart, emuStart: emuRec.startedAt || emuRec.spawnedAt + 1500, emuKind: emuRec.kind, phoneStart: T.phoneStart };
}

async function endScene() {
  driving = false;
  scene.end = Date.now();
  await stopEmuRec(emuRec);
  const video = vcPage.video();
  await vcCtx.close();
  scene.vcFile = video ? await video.path() : null;
  scene.emuFile = emuRec.file;
  scene.audioFile = sceneAudio(scene.start, scene.end, path.join(OUT, 'audio', scene.name + '.wav'));
  // 이 장면 동안 차가 실제로 말한 문장 (서버의 발화 기록에서 시각으로 거름)
  scene.code = scene.code || curCode;
  if (scene.code) {
    try {
      const said = await (await fetch(SRV + '/api/groups/' + scene.code + '/said')).json();
      scene.said = said.filter(s => s.at >= scene.start - 2000 && s.at <= scene.end).map(s => ({ ...s, t: s.at - scene.start }));
    } catch {}
  }
  T.scenes = T.scenes.filter(s => s.name !== scene.name);
  T.scenes.push(scene);
  runScenes.push(scene);
  fs.writeFileSync(TL, JSON.stringify(T, null, 2));
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
  curCode = null;
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
  // [여행 시작] 버튼은 한국어 화면에서 657~789, 영어 화면에서 730~862 (기기 픽셀). 둘 다 들어가는 높이를 누름
  tap(1652, 760);
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

// 휴대폰에서 3D 모형을 손가락으로 끌어 돌려 봄
async function phoneOrbit() {
  try {
    await phone.click('#mOrbit');
    await sleep(900);
    const box = await phone.locator('#stage').boundingBox();
    if (!box) return;
    const y = box.y + box.height * 0.55;
    await phone.mouse.move(box.x + box.width * 0.8, y);
    await phone.mouse.down();
    for (let i = 1; i <= 30; i++) {
      await phone.mouse.move(box.x + box.width * (0.8 - 0.6 * i / 30), y - box.height * 0.08 * Math.sin(i / 30 * Math.PI));
      await sleep(45);
    }
    await phone.mouse.up();
  } catch (e) { log('3D 돌리기 실패', e.message); }
}

// ------------------------------------------------------------ 차가 말을 마칠 때까지 기다리기 (앱 로그 기준)
function appLog() { return adb('logcat', '-d', '-s', 'Gureuneun:*'); }
function spoken() {
  return appLog().split('\n').filter(l => l.includes('[Speak]')).map(l => l.replace(/^.*\[Speak\] \[\w+\] /, '').trim());
}
// 이 정규식에 맞는 문장을 차가 말하면 그 문장을 돌려줌
async function waitSpeak(re, ms = 30000) { return await waitFor(() => spoken().find(t => re.test(t)), ms, 600); }
function playerIdle() {
  const ls = appLog().split('\n').filter(l => /\[Speak\]|\[Player\]/.test(l));
  return /조용함/.test(ls[ls.length - 1] || '');
}
// 들려줄 이야기가 다 끝나 조용해질 때까지
async function untilQuiet(ms = 40000) { await sleep(1500); return await waitFor(() => playerIdle(), ms, 700); }
async function waitLog(re, ms = 30000) { return await waitFor(() => re.test(appLog()), ms, 700); }

// README 용 화면 캡처. 차 화면은 원본 해상도, 휴대폰은 2배 해상도
async function shot(name, which = 'both') {
  try {
    if (which !== 'phone') fs.writeFileSync(path.join(OUT, 'shots', name + '_car.png'), execFileSync(ADB, ['exec-out', 'screencap', '-p'], { env, maxBuffer: 64 * 1024 * 1024 }));
    if (which !== 'car') await phone.screenshot({ path: path.join(OUT, 'shots', name + '_phone.png') });
  } catch (e) { log('캡처 실패', name, e.message); }
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
  await sleep(8000);
  await shot('rollcall', 'car');
  const code = await waitFor(groupCode, 8000);
  curCode = code;
  caption('출발 전 좌석 점호', '모두 안전벨트를 매면 출발 안내가 나와요. 가족은 차 화면의 QR로 그룹 페이지에 들어와요');
  if (code) await phoneJoin(code);
  await sleep(3000);
  demo('go');
  await waitSpeak(/출발할게요/, 15000);
  await sleep(2500);
  await drive();
  caption('D단으로 바꾸면 화면은 소리 위주로', '달리는 동안에는 영상과 QR, 긴 글을 숨겨요 (CarUxRestrictions)');
  await waitLog(/영역 진입:/, 40000);
  caption('경주에 들어서면 조명이 바뀌고 오프닝', '여행지에 들어서는 순간 실내 조명 색이 바뀌고 이야기가 시작돼요 (Vehicle 실내 조명)');
  await sleep(6000);
  await shot('driving');
  await sleep(10000);
  await endScene();

  await beginScene('gyeongju2');
  demo('jump');
  const ann = await waitSpeak(/대릉원이 보여요/, 25000);
  caption('어느 쪽에 보일지 먼저 알려 줘요', (ann ? '"' + ann + '" ' : '') + '같은 순간 휴대폰에는 명소 영상이 떠요');
  await sleep(5000);
  await shot('story', 'phone');
  await untilQuiet(40000);
  demo('jump');
  await waitSpeak(/^첨성대예요/, 25000);
  await sleep(1600);
  caption('방향지시등을 켜면 잠시 멈춤', '차선을 바꾸는 동안은 이야기를 멈춰요 (Vehicle 방향지시등)');
  await vc('/vehicle/turn-signal?mode=BlinkRight');
  await sleep(4000);
  await vc('/vehicle/turn-signal?mode=Off');
  caption('불이 꺼지면 이어서', '멈췄던 문장부터 다시 들려줘요');
  await untilQuiet(40000);
  caption('비가 오면 비 오는 날 이야기로', '와이퍼가 움직이면 다음 이야기를 빗소리에 맞춰 바꿔요 (Vehicle 와이퍼)');
  await vc('/vehicle/body?front_wiper=Low');
  await waitSpeak(/비가 오네요/, 15000);
  await untilQuiet(20000);
  await vc('/vehicle/body?front_wiper=Off');
  caption('어두워지면 밤 이야기로', '전조등이 켜지면 밤 풍경 이야기로 바꿔요 (Vehicle 전조등)');
  await vc('/vehicle/lights?feature=headlamp_mode&value=LowBeam');
  await waitSpeak(/전조등이 켜졌네요/, 15000);
  await untilQuiet(20000);
  demo('jump');
  await waitSpeak(/동궁과 월지가 보여요/, 25000);
  caption('동궁과 월지, 밤 이야기', '휴대폰에도 불이 켜진 동궁과 월지 장면이 떠요');
  await untilQuiet(45000);
  await park();
  caption('주차하면 차 화면에도 크게', '차가 서면 명소 장면과 QR을 크게 보여 줘요');
  await sleep(5000);
  await shot('parked');
  await sleep(3000);
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
  await sleep(10000);
  jejuCode = await waitFor(groupCode, 8000);
  curCode = jejuCode;
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
  await waitLog(/영역 진입:/, 40000);
  await sleep(6000);
  caption('휴대폰 지도에서 바닷가 카페를 찍으면', '그룹 차 모두에 같은 장소가 떠요');
  await phonePick('aewol_cafe');
  await sleep(2500);
  await shot('pick');
  await sleep(1500);
  caption('운전자는 확인만', '한 번 누르면 순정 내비게이션 목적지가 바뀌어요 (NaviHelper)');
  demo('pick', '--es', 'mode', 'destination');
  await sleep(9000);
  caption('먼저 도착한 차가 주차장을 보내면', '뒤차도 바로 그 주차장으로 안내받아요');
  if (car2 && car2.readyState === 1) car2.send(JSON.stringify({ type: 'pick', id: 'hyeopjae_parking', name: '협재해수욕장 주차장', lat: 33.3946, lon: 126.2406, kind: 'parking', by: '2호차', fromCar: true }));
  await sleep(5000);
  demo('pick', '--es', 'mode', 'waypoint');
  await sleep(1500);
  await phone.click('#tNow').catch(() => {});
  await sleep(4500);
  demo('jump', '--es', 'to', 'iho');
  await waitSpeak(/말 등대가 보여요/, 25000);
  caption('이호테우 해변의 말 등대', '휴대폰에는 등대 영상과 손으로 돌려 보는 3D 모형이 떠요');
  await sleep(3000);
  await phoneOrbit();
  await sleep(1200);
  await phoneOrbit();
  await untilQuiet(40000);
  await phone.click('#mVideo').catch(() => {});
  demo('jump', '--es', 'to', 'hyeopjae');
  await waitSpeak(/협재해수욕장이 보여요/, 25000);
  caption('협재해수욕장', '오늘 바닷길의 끝이에요. 그 바다 이야기가 시작돼요');
  await untilQuiet(45000);
  await park();
  await sleep(3000);
  caption('바닷가에 세우면 창문을 열지 물어요', '확인을 누르면 창문이 한 뼘쯤 내려가고 파도 소리가 들려요 (Vehicle 창문)');
  await sleep(2000);
  await shot('window', 'car');
  await sleep(2000);
  demo('window', '--ez', 'open', 'true');
  await sleep(9000);
  caption('여행이 끝나면 하루 기록 카드', '지나온 길, 명소를 지난 시각, 들은 이야기가 모여요');
  demo('end');
  await sleep(5000);
  await shot('record', 'car');
  await sleep(4000);
  if (car2) car2.close();
  await endScene();
}

// ------------------------------------------------------------ 장면 4: 휴대폰 기록 (사진, 빌트인 캠 하이라이트)
// 제주 장면을 앞선 실행에서 녹화했다면 그 그룹으로 휴대폰을 다시 들여보낸다
if (want('record') && !jejuCode) {
  const jejuPrev = T.scenes.find(s => s.name === 'jeju');
  if (jejuPrev && jejuPrev.code) { jejuCode = jejuPrev.code; jejuStart = jejuPrev.start; await phoneJoin(jejuCode); }
}
if (want('record') && jejuCode) {
  curCode = jejuCode;
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
    await sleep(6000);
    await shot('phone_record', 'phone');
    await sleep(8000);
  }
  await endScene();
}

// ------------------------------------------------------------ 장면 5: 서울, 외국인 손님 영어 안내
if (want('seoul')) {
  await resetCar();
  await beginScene('seoul');
  caption('서울, 외국인 손님을 태운 택시', '안내 언어를 영어로 바꾸면 차도 휴대폰도 영어로 안내해요');
  await startTrip(2182, 'seoul', 'en');
  await sleep(10000);
  const code = await waitFor(groupCode, 8000);
  curCode = code;
  if (code) await phoneJoin(code);
  demo('go');
  await sleep(4000);
  await drive();
  await waitLog(/영역 진입:/, 40000);
  await sleep(9000);
  demo('jump', '--es', 'to', 'building63');
  const ann = await waitSpeak(/is the 63 Building/, 25000);
  caption(ann ? '"' + ann.replace(/\.$/, '') + '"' : '63빌딩 영어 안내', '기사가 영어를 잘하지 못해도 차가 대신 안내해요');
  await sleep(4000);
  await shot('seoul');
  await untilQuiet(40000);
  await park();
  await sleep(6000);
  await endScene();
}

const pv = phone.video();
await phoneCtx.close();
const phoneFile = pv ? await pv.path() : null;
for (const s of runScenes) s.phoneFile = phoneFile;
T.phoneFile = phoneFile;
fs.writeFileSync(TL, JSON.stringify(T, null, 2));
adb('shell', 'settings', 'put', 'system', 'show_touches', '0');
clearInterval(hb);
clearInterval(wavPoll);
await browser.close();
log('녹화 끝', OUT);
