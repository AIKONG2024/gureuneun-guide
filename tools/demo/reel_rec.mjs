// 여행 영상(reel) 화면을 1280x720 영상으로 녹화 (시연 영상에서 차량 화면 속 영상 자리에 넣음)
// 실행: node tools/demo/reel_rec.mjs  → /tmp/rec/reel.webm, /tmp/rec/reel_start.txt
import { createRequire } from 'node:module';
import fs from 'node:fs';
import path from 'node:path';

const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_CORE || 'playwright-core');   // tools/demo 에서 npm install
const CHROME = process.env.CHROME || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const BASE = process.env.BASE || 'http://localhost:8787';
const REC = process.env.REC_DIR || '/tmp/rec';
const RAW = path.join(REC, '.reel_raw');
fs.mkdirSync(RAW, { recursive: true });

const browser = await chromium.launch({ executablePath: CHROME, headless: true, args: ['--autoplay-policy=no-user-gesture-required'] });
const context = await browser.newContext({ viewport: { width: 1280, height: 720 }, deviceScaleFactor: 1, recordVideo: { dir: RAW, size: { width: 1280, height: 720 } } });
const page = await context.newPage();
const start = Date.now();
await page.goto(BASE + '/reel/GJ1016?car=1', { waitUntil: 'load' });
const loaded = Date.now();
fs.writeFileSync(path.join(REC, 'reel_start.txt'), JSON.stringify({ start, loaded }));
await page.waitForTimeout(66000);
const video = page.video();
await context.close();
await browser.close();
const p = await video.path();
fs.renameSync(p, path.join(REC, 'reel.webm'));
console.log('reel', path.join(REC, 'reel.webm'), 'load', loaded - start, 'ms');

