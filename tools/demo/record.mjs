// 구르는 가이드 시연 영상 녹화 도구
// 에뮬레이터의 차 화면(scrcpy)과 차 소리, 동승자 휴대폰 화면, Virtual Console 화면을 같은 시각 기준으로 담고
// 장면마다 언제 무슨 일이 있었는지(marks)와 앱 로그를 timeline.json 에 남긴다. 편집은 compose.py 가 한다.
//
// 준비: Pleos Connect 에뮬레이터 실행(넓은 창), 앱 설치, adb forward tcp:8088 tcp:8088,
//       companion 폴더에서 npm run demo, 앱 [점검] 화면에서 [시연 명령 받기] 켜기
//       차 소리를 담으려면 에뮬레이터를 소리 파일 출력으로 띄움
//       QEMU_AUDIO_DRV=wav QEMU_WAV_PATH=/tmp/rgdev/emu_audio.wav emulator -avd <AVD> -audio wav
// 사용: node record.mjs ./out                 모든 장면
//       node record.mjs ./out jeju,seoul      일부 장면만 다시 (같은 폴더의 다른 장면은 그대로 둠)
import { chromium } from 'playwright-core';
import { spawn, execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const HOME = process.env.HOME;
const ADB = process.env.ADB || path.join(HOME, 'Library/Android/sdk/platform-tools/adb');
const SCRCPY = process.env.SCRCPY || 'scrcpy';
const VC = 'http://127.0.0.1:8088';
const SRV = 'http://127.0.0.1:8787';
const REPO = path.resolve(path.dirname(new URL(import.meta.url).pathname), '../..');
// 질문 음성. 앱에 넣어 둔 것(assets/ask 의 PCM)과 같은 내용의 WAV 를 영상 소리에 겹침
const ASK_DIR = process.env.ASK_DIR || path.join(REPO, 'tools/demo/ask');
const SCN = Object.fromEntries(['gyeongju', 'jeju', 'seoul'].map(id => [id, JSON.parse(fs.readFileSync(path.join(REPO, 'app/src/main/assets/scenarios', id + '.json'), 'utf8'))]));
const OUT = path.resolve(process.argv[2] || 'out');
const ONLY = (process.argv[3] || '').split(',').filter(Boolean);
const env = { ...process.env, ADB };
const sleep = ms => new Promise(r => setTimeout(r, ms));
const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a);
for (const d of ['emu', 'phone', 'vc', 'audio', 'shots']) fs.mkdirSync(path.join(OUT, d), { recursive: true });

// ------------------------------------------------------------ 차 소리
// 에뮬레이터는 소리가 날 때만 WAV 파일에 덧붙인다. 파일이 커진 시각을 적어 두었다가
// 장면이 끝나면 그 구간의 소리를 원래 시각 자리에 놓아 장면별 WAV 로 만든다
const WAV = process.env.EMU_WAV || '/tmp/rgdev/emu_audio.wav';
const WAV_HDR = 44, WAV_RATE = 44100, WAV_BPS = WAV_RATE * 4;
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
    const [, s0] = amap[k - 1], [t1, s1] = amap[k];
    const from = Math.max(s0, WAV_HDR), len = Math.floor((s1 - from) / 4) * 4;
    if (len <= 0) continue;
    const d = (len / WAV_BPS) * 1000;
    const observed = t1 - d;
    // 이어서 나오는 소리면 바로 뒤에 붙이고, 시각이 많이 어긋나면 관측 시각으로 다시 맞춤
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

// ------------------------------------------------------------ 에뮬레이터와 Virtual Console
function adb(...a) { return execFileSync(ADB, a, { env, encoding: 'utf8', maxBuffer: 64 << 20 }); }
const q1 = a => /[\s?']/.test(a) ? "'" + a.replace(/'/g, "'\\''") + "'" : a;
function demo(cmd, ...more) { try { adb('shell', 'am', 'start', '-n', 'app.gureuneun.guide/.MainActivity', '--es', 'demo', cmd, ...more.map(q1)); } catch (e) { log('명령 실패', cmd); } }
async function vc(p) { try { return (await fetch(VC + p, { method: 'POST' })).ok; } catch { return false; } }
// Virtual Console 은 브라우저가 붙어 있어야 조작을 받음
const hb = setInterval(() => fetch(VC + '/browser/session?client=gg-demo', { method: 'POST' }).catch(() => {}), 1000);

async function waitFor(fn, ms = 20000, step = 400) {
  const end = Date.now() + ms;
  while (Date.now() < end) { const v = await fn(); if (v) return v; await sleep(step); }
  return null;
}

// 화면에 보이는 글자와 그 자리. 읽는 데 2~3초가 걸려서 장면을 찍기 전에 한 번 읽어 두고 씀 (화면이 멈춰 있을 때만 읽힘)
function screen() {
  const found = [];
  try {
    adb('shell', 'uiautomator', 'dump', '/sdcard/gg-ui.xml');
    const xml = adb('shell', 'cat', '/sdcard/gg-ui.xml');
    for (const m of xml.matchAll(/<node [^>]*?text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/g)) {
      if (m[1]) found.push({ text: m[1], x: (Number(m[2]) + Number(m[4])) >> 1, y: (Number(m[3]) + Number(m[5])) >> 1 });
    }
  } catch {}
  return found;
}
function tap(at) { if (at) adb('shell', 'input', 'tap', String(at.x), String(at.y)); return !!at; }
function tapText(re, list = screen()) {
  const hit = list.find(n => re.test(n.text));
  if (!hit) log('화면에서 찾지 못함', String(re));
  return tap(hit);
}

// 앱 로그: 시각이 붙은 줄로 읽음 ("11-12 05:19:05.980 ... Gureuneun: [Speak] [ko] ...")
function logTime(line) {
  const m = /^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d{3})/.exec(line);
  return m ? new Date(new Date().getFullYear(), +m[1] - 1, +m[2], +m[3], +m[4], +m[5], +m[6]).getTime() : null;
}
function appLog() { return adb('logcat', '-d', '-s', 'Gureuneun:*').split('\n').filter(l => l.includes('Gureuneun:')); }
const body = l => l.replace(/^.*Gureuneun: /, '');
// since 뒤에 re 에 맞는 줄이 나오면 그 시각을 돌려줌
async function waitLog(re, ms = 30000, since = 0) {
  return await waitFor(() => { for (const l of appLog()) { const t = logTime(l); if (t && t >= since && re.test(body(l))) return t; } return null; }, ms, 350);
}
const waitSpeak = (re, ms = 30000, since = 0) => waitLog(new RegExp('^\\[Speak\\] \\[\\w+\\] .*(?:' + re.source + ')', re.flags), ms, since);
async function untilQuiet(ms = 40000) {
  await sleep(1200);
  return await waitFor(() => { const ls = appLog().filter(l => /\[Speak\]|\[Player\]/.test(l)); return /조용함/.test(ls[ls.length - 1] || ''); }, ms, 500);
}
function groupCode() {
  const m = [...appLog().join('\n').matchAll(/그룹 서버 연결됨 \((\d{4})\)/g)].pop();
  return m ? m[1] : null;
}

// ------------------------------------------------------------ 브라우저: 동승자 휴대폰과 Virtual Console
const browser = await chromium.launch({ channel: 'chrome', headless: true, args: ['--use-angle=metal', '--ignore-gpu-blocklist', '--autoplay-policy=no-user-gesture-required'] });
const phoneCtx = await browser.newContext({ viewport: { width: 412, height: 880 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true });
const phone = await phoneCtx.newPage();
await phone.goto(SRV + '/', { waitUntil: 'domcontentloaded' });

const TL = path.join(OUT, 'timeline.json');
const T = fs.existsSync(TL) ? JSON.parse(fs.readFileSync(TL, 'utf8')) : { scenes: [] };
let scene = null, vcCtx = null, vcPage = null, emuRec = null, grabbing = false, moving = false;

// 휴대폰 화면은 2배 해상도 캡처를 이어 붙여 씀 (브라우저 녹화는 1배라 글자가 흐림)
async function grabPhone(dir) {
  fs.mkdirSync(dir, { recursive: true });
  const frames = [];
  grabbing = true;
  (async () => {
    let i = 0;
    while (grabbing) {
      const t = Date.now();
      if (moving) { await sleep(60); continue; }
      try {
        const buf = await phone.screenshot({ type: 'jpeg', quality: 88, timeout: 2000 });
        const name = String(i++).padStart(5, '0') + '.jpg';
        fs.writeFileSync(path.join(dir, name), buf);
        frames.push([t, name]);
      } catch {}
      await sleep(Math.max(10, 90 - (Date.now() - t)));
    }
  })();
  return frames;
}

function startEmuRec(name) {
  const file = path.join(OUT, 'emu', name + '.mkv');
  const proc = spawn(SCRCPY, ['-s', 'emulator-5554', '--no-window', '--no-playback', '--max-size=1920', '--max-fps=30', '--video-bit-rate=12M', '--no-audio', '--record=' + file], { env });
  const rec = { proc, file, startedAt: null, spawnedAt: Date.now(), log: '' };
  const onData = d => { const s = String(d); rec.log += s; if (!rec.startedAt && /Recording started/i.test(s)) rec.startedAt = Date.now(); };
  proc.stdout.on('data', onData); proc.stderr.on('data', onData);
  return rec;
}

async function stopEmuRec(rec) {
  const exited = new Promise(r => { if (rec.proc.exitCode !== null) r(); else rec.proc.on('exit', r); });
  rec.proc.kill('SIGINT');
  await Promise.race([exited, sleep(10000)]);
  if (rec.proc.exitCode === null) rec.proc.kill('SIGKILL');
  if (!rec.startedAt) log('scrcpy 로그:', rec.log.slice(-400));
}

async function beginScene(name) {
  log('=== 장면', name);
  adb('logcat', '-c');
  vcCtx = await browser.newContext({ viewport: { width: 1280, height: 720 }, recordVideo: { dir: path.join(OUT, 'vc', name), size: { width: 1280, height: 720 } } });
  const vcStart = Date.now();
  vcPage = await vcCtx.newPage();
  await vcPage.goto(VC + '/', { waitUntil: 'domcontentloaded' }).catch(() => {});
  emuRec = startEmuRec(name);
  await waitFor(() => emuRec.startedAt, 8000, 150);
  await sleep(600);
  const dir = path.join(OUT, 'phone', name);
  for (const f of fs.existsSync(dir) ? fs.readdirSync(dir) : []) fs.unlinkSync(path.join(dir, f));
  scene = { name, start: Date.now(), marks: {}, overlays: [], vcStart, emuStart: emuRec.startedAt || emuRec.spawnedAt + 1200, phoneDir: dir };
  scene.phoneFrames = await grabPhone(dir);
}

// 편집할 때 쓸 시각 표시 (장면 시작부터 초)
function mark(name, at = Date.now()) {
  scene.marks[name] = +((at - scene.start) / 1000).toFixed(2);
  log('·', name, scene.marks[name]);
}

async function endScene() {
  scene.end = Date.now();
  grabbing = false;
  await sleep(200);
  await stopEmuRec(emuRec);
  const video = vcPage.video();
  await vcCtx.close();
  scene.vcFile = video ? await video.path() : null;
  scene.emuFile = emuRec.file;
  scene.audioFile = sceneAudio(scene.start, scene.end, path.join(OUT, 'audio', scene.name + '.wav'));
  scene.phoneFrames = scene.phoneFrames.map(([t, f]) => [+((t - scene.start) / 1000).toFixed(3), f]);
  // 이 장면의 앱 로그 (차가 말한 문장과 차 신호를 편집할 때 봄)
  scene.log = appLog().map(l => ({ t: logTime(l), s: body(l) })).filter(x => x.t && x.t >= scene.start - 500 && x.t <= scene.end)
    .map(x => ({ t: +((x.t - scene.start) / 1000).toFixed(2), s: x.s })).filter(x => !/실내 조명 색|Media\]|Audio\]/.test(x.s) || /실내 조명 색 FFC94D/.test(x.s));
  T.scenes = T.scenes.filter(s => s.name !== scene.name);
  T.scenes.push(scene);
  fs.writeFileSync(TL, JSON.stringify(T, null, 1));
  log('=== 장면 끝', scene.name, Math.round((scene.end - scene.start) / 1000) + 's');
}

async function resetCar() {
  await vc('/vehicle/gear?gear=Park');
  await vc('/vehicle/turn-signal?mode=Off');
  await vc('/vehicle/body?front_wiper=Off');
  await vc('/vehicle/lights?feature=headlamp_mode&value=Off');
  await vc('/vehicle/body?ambient_color=%23ffffff&ambient_brightness=70');
  demo('end'); await sleep(1500);
  demo('clear'); await sleep(500);
  demo('home'); await sleep(2500);
}

// Virtual Console 화면의 기어 단추를 누름 (녹화에 누르는 모습이 담김)
async function gear(g) {
  const title = { P: 'Park', D: 'Drive' }[g];
  try { await vcPage.click('#gearButtons button[title="' + title + '"]', { timeout: 3000 }); }
  catch { await vc('/vehicle/gear?gear=' + title); }
}

// Virtual Console 의 Emulation Controls 에서 방향지시등 카드를 열어 둠
async function openTurnSignal() {
  await vcPage.click('#apiBrowserOpen');
  await sleep(500);
  await vcPage.fill('#apiSearch', 'turn signal');
  await sleep(600);
}
async function turnSignal(label) {
  try { await vcPage.locator('button.api-action', { hasText: new RegExp('^' + label + '$') }).first().click({ timeout: 3000 }); }
  catch { await vc('/vehicle/turn-signal?mode=' + (label === 'Off' ? 'Off' : 'BlinkRight')); }
}

// 처음 화면에서 여행지 카드와 [여행 시작]을 눌러 시작. home 은 장면을 찍기 전에 읽어 둔 처음 화면
async function startTrip(scenario, lang, home) {
  const sc = SCN[scenario];
  if (lang === 'en') { tapText(/^English$/, home); mark('lang'); await sleep(900); }
  tapText(new RegExp('^' + sc.title.ko + '$'), home); mark('card_tap');
  await sleep(1000);
  tapText(/^여행 시작$/, home); mark('start_tap');
  const ok = await waitLog(/^\[Trip\] 여행 시작:/, 5000, scene.start);
  if (!ok) { log('눌러서 시작이 안 되어 명령으로 시작'); demo('start', '--es', 'scenario', scenario, '--es', 'lang', lang); }
  mark('started', ok || Date.now());
  // 순정 내비가 경로를 보여 주고 안내를 시작하면 1.5초 뒤 우리 화면(QR)으로 돌아옴
  const route = await waitLog(/^\[Navi\] 경로 안내 시작/, 15000, scene.start);
  mark('route', route || Date.now());
  await sleep(Math.max(0, (route || Date.now()) + 2600 - Date.now()));
  mark('qr');
}

async function phoneJoin(code, name, lang) {
  // 페이지를 옮기는 동안 캡처를 걸면 한참 멈춰 있을 때가 있어 잠깐 쉼
  moving = true;
  await phone.evaluate(([n, l]) => { localStorage.setItem('gg_me', n); if (l) localStorage.setItem('gg_lang', l); else localStorage.removeItem('gg_lang'); }, [name, lang || '']);
  await phone.goto(SRV + '/g/' + code + '?car=1', { waitUntil: 'load' });
  await sleep(300);
  moving = false;
}

async function phonePick(id) {
  await phone.click('#tPick'); mark('pick_tab');
  await sleep(1800);
  await phone.click('#picklist button[data-id="' + id + '"]'); mark('pick_select');
  await sleep(1300);
  await phone.click('#sendPick'); mark('pick_send');
}

// 휴대폰에 온 퀴즈의 정답을 누름. 차가 보기 순서를 섞어 보내므로 글자로 찾음
async function phoneAnswer(scId, quizId, tag) {
  const q = SCN[scId].quiz[quizId];
  const want = [q.choices.ko[q.answer], q.choices.en[q.answer]];
  const ready = await waitFor(async () => (await phone.locator('#quizCard:not(.hidden) #quizChoices button:not([disabled])').count()) > 0, 40000, 300);
  if (!ready) { log('퀴즈가 휴대폰에 안 뜸', quizId); return; }
  mark(tag + '_shown');
  await sleep(2200);
  const texts = await phone.locator('#quizChoices button').allTextContents();
  const idx = Math.max(0, texts.findIndex(t => want.some(w => t.includes(w))));
  await phone.locator('#quizChoices button').nth(idx).click();
  mark(tag + '_tap');
}

// 말로 묻기: 앱에 넣어 둔 질문 음성을 음성 인식 SDK 에 넣고, 인식기가 듣기 시작한 때에 같은 목소리를 영상 소리에 겹침
async function ask(name, tag) {
  const t0 = Date.now();
  demo('ask', '--es', 'file', name + '.pcm');
  const fed = await waitLog(/음성 넣기 시작/, 12000, t0 - 500);
  mark(tag, fed || t0 + 1600);
  scene.overlays.push({ at: +(((fed || t0 + 1600) - scene.start) / 1000).toFixed(2), file: path.join(ASK_DIR, name + '.wav'), gain: 1.5 });
  const heard = await waitLog(/^\[STT\] 인식: /, 15000, t0);
  if (heard) mark(tag + '_heard', heard); else log('질문을 알아듣지 못함', name);
  return heard;
}

// README 와 기획서에 쓸 화면 캡처 (차 화면 원본 해상도, 휴대폰 2배)
async function shot(name, which = 'both') {
  try {
    if (which !== 'phone') fs.writeFileSync(path.join(OUT, 'shots', name + '_car.png'), execFileSync(ADB, ['exec-out', 'screencap', '-p'], { env, maxBuffer: 64 << 20 }));
    if (which !== 'car') await phone.screenshot({ path: path.join(OUT, 'shots', name + '_phone.png') });
  } catch (e) { log('캡처 실패', name, e.message); }
}

const want = n => ONLY.length === 0 || ONLY.includes(n);
adb('shell', 'settings', 'put', 'system', 'show_touches', '1');

// ------------------------------------------------------------ 경주: 가족이 차 한 대로
if (want('gyeongju')) {
  await resetCar();
  const home = screen();
  // 방향지시등 장면만 다시 찍을 때: STOP_AFTER=signal SCENE_AS=signal node record.mjs ./out gyeongju
  await beginScene(process.env.SCENE_AS || 'gyeongju');
  await shot('home', 'car');
  await sleep(2000);
  await startTrip('gyeongju', 'ko', home);
  const code = await waitFor(groupCode, 8000);
  if (code) await phoneJoin(code, '하준');
  mark('phone_join');
  await sleep(2500);
  await shot('before');
  await sleep(1500);
  // 기어를 D 로 바꾸면 출발
  await gear('D'); mark('gear_d');
  mark('depart', await waitSpeak(/출발할게요/, 15000, scene.start) || Date.now());
  await waitLog(/영역 진입: 경주/, 40000, scene.start);
  mark('city');
  await untilQuiet(45000);
  // 대릉원 앞: 코스에 없는 천마총을 말로 물어봄
  demo('jump');
  let t = Date.now();
  await ask('q_right', 'ask');
  mark('answer', await waitSpeak(/보이는 곳은/, 15000, t) || Date.now());
  await sleep(2500); await shot('ask');
  mark('announce', await waitSpeak(/대릉원이 보여요/, 40000, t) || Date.now());
  await sleep(1500); await shot('announce');
  mark('story', await waitSpeak(/대릉원이에요/, 20000, t) || Date.now());
  await sleep(3500); await shot('story');
  mark('kid', await waitSpeak(/뒷자리 친구들/, 60000, t) || Date.now());
  await phoneAnswer('gyeongju', 'daereungwon', 'quiz');
  mark('cheer', await waitSpeak(/정답이에요/, 15000, t) || Date.now());
  await sleep(1200); await shot('quiz');
  await untilQuiet(30000);
  // 첨성대: 이야기 도중에 방향지시등
  await openTurnSignal();
  t = Date.now();
  demo('jump');
  mark('announce2', await waitSpeak(/첨성대가 보여요/, 40000, t) || Date.now());
  mark('story2', await waitSpeak(/첨성대예요/, 20000, t) || Date.now());
  // 길 안내 때문에 쉬는 중이 아니고 문장을 읽기 시작한 지 1~2초 됐을 때 방향지시등을 켬 (멈추는 모습이 또렷하게)
  await waitFor(() => {
    const last = appLog().filter(l => /\[Speak\]|길 안내 \d+m 앞|길 안내 지남/.test(l)).pop();
    const age = last ? Date.now() - logTime(last) : 0;
    return last && /\[Speak\]/.test(last) && !/첨성대예요\.$/.test(last) && age > 1100 && age < 2200;
  }, 25000, 150);
  await turnSignal('Blink right'); mark('turn_on');
  await sleep(4000);
  await turnSignal('Off'); mark('turn_off');
  mark('resume', await waitLog(/방향지시등 꺼짐/, 8000, t) || Date.now());
  await sleep(1500);
  await vcPage.click('#apiBrowserClose').catch(() => {});
  if (process.env.STOP_AFTER === 'signal') { await sleep(7000); await endScene(); }
  else {
  await phoneAnswer('gyeongju', 'cheomseongdae', 'quiz2');
  await untilQuiet(40000);
  // 동궁과 월지: 마지막 목적지
  t = Date.now();
  demo('jump');
  mark('announce3', await waitSpeak(/동궁과 월지가 보여요/, 40000, t) || Date.now());
  await phoneAnswer('gyeongju', 'donggung', 'quiz3');
  mark('allstars', await waitSpeak(/다 맞혔어요/, 20000, t) || Date.now());
  await untilQuiet(40000);
  await sleep(800);
  t = Date.now();
  await gear('P'); mark('park');
  await sleep(2500); await shot('parked');
  mark('end_ask', await waitSpeak(/여행을 끝낼까요/, 30000, t) || Date.now());
  await sleep(3200); await shot('end_ask', 'car');
  // 차가 서 있을 때는 사진이 천천히 움직여 화면 글자를 읽지 못함. 넓은 창 기준 자리를 누르고, 안 되면 명령으로
  tap({ x: 1516, y: 707 }); mark('end_tap');
  let fin = await waitSpeak(/여기까지예요/, 6000, t);
  if (!fin) { demo('end'); fin = await waitSpeak(/여기까지예요/, 8000, t); }
  mark('finale', fin || Date.now());
  await sleep(6000); await shot('record', 'car');
  tap({ x: 2207, y: 1103 }); mark('share');
  await sleep(2500);
  await phone.click('#tRecord').catch(() => {}); mark('phone_record');
  await sleep(1500);
  if (!(await phone.locator('#saveCard').isVisible().catch(() => false))) { log('보내기가 안 눌려 명령으로 보냄'); demo('share'); await sleep(3000); }
  await sleep(2500); await shot('record_sent');
  await phone.click('#saveCard').catch(() => {}); mark('postcard');
  await sleep(3500);
  await endScene();
  }
}

// ------------------------------------------------------------ 제주: 친구들이 두 대로
if (want('jeju')) {
  await resetCar();
  const home = screen();
  await beginScene('jeju');
  await sleep(1500);
  await startTrip('jeju', 'ko', home);
  const code = await waitFor(groupCode, 8000);
  let car2 = null;
  if (code) {
    const join = await (await fetch(SRV + '/api/groups/' + code + '/join', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ carName: '뒤차' }) })).json();
    car2 = new WebSocket(SRV.replace('http', 'ws') + '/ws?code=' + code + '&role=car&key=' + encodeURIComponent(join.carKey));
    await phoneJoin(code, '민지');
  }
  mark('phone_join');
  await sleep(3000);
  await gear('D'); mark('gear_d');
  await waitLog(/영역 진입: 제주/, 40000, scene.start);
  await sleep(5000);
  // 뒷자리에서 바닷가 카페를 고르면 차가 묻고, 운전자는 "응"
  let t = Date.now();
  await phonePick('aewol_cafe');
  mark('pick_say', await waitSpeak(/골랐어요/, 40000, t) || Date.now());
  mark('pick_ask', await waitSpeak(/들를까요/, 15000, t) || Date.now());
  await waitLog(/듣기 요청/, 8000, t);
  await sleep(700); await shot('pick');
  await ask('a_yes', 'yes');
  const added = await waitLog(/경유지 다시 묶음/, 10000, t);
  mark('added', added || Date.now());
  // 순정 내비가 카페를 넣은 경로를 보여 주고, 안내를 시작하면 우리 화면으로 돌아옴
  const route = await waitLog(/^\[Navi\] 경로 안내 시작/, 15000, added || t);
  mark('route2', route || Date.now());
  await shot('navi_stop', 'car');
  await sleep(2600);
  mark('back');
  await sleep(3000); await shot('pick_done');
  if (car2) car2.close();
  await gear('P');
  await endScene();
}

// ------------------------------------------------------------ 서울: 외국인 손님을 태운 택시
if (want('seoul')) {
  await resetCar();
  const home = screen();
  await beginScene('seoul');
  await sleep(1500);
  await startTrip('seoul', 'en', home);
  const code = await waitFor(groupCode, 8000);
  if (code) await phoneJoin(code, 'Alex', 'en');
  mark('phone_join');
  await sleep(2500);
  await gear('D'); mark('gear_d');
  await waitLog(/영역 진입: Yeouido/, 40000, scene.start);
  await sleep(2500);
  let t = Date.now();
  demo('jump', '--es', 'to', 'building63');
  mark('announce', await waitSpeak(/is the 63 Building/, 40000, t) || Date.now());
  await sleep(3500); await shot('seoul');
  await untilQuiet(40000);
  // 한강공원 앞에서 손님이 영어로 물어봄 (오른쪽에 마포대교)
  demo('seek', '--ei', 'm', '5300');
  await sleep(1200);
  t = Date.now();
  await ask('q_en_right', 'ask');
  mark('answer', await waitSpeak(/Look to your right/, 15000, t) || Date.now());
  await sleep(3000); await shot('seoul_ask');
  await untilQuiet(30000);
  await gear('P');
  // 다음 실행을 위해 안내 언어를 한국어로 돌려 둠
  demo('end'); await sleep(1500); demo('home'); await sleep(2000); tapText(/^한국어$/);
  await endScene();
}

adb('shell', 'settings', 'put', 'system', 'show_touches', '0');
clearInterval(hb);
clearInterval(wavPoll);
await browser.close();
log('녹화 끝', OUT);
process.exit(0);
