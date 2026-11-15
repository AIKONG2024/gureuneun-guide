// 구르는 가이드 화면 확인용 촬영 도구
// 사용: node companion/test/shots.mjs <출력 폴더> [phone,reel,car]   (먼저 서버를 켜 둠: node companion/server.js)
// - phone: 휴대폰 390x844@3x 로 우리 여행 4개 탭(누구세요, 지금, 담기, 일정, 기록)과 담기·들르기·사진 올리기 흐름
// - reel : 서버의 /reel/GJ1016 장면마다 1280x720
// - car  : file:// 로 연 reel/index.html?car=1 에 가짜 window.CarReel(data, ended)을 넣어 차량 WebView 흐름 확인
// 주의: 시작할 때 서버의 GJ1016 여행을 처음 상태로 되돌리고 차량 상태를 덮어씀. 차량 앱이 연결된 시연 서버에서는 멈춤.
//       시험은 따로 띄운 서버에서: PORT=8797 STATE_DIR=/tmp/gg/state UPLOAD_DIR=/tmp/gg/uploads node companion/server.js
//       BASE=http://localhost:8797 node companion/test/shots.mjs <출력 폴더>   (그래도 시연 서버에서 하려면 FORCE=1)
import { createRequire } from 'node:module';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const PW = process.env.PLAYWRIGHT_CORE || 'playwright-core';
const { chromium } = require(PW);
const CHROME = process.env.CHROME || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const BASE = process.env.BASE || 'http://localhost:8787';
const HERE = path.dirname(fileURLToPath(import.meta.url));
const WEB = path.join(HERE, '..', 'web');
const OUT = path.resolve(process.argv[2] || path.join(HERE, 'shots'));
const ONLY = (process.argv[3] || 'phone,reel,car').split(',');
const CODE = 'GJ1016';
fs.mkdirSync(OUT, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a);
const fixture = (n) => fs.readFileSync(path.join(HERE, n), 'utf8');

async function api(method, p, body) {
  const r = await fetch(BASE + p, { method, headers: body ? { 'Content-Type': 'application/json' } : {}, body: body == null ? undefined : typeof body === 'string' ? body : JSON.stringify(body) });
  return r.json();
}

const browser = await chromium.launch({ executablePath: CHROME, headless: true, args: ['--autoplay-policy=no-user-gesture-required'] });
const problems = [];
function watch(page, tag) {
  page.on('console', (m) => { if (m.type() === 'error' || m.type() === 'warning') problems.push('[' + tag + '] console.' + m.type() + ': ' + m.text()); });
  page.on('pageerror', (e) => problems.push('[' + tag + '] pageerror: ' + e.message));
  page.on('requestfailed', (r) => problems.push('[' + tag + '] requestfailed: ' + r.url() + ' ' + ((r.failure() && r.failure().errorText) || '')));
  page.on('response', (r) => { if (r.status() >= 400) problems.push('[' + tag + '] HTTP ' + r.status() + ' ' + r.url()); });
}
async function shot(page, name) {
  await page.screenshot({ path: path.join(OUT, name + '.png') });
  log('찍음', name);
}

try {
  const live = await api('GET', '/api/trip/' + CODE + '?full=1').catch(() => null);
  if (live && live.carOnline && !process.env.FORCE) {
    console.error('차량 앱이 이 서버(' + BASE + ')에 연결되어 있어요. 시연 상태를 덮어쓰지 않게 멈춰요. 다른 포트의 시험 서버를 BASE 로 주세요(FORCE=1 이면 그대로 진행)');
    process.exitCode = 2;
    throw new Error('live car');
  }
  if (ONLY.includes('phone')) {
    await api('POST', '/api/trip/' + CODE + '/reset');
    const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 3, isMobile: true, hasTouch: true, locale: 'ko-KR' });
    const page = await ctx.newPage();
    watch(page, 'phone');
    await page.goto(BASE + '/t/' + CODE);
    await page.waitForSelector('#whoOv.on');
    await page.evaluate(() => document.fonts.ready);
    await sleep(600);
    await shot(page, 'p01_who');
    await page.click('#whoOv [data-id="mom"]');
    await sleep(700);
    await shot(page, 'p02_now_empty');

    await page.click('#tabbar [data-tab="add"]');
    await sleep(300);
    await shot(page, 'p03_add_empty');
    await page.click('[data-act="example"][data-i="0"]');
    await page.waitForSelector('#pv .pv-bd');
    await sleep(900);
    await shot(page, 'p04_add_preview');
    await page.click('#addBtn');
    await sleep(1600);
    await shot(page, 'p05_add_done');
    await page.fill('#link', 'https://blog.naver.com/gureun_family/' + encodeURIComponent('서울-한강-야경'));
    await page.press('#link', 'Enter');
    await page.waitForSelector('#pv .nf');
    await sleep(400);
    await shot(page, 'p06_add_notfound');
    await page.fill('#nfName', '월정교');
    await page.click('[data-act="find-name"]');
    await page.waitForSelector('#pv .pv-bd');
    await sleep(800);
    await shot(page, 'p07_add_byname');

    // 차량이 상태를 보냄: 경주 여행 모드, 첨성대 설명 중
    await page.click('#tabbar [data-tab="plan"]');
    await api('PUT', '/api/trip/' + CODE + '/state', fixture('car_drive.json'));
    await sleep(2300);
    await shot(page, 'p08_plan_now_pill');
    await page.click('#tabbar [data-tab="now"]');
    await sleep(600);
    await shot(page, 'p09_now');
    await page.click('#nowBox [data-act="visit"]');
    await sleep(500);
    await shot(page, 'p10_now_visit_toast');
    await page.evaluate(() => { const p = document.querySelector('#p-now'); p.scrollTop = p.scrollHeight; });
    await sleep(2600);
    await shot(page, 'p11_now_scrolled');
    await page.click('#heardList .row');
    await sleep(700);
    await shot(page, 'p12_sheet_sight');
    await page.mouse.click(195, 60);
    await sleep(500);
    await page.evaluate(() => { document.querySelector('#p-now').scrollTop = 0; });

    await page.click('#tabbar [data-tab="plan"]');
    await sleep(300);
    await page.click('#memList [data-act="share"]');
    await sleep(2600);
    await shot(page, 'p13_plan');
    await page.evaluate(() => { const p = document.querySelector('#p-plan'); p.scrollTop = p.scrollHeight; });
    await sleep(300);
    await shot(page, 'p14_plan_scrolled');

    await page.click('#tabbar [data-tab="rec"]');
    await sleep(300);
    await page.setInputFiles('#file', path.join(WEB, 'photos', 'gyerim_night_1.jpg'));
    await sleep(2800);
    await shot(page, 'p15_rec_wait');

    // 숙소 도착: 여행 영상과 티켓이 준비됨
    await page.click('#tabbar [data-tab="now"]');
    await api('PUT', '/api/trip/' + CODE + '/state', fixture('car_record.json'));
    await sleep(2300);
    await shot(page, 'p16_now_record_pill');
    await page.click('#tabbar [data-tab="rec"]');
    await sleep(1200);
    await shot(page, 'p17_rec_ready');
    await page.click('.vcard');
    await sleep(5000);
    await shot(page, 'p18_rec_player');
    await page.click('[data-act="close-player"]');
    await sleep(300);
    const [dl] = await Promise.all([page.waitForEvent('download', { timeout: 8000 }).catch(() => null), page.click('[data-act="save-ticket"]')]);
    if (dl) { await dl.saveAs(path.join(OUT, 'p19_ticket_saved.png')); log('티켓 그림 저장됨'); } else problems.push('[phone] 티켓 저장 내려받기가 없었음');
    await sleep(500);
    await shot(page, 'p20_rec_saved_toast');
    const pres = await api('GET', '/api/trip/' + CODE + '/presence');
    log('presence', JSON.stringify(pres));
    await ctx.close();
  }

  if (ONLY.includes('reel')) {
    await api('PUT', '/api/trip/' + CODE + '/state', fixture('car_record.json'));
    const ctx = await browser.newContext({ viewport: { width: 1280, height: 720 }, deviceScaleFactor: 1 });
    const page = await ctx.newPage();
    watch(page, 'reel');
    await page.goto(BASE + '/reel/' + CODE + '?t=0');
    await page.waitForFunction(() => window.reel && document.title === 'ready', null, { timeout: 20000 });
    const scenes = await page.evaluate(() => window.reel.scenes);
    const T = await page.evaluate(() => window.reel.duration);
    log('영상 길이', T.toFixed(2), '초, 장면', scenes.length, JSON.stringify(scenes));
    let i = 0;
    for (const [name, t0, t1] of scenes) {
      const t = Math.min(T, t0 + (t1 - t0) * (name.includes('ticket') ? 0.8 : 0.62));
      await page.evaluate((x) => window.reel.seek(x), t);
      await sleep(300);
      await shot(page, 'r' + String(++i).padStart(2, '0') + '_' + name.replace('sc-', '') + '_' + t.toFixed(1) + 's');
    }
    await page.evaluate((x) => window.reel.seek(x), scenes[3][1] + 0.45);
    await sleep(300);
    await shot(page, 'r' + String(++i).padStart(2, '0') + '_crossfade_' + (scenes[3][1] + 0.45).toFixed(1) + 's');
    await ctx.close();
  }

  if (ONLY.includes('car')) {
    const st = await api('GET', '/api/trip/' + CODE + '?full=1');
    const ups = (st.uploads || []).map((u) => u.url);
    const trip = JSON.parse(fixture('car_record.json'));
    trip.record.photos = trip.record.photos.concat(ups.slice(-2));
    trip.base = BASE;
    const ctx = await browser.newContext({ viewport: { width: 1920, height: 1000 }, deviceScaleFactor: 1 });
    await ctx.addInitScript((json) => { window.__ended = 0; window.CarReel = { data: () => json, ended: () => { window.__ended++; } }; }, JSON.stringify(trip));
    const page = await ctx.newPage();
    watch(page, 'car');
    await page.goto('file://' + path.join(WEB, 'reel', 'index.html') + '?code=' + CODE + '&car=1');
    await page.waitForFunction(() => window.reel && window.reel.playing, null, { timeout: 20000 });
    await sleep(3000);
    const s1 = await page.evaluate(() => ({ t: +window.reel.t.toFixed(2), sound: window.reel.sound, source: window.reel.source, map: window.reel.map, T: +window.reel.duration.toFixed(2), scenes: window.reel.scenes.map((s) => s[0]) }));
    log('차량 모드 시작', JSON.stringify(s1));
    await shot(page, 'c01_car_playing');
    const ph = s1.scenes.indexOf('sc-photos');
    if (ph >= 0) {
      const sc = await page.evaluate(() => window.reel.scenes);
      await page.evaluate((x) => window.reel.seek(x), sc[ph][1] + 2.6);
      await sleep(400);
      await shot(page, 'c02_car_photos_with_uploads');
    }
    await page.evaluate(() => window.reel.seek(window.reel.duration - 1.2));
    await sleep(2600);
    const s2 = await page.evaluate(() => ({ ended: window.reel.ended, endedCalls: window.__ended }));
    log('차량 모드 끝', JSON.stringify(s2));
    await shot(page, 'c03_car_end');
    await ctx.close();

    // 서버에 닿지 않고 data/*.json 도 못 읽는 차량(최악의 경우)
    const trip2 = JSON.parse(fixture('car_record.json'));
    trip2.base = 'http://127.0.0.1:9';
    const ctx2 = await browser.newContext({ viewport: { width: 1280, height: 720 }, deviceScaleFactor: 1 });
    await ctx2.addInitScript((json) => { window.CarReel = { data: () => json, ended: () => {} }; }, JSON.stringify(trip2));
    const p2 = await ctx2.newPage();
    await p2.goto('file://' + path.join(WEB, 'reel', 'index.html') + '?code=' + CODE + '&car=1');
    await p2.waitForFunction(() => window.reel && window.reel.playing, null, { timeout: 25000 });
    const sc2 = await p2.evaluate(() => window.reel.scenes);
    log('서버 없는 차량', JSON.stringify(sc2.map((s) => s[0])), 'map', await p2.evaluate(() => window.reel.map));
    await p2.evaluate(() => window.reel.pause());
    for (const want of ['sc-map', 'sc-story']) {
      const k = sc2.findIndex((s) => s[0] === want);
      if (k < 0) continue;
      await p2.evaluate((x) => window.reel.seek(x), sc2[k][1] + (sc2[k][2] - sc2[k][1]) * 0.62);
      await sleep(400);
      await shot(p2, 'c04_car_offline_' + want.replace('sc-', ''));
    }
    await ctx2.close();
  }
} catch (e) {
  if (e.message !== 'live car') throw e;
} finally {
  await browser.close();
  const uniq = Array.from(new Set(problems));
  console.log('\n문제 ' + uniq.length + '건');
  for (const p of uniq) console.log(' - ' + p);
}
