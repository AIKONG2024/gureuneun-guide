// 구르는 가이드 여행 그룹 서버
// 차 앱과 동승자 휴대폰(웹)을 네 자리 그룹 번호로 묶고, 이야기 시작과 고른 장소를 주고받는다.
// 퀴즈(차가 내고 휴대폰이 풀고 서버가 채점), 차에 말로 물어본 것과 답, 휴대폰에서 이어 듣기도 여기서 오간다.
// 차의 실시간 위치는 받지 않는다. 여행이 끝난 뒤 차가 올린 기록(지나온 길, 명소를 지난 시각)만 보관한다.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import zlib from 'node:zlib';
import { spawn } from 'node:child_process';
import { pipeline } from 'node:stream';
import { fileURLToPath } from 'node:url';
import { WebSocketServer } from 'ws';

// 블랙박스 파일 이름의 시각(20261109_131530)을 한국 시각으로 읽음. 다른 시간대 컴퓨터에서 돌려도 같게
process.env.TZ = process.env.TZ || 'Asia/Seoul';
const PORT = Number(process.env.PORT || 8787);
const HERE = path.dirname(fileURLToPath(import.meta.url));
const WEB = path.join(HERE, 'web');
const SCENARIOS = path.join(HERE, '..', 'app', 'src', 'main', 'assets', 'scenarios');
// 시험할 때는 DATA_DIR 로 임시 폴더를 줘서 실제 data 폴더를 건드리지 않음
const DATA = path.resolve(process.env.DATA_DIR || path.join(HERE, 'data'));
const FONT = process.env.FONT_FILE || ['/System/Library/Fonts/AppleSDGothicNeo.ttc', '/usr/share/fonts/truetype/nanum/NanumGothic.ttf'].find(f => fs.existsSync(f));
let HAS_DRAWTEXT = false;
let HAS_PIL = false;

function lanIp() {
  for (const list of Object.values(os.networkInterfaces())) {
    for (const a of list || []) if (a.family === 'IPv4' && !a.internal) return a.address;
  }
  return '127.0.0.1';
}
const PUBLIC_BASE = process.env.PUBLIC_BASE || ('http://' + lanIp() + ':' + PORT);
const CLIPS = path.join(WEB, 'clips');
// 개발용 영상 클립 만들기 API. RENDER=1 로 켰을 때, 이 컴퓨터에서 바로 온 요청만 받음
const RENDER = process.env.RENDER === '1';
const CODE_RE = /^\d{4}$/;
// 끝난 그룹은 KEEP_DAYS(기본 3일) 뒤 폴더째 지움. 0 이나 off 면 지우지 않음
const KEEP_DAYS = process.env.KEEP_DAYS === undefined || process.env.KEEP_DAYS === '' ? 3 : (Number(process.env.KEEP_DAYS) > 0 ? Number(process.env.KEEP_DAYS) : 0);
// 빌트인 캠 영상 한 개의 최대 크기(MB). 넘으면 413
const MAX_UPLOAD_MB = Math.max(1, Number(process.env.MAX_UPLOAD_MB) || 400);

/** root 안쪽 경로만 돌려줌. 폴더 밖(.. 이나 절대 경로)으로 나가면 null */
function inside(root, ...parts) {
  const full = path.resolve(root, ...parts);
  const rel = path.relative(root, full);
  if (!rel || rel === '..' || rel.startsWith('..' + path.sep) || path.isAbsolute(rel)) return null;
  return full;
}
const groupDir = code => (CODE_RE.test(String(code)) ? inside(DATA, String(code)) : null);

/** 이 컴퓨터에서 바로 온 요청 (터널이나 프록시를 거친 것은 아님) */
function directLocal(req) {
  const a = req.socket.remoteAddress || '', h = req.headers;
  return (a === '127.0.0.1' || a === '::1' || a === '::ffff:127.0.0.1') && !h['x-forwarded-for'] && !h['forwarded'] && !h['x-real-ip'] && !h['cf-connecting-ip'];
}

function clientIp(req) {
  return String(req.socket.remoteAddress || '').replace(/^::ffff:/, '');
}

// 간단한 속도 제한: windowMs 동안 max 번까지. 넘으면 기다릴 초를 돌려줌
class Limiter {
  constructor(max, windowMs) { this.max = max; this.windowMs = windowMs; this.hits = new Map(); }
  take(key) {
    const now = Date.now();
    const arr = (this.hits.get(key) || []).filter(t => now - t < this.windowMs);
    if (arr.length >= this.max) { this.hits.set(key, arr); return Math.max(1, Math.ceil((this.windowMs - (now - arr[0])) / 1000)); }
    arr.push(now);
    this.hits.set(key, arr);
    return 0;
  }
  sweep() {
    const now = Date.now();
    for (const [k, arr] of this.hits) { const a = arr.filter(t => now - t < this.windowMs); if (a.length) this.hits.set(k, a); else this.hits.delete(k); }
  }
}
const pidLimit = new Limiter(10, 10 * 60 * 1000);   // 새 휴대폰 구분 값은 IP·그룹마다 10분에 10개까지
const lookupLimit = new Limiter(5, 60 * 1000);       // 키 없이 네 자리 번호로 찾는 것은 IP마다 1분에 5번
const joinLimit = new Limiter(5, 60 * 1000);         // 두 번째 차부터 그룹에 들어오기
const createLimit = new Limiter(20, 10 * 60 * 1000); // 그룹 만들기
const reportLimit = new Limiter(3, 60 * 1000);       // 틀린 내용 신고
const okPairs = new Map();                           // 'ip|번호' → 이미 맞힌 번호 (같은 휴대폰이 다시 붙을 때는 세지 않음)
function lookupWait(ip, code) {
  const exp = okPairs.get(ip + '|' + code);
  return exp && exp > Date.now() ? 0 : lookupLimit.take(ip);
}
const lookupOk = (ip, code) => okPairs.set(ip + '|' + code, Date.now() + 6 * 3600 * 1000);
// 속도 제한 기록은 10분마다 정리 (오래 켜 둬도 메모리가 늘지 않게)
setInterval(() => {
  for (const l of [pidLimit, lookupLimit, joinLimit, createLimit, reportLimit]) l.sweep();
  const now = Date.now();
  for (const [k, exp] of okPairs) if (exp < now) okPairs.delete(k);
}, 10 * 60 * 1000).unref();

// 시연 녹화 도구(tools/demo)용 예외: DEMO_LOCAL_TRUST=1 이면 이 컴퓨터에서 바로 온 접속은 키가 없어도 차·full 로 받음
const DEMO_LOCAL_TRUST = process.env.DEMO_LOCAL_TRUST === '1';
const localTrusted = req => DEMO_LOCAL_TRUST && directLocal(req);

// 그룹마다 휴대폰 키(QR 주소의 k) 하나, 차마다 차 키 하나. 128비트 난수
const newKey = () => crypto.randomBytes(16).toString('base64url');
function sameKey(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string' || !a || a.length !== b.length) return false;
  const x = Buffer.from(a), y = Buffer.from(b);
  return x.length === y.length && crypto.timingSafeEqual(x, y);
}

// 휴대폰에 주는 시나리오에서는 퀴즈를 뺀다 (정답 번호가 들어 있음. 퀴즈는 차가 낼 때 서버가 정답을 빼고 보냄)
const scenarioCache = new Map();
function scenarioEntry(id) {
  const file = path.join(SCENARIOS, id + '.json');
  let st;
  try { st = fs.statSync(file); } catch { return null; }
  const hit = scenarioCache.get(id);
  if (hit && hit.mtimeMs === st.mtimeMs && hit.size === st.size) return hit;
  try {
    const data = JSON.parse(fs.readFileSync(file, 'utf8'));
    const pub = Object.assign({}, data);
    delete pub.quiz;
    const entry = { mtimeMs: st.mtimeMs, size: st.size, data, body: JSON.stringify(pub) };
    scenarioCache.set(id, entry);
    return entry;
  } catch (e) { console.warn('[scenario]', id, e.message); return null; }
}

/** code -> group */
const groups = new Map();

function newCode() {
  for (let i = 0; i < 1000; i++) {
    const c = String(1000 + crypto.randomInt(9000));
    if (!groups.has(c)) return c;
  }
  throw new Error('no code');
}

function makeGroup(code, scenarioId, lang) {
  const dir = groupDir(code);
  if (!dir) throw new Error('bad code');
  const g = {
    code, scenarioId, lang, createdAt: Date.now(),
    phoneKey: newKey(), carSlots: [],
    cars: new Map(), phones: new Set(),
    story: null, line: null, passes: [], picks: [], acks: [], reports: [], said: [],
    record: null, photos: [], highlight: null, ended: false,
    quizzes: {}, answers: [], qa: [], handoff: null, treasure: null, stars: null,
    pids: [], pidSet: new Set(),
  };
  groups.set(code, g);
  fs.mkdirSync(path.join(dir, 'photos'), { recursive: true });
  return g;
}

// 차 자리: 번호, 서버가 붙인 이름('2호차'), 차 키. 그룹마다 8대까지
function addCar(g) {
  if (g.carSlots.length >= 8) return null;
  const no = g.carSlots.reduce((m, s) => Math.max(m, s.no), 0) + 1;
  const slot = { no, name: no + '호차', key: newKey(), joinedAt: Date.now() };
  g.carSlots.push(slot);
  return slot;
}
const carSlotByKey = (g, key) => (key && g.carSlots.find(s => sameKey(s.key, String(key)))) || null;
// k 를 car 보다 앞에 둠: 차 앱이 주소 끝에 /record 를 붙여도(car=1/record) 휴대폰이 알아봄
const joinUrlFor = (g, no) => PUBLIC_BASE + '/g/' + g.code + '?k=' + g.phoneKey + '&car=' + no;
const recordUrlFor = (g, no) => PUBLIC_BASE + '/g/' + g.code + '/record?k=' + g.phoneKey + '&car=' + no;
function carCredentials(g, slot) {
  return {
    code: g.code, carKey: slot.key, carNo: slot.no, carName: slot.name,
    joinUrl: joinUrlFor(g, slot.no), recordUrl: recordUrlFor(g, slot.no), publicBase: PUBLIC_BASE,
    scenarioId: g.scenarioId, lang: g.lang,
  };
}

// who: { role: 'car' | 'phone', level: 'full' | 'basic', pid }
// 번호만 넣고 들어온 휴대폰(basic)에는 지나온 길·사진·영상·차에 물어본 것을 주지 않음
function summary(g, who = {}) {
  const full = who.role === 'car' || who.level === 'full';
  const s = {
    code: g.code, scenarioId: g.scenarioId, lang: g.lang, publicBase: PUBLIC_BASE, access: full ? 'full' : 'basic',
    cars: [...g.cars.values()].map(c => c.name), phones: g.phones.size,
    story: g.story, line: g.line, passes: g.passes, ended: g.ended,
    // 차가 둘이면 휴대폰은 QR 의 car=N 으로 자기 차 이야기만 따름. 다시 붙을 때 차마다 마지막 이야기를 그리게 함께 줌
    stories: Object.values(g.storyBy || {}), lines: Object.values(g.lineBy || {}).filter(Boolean),
    quizzes: Object.values(g.quizzes).map(publicQuiz), answers: g.answers.map(a => publicAnswer(g, a, who.pid)),
    handoff: g.handoff, treasure: g.treasure, stars: g.stars,
    keepDays: KEEP_DAYS,   // 휴대폰의 '여행이 끝나고 N일 뒤 지워져요' 안내에 씀 (0 이면 지우지 않음)
  };
  if (full) Object.assign(s, { record: g.record, photos: g.photos, highlight: g.highlight, qa: g.qa.slice(-20), scores: scores(g), maxUploadMB: MAX_UPLOAD_MB });
  return s;
}

// 휴대폰에는 정답을 미리 보내지 않는다 (답을 고른 뒤에 알려 줌)
function publicQuiz(q) {
  return { id: q.id, placeId: q.placeId || q.id, name: q.name, q: q.q, choices: q.choices, lang: q.lang, at: q.at, car: q.car, carNo: q.carNo };
}

// 다른 휴대폰에는 누가 맞혔는지만. 고른 번호와 정답은 답한 휴대폰(같은 pid)에만
function publicAnswer(g, a, pid) {
  const mine = !!pid && a.pid === pid;
  const o = { id: a.id, by: a.by, correct: !!a.correct, at: a.at, mine };
  if (mine) {
    o.choice = a.choice;
    const q = g.quizzes[a.id];
    if (q) o.answer = q.answer;
  }
  return o;
}

// 이름별 맞힌 개수
function scores(g) {
  const s = {};
  for (const a of g.answers) if (a.correct) s[a.by] = (s[a.by] || 0) + 1;
  return s;
}

// 서버를 다시 켜도 여행 그룹이 이어지도록 디스크에 저장해 둔다
const saveTimers = new Map();
function writeGroup(g) {
  // group.json 은 /media 로 열리지 않는 곳에만 둠 (퀴즈 정답과 키가 들어 있음)
  if (groups.get(g.code) !== g) return;   // 그사이 지운 그룹은 다시 쓰지 않음
  const keep = { code: g.code, scenarioId: g.scenarioId, lang: g.lang, createdAt: g.createdAt, phoneKey: g.phoneKey, carSlots: g.carSlots, story: g.story, storyBy: g.storyBy, passes: g.passes, picks: g.picks, acks: g.acks, reports: g.reports, record: g.record, photos: g.photos, highlight: g.highlight, ended: g.ended, endedAt: g.endedAt, quizzes: g.quizzes, answers: g.answers, qa: g.qa, handoff: g.handoff, treasure: g.treasure, stars: g.stars, pids: g.pids };
  try {
    const f = path.join(DATA, g.code, 'group.json');
    fs.writeFileSync(f + '.tmp', JSON.stringify(keep));
    fs.renameSync(f + '.tmp', f);
  } catch (e) { console.warn('[persist]', e.message); }
}
function persist(g) {
  clearTimeout(saveTimers.get(g.code));
  saveTimers.set(g.code, setTimeout(() => { saveTimers.delete(g.code); writeGroup(g); }, 300));
}

function restoreGroups() {
  if (!fs.existsSync(DATA)) return;
  for (const code of fs.readdirSync(DATA)) {
    const f = path.join(DATA, code, 'group.json');
    if (!/^\d{4}$/.test(code) || !fs.existsSync(f)) continue;
    try {
      const s = JSON.parse(fs.readFileSync(f, 'utf8'));
      const g = makeGroup(code, s.scenarioId, s.lang);
      Object.assign(g, s, { cars: new Map(), phones: new Set() });
      g.pids = Array.isArray(g.pids) ? g.pids : [];
      g.pidSet = new Set(g.pids);
      if (!Array.isArray(g.carSlots)) g.carSlots = [];
      if (!s.phoneKey) persist(g);   // 예전 그룹에는 키가 없어 새로 만든 키를 저장
      if (g.highlight && g.highlight.status === 'processing') g.highlight = null;
      // 예전 그룹은 끝난 시각이 없음: 기록의 끝난 시각, 없으면 파일 시각으로
      if (g.ended && !g.endedAt) g.endedAt = Number(g.record && g.record.endedAt) || fs.statSync(f).mtimeMs;
    } catch (e) { console.warn('[restore]', code, e.message); }
  }
  if (groups.size) console.log('[group] 이전 그룹 ' + groups.size + '개를 불러왔어요');
}
restoreGroups();

const markEnded = g => { g.ended = true; g.endedAt = Date.now(); };

// 끝난 지 KEEP_DAYS 가 지난 그룹은 폴더째(사진, 하이라이트, 기록) 지움. 차가 붙어 있거나 영상을 만드는 중이면 미룸
function sweepOld() {
  if (!(KEEP_DAYS > 0)) return;
  const limit = KEEP_DAYS * 24 * 3600 * 1000, now = Date.now();
  for (const g of [...groups.values()]) {
    if (!g.ended || !g.endedAt || now - g.endedAt < limit || g.cars.size || g.job) continue;
    for (const p of g.phones) { send(p, { type: 'error', error: 'no such group' }); try { p.close(4404, 'no such group'); } catch {} }
    groups.delete(g.code);
    clearTimeout(saveTimers.get(g.code));
    saveTimers.delete(g.code);
    const dir = groupDir(g.code);
    if (dir) { try { fs.rmSync(dir, { recursive: true, force: true }); } catch (e) { console.warn('[keep]', g.code, e.message); } }
    console.log('[keep] 끝난 지 ' + KEEP_DAYS + '일이 지나 그룹을 지웠어요', g.code);
  }
}
sweepOld();
setInterval(sweepOld, 3600 * 1000).unref();

function send(ws, obj) {
  try { if (ws.readyState === 1) ws.send(JSON.stringify(obj)); } catch {}
}

// full: QR(휴대폰 키)로 들어온 휴대폰에만 보냄 (사진, 영상, 지나온 길, 물어본 것)
function broadcast(g, obj, { cars = true, phones = true, except = null, full = false } = {}) {
  persist(g);
  if (cars) for (const c of g.cars.values()) if (c.ws !== except) send(c.ws, obj);
  if (phones) for (const p of g.phones) if (p !== except && (!full || p.level === 'full')) send(p, obj);
}

function members(g) {
  broadcast(g, { type: 'members', cars: [...g.cars.values()].map(c => c.name), phones: g.phones.size });
}

// ---------------------------------------------------------------- HTTP
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json; charset=utf-8', '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.mp4': 'video/mp4', '.svg': 'image/svg+xml', '.txt': 'text/plain; charset=utf-8', '.webp': 'image/webp', '.woff2': 'font/woff2', '.woff': 'font/woff' };

function json(res, code, obj) {
  // CORS 머리글은 두지 않음 (휴대폰 페이지는 같은 주소, 차는 OkHttp 라 필요 없음)
  res.writeHead(code, { 'Content-Type': MIME['.json'], 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(obj));
}

// Range 머리글은 한 구간만 받음. 틀렸거나 파일 밖이면 null(416), 여러 구간이면 'all'(통째로 보냄)
function parseRange(header, size) {
  const s = String(header).trim();
  if (s.includes(',')) return 'all';
  const m = /^bytes=(\d*)-(\d*)$/.exec(s);
  if (!m || (m[1] === '' && m[2] === '') || size <= 0) return null;
  let start, end;
  if (m[1] === '') {
    const n = Number(m[2]);
    if (!Number.isSafeInteger(n) || n <= 0) return null;
    start = Math.max(0, size - n);
    end = size - 1;
  } else {
    start = Number(m[1]);
    end = m[2] === '' ? size - 1 : Number(m[2]);
    if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end)) return null;
    end = Math.min(end, size - 1);
  }
  return start <= end && start < size ? { start, end } : null;
}

const gzCache = new Map();   // 웹 폴더 글자 파일의 gzip 결과 (파일이 바뀌면 다시 만듦)
function serveFile(res, file, req) {
  fs.stat(file, (err, st) => {
    try {
      if (err || !st.isFile()) { res.writeHead(404); return res.end('not found'); }
      const type = MIME[path.extname(file).toLowerCase()] || 'application/octet-stream';
      const range = req && req.headers.range;
      if (range && type.startsWith('video/')) {
        const r = parseRange(range, st.size);
        if (!r) { res.writeHead(416, { 'Content-Range': 'bytes */' + st.size }); return res.end(); }
        if (r !== 'all') {
          res.writeHead(206, { 'Content-Type': type, 'Content-Range': 'bytes ' + r.start + '-' + r.end + '/' + st.size, 'Accept-Ranges': 'bytes', 'Content-Length': r.end - r.start + 1 });
          return pipeline(fs.createReadStream(file, { start: r.start, end: r.end }), res, () => {});
        }
      }
      // 한 번 받은 파일은 다시 받지 않게(304). 3D·지도 라이브러리, 클립, 포스터는 1시간 동안 그대로 씀
      const lastMod = st.mtime.toUTCString();
      const cache = file.startsWith(DATA + path.sep) ? 'private, no-cache' : /[\\/](vendor|clips|posters)[\\/]/.test(file) ? 'public, max-age=3600' : 'no-cache';
      if (req && !range && req.headers['if-modified-since'] === lastMod) { res.writeHead(304, { 'Last-Modified': lastMod, 'Cache-Control': cache }); return res.end(); }
      // 글자 파일은 gzip 으로 보냄 (three.min.js 650KB → 약 160KB)
      if (req && st.size > 1024 && /^(text\/|application\/json|image\/svg)/.test(type) && /\bgzip\b/.test(req.headers['accept-encoding'] || '')) {
        let hit = gzCache.get(file);
        if (!hit || hit.mtimeMs !== st.mtimeMs) { hit = { mtimeMs: st.mtimeMs, body: zlib.gzipSync(fs.readFileSync(file)) }; gzCache.set(file, hit); }
        res.writeHead(200, { 'Content-Type': type, 'Content-Encoding': 'gzip', 'Content-Length': hit.body.length, 'Cache-Control': cache, 'Last-Modified': lastMod, 'Vary': 'Accept-Encoding' });
        return res.end(req.method === 'HEAD' ? undefined : hit.body);
      }
      res.writeHead(200, { 'Content-Type': type, 'Content-Length': st.size, 'Cache-Control': cache, 'Last-Modified': lastMod, 'Accept-Ranges': type.startsWith('video/') ? 'bytes' : 'none' });
      if (req && req.method === 'HEAD') return res.end();
      pipeline(fs.createReadStream(file), res, () => {});
    } catch (e) {
      console.warn('[file]', e.message);
      if (!res.headersSent) { res.writeHead(500); res.end(); } else res.destroy();
    }
  });
}

const httpError = (status, message) => Object.assign(new Error(message), { status });

/** 본문을 메모리로 읽음. 한도를 넘으면 413 (연결은 응답을 보낸 뒤 failRequest 가 끊음) */
function readBody(req, limit) {
  return new Promise((resolve, reject) => {
    const chunks = []; let size = 0, over = false;
    req.on('data', c => {
      if (over) return;
      size += c.length;
      if (size > limit) { over = true; chunks.length = 0; req.pause(); reject(httpError(413, 'too large')); } else chunks.push(c);
    });
    req.on('end', () => { if (!over) resolve(Buffer.concat(chunks)); });
    req.on('close', () => { if (!over && !req.complete) reject(httpError(400, 'aborted')); });
    req.on('error', reject);
  });
}

/** JSON 객체만 받음. 깨진 JSON, 배열, 숫자는 400 */
async function readJson(req, limit = 2 * 1024 * 1024) {
  const b = await readBody(req, limit);
  if (!b.length) return {};
  let v;
  try { v = JSON.parse(b.toString('utf8')); } catch { throw httpError(400, 'bad json'); }
  if (!v || typeof v !== 'object' || Array.isArray(v)) throw httpError(400, 'bad json');
  return v;
}

/** 캠 영상을 메모리에 모으지 않고 바로 디스크에 씀. 앞 12바이트에 ftyp 가 없으면(MP4/MOV 아님) 415, 너무 크면 413 */
function streamUpload(req, file, max) {
  return new Promise((resolve, reject) => {
    const out = fs.createWriteStream(file, { flags: 'wx' });
    let size = 0, head = Buffer.alloc(0), done = false;
    const rm = () => fs.rm(file, { force: true }, () => {});
    const fail = e => {
      if (done) return;
      done = true;
      req.pause();
      out.destroy();
      if (out.closed) rm(); else out.once('close', rm);
      reject(e);
    };
    out.on('error', () => fail(httpError(500, 'write failed')));
    out.on('finish', () => { if (!done) { done = true; resolve(size); } });
    req.on('error', () => fail(httpError(400, 'aborted')));
    req.on('close', () => { if (!req.complete) fail(httpError(400, 'aborted')); });
    req.on('data', c => {
      if (done) return;
      size += c.length;
      if (size > max) return fail(httpError(413, 'too large'));
      if (head.length < 12) {
        head = Buffer.concat([head, c.subarray(0, 12 - head.length)]);
        if (head.length >= 12 && head.toString('latin1', 4, 8) !== 'ftyp') return fail(httpError(415, 'not mp4'));
      }
      if (!out.write(c)) { req.pause(); out.once('drain', () => { if (!done) req.resume(); }); }
    });
    req.on('end', () => {
      if (done) return;
      if (head.length < 12) return fail(httpError(415, 'not mp4'));
      out.end();
    });
  });
}

/** 오류 응답을 보내고, 본문을 다 받지 않은 요청이면 남은 본문을 버리다가 5초 뒤 끊음 (큰 영상을 끝까지 받지 않게).
 *  바로 끊으면 브라우저가 오류 응답을 읽기 전에 '연결 끊김'만 보게 됨 */
function failRequest(req, res, e) {
  const status = (e && e.status) || 400;
  if (!res.headersSent) {
    res.writeHead(status, { 'Content-Type': MIME['.json'], 'Cache-Control': 'no-store' });
    res.end(JSON.stringify({ error: status >= 500 ? 'server error' : String((e && e.message) || 'bad request') }));
  }
  if (req.complete || req.destroyed) return;
  req.removeAllListeners('data');
  req.on('data', () => {});
  req.resume();
  const t = setTimeout(() => req.destroy(), 5000);
  req.once('end', () => clearTimeout(t));
  req.once('close', () => clearTimeout(t));
}

/** JPEG 에서 EXIF(GPS·기기 정보)·XMP·IPTC·주석 조각을 뺌. 휴대폰이 다시 그려 보내 이미 없지만 한 번 더 */
function stripJpegMeta(buf) {
  if (buf[0] !== 0xff || buf[1] !== 0xd8) return buf;
  const parts = [buf.subarray(0, 2)];
  let i = 2;
  while (i + 4 <= buf.length && buf[i] === 0xff) {
    const marker = buf[i + 1];
    if (marker === 0xff) { i += 1; continue; }
    if (marker === 0xda) break;   // 여기부터 그림 데이터
    const len = buf.readUInt16BE(i + 2);
    if (len < 2 || i + 2 + len > buf.length) return buf;
    const drop = marker === 0xe1 || (marker >= 0xe3 && marker <= 0xed) || marker === 0xef || marker === 0xfe;
    if (!drop) parts.push(buf.subarray(i, i + 2 + len));
    i += 2 + len;
  }
  parts.push(buf.subarray(i));
  return Buffer.concat(parts);
}

const server = http.createServer(async (req, res) => {
  try {
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('Referrer-Policy', 'strict-origin-when-cross-origin');
    // 주소가 깨져 있으면(%, // 등) 400. 예전에는 try 밖이라 서버가 통째로 꺼졌음
    let url, p;
    try { url = new URL(req.url || '/', 'http://x'); p = decodeURIComponent(url.pathname); } catch { return json(res, 400, { error: 'bad url' }); }
    if (p.includes('\0')) return json(res, 400, { error: 'bad url' });
    if (req.method === 'OPTIONS') { res.writeHead(204, { Allow: 'GET, HEAD, POST' }); return res.end(); }

    if (p === '/api/info') return json(res, 200, { ok: true, version: 2, publicBase: PUBLIC_BASE });

    // 개발용: 3D 장면 프레임을 받아 MP4 클립으로 만든다 (web/clips 에 저장되어 휴대폰이 씀)
    // 예전에는 누구나 start 값으로 서버 밖에 파일을 쓰고 web/clips 를 덮어쓸 수 있었음
    if (p.startsWith('/api/render/')) {
      if (!RENDER || !directLocal(req) || req.method !== 'POST') return json(res, 404, { error: 'not found' });
      const body = await readJson(req, 40 * 1024 * 1024);
      const name = String(body.name || '').replace(/[^\w]/g, '').slice(0, 60);
      const dir = name && inside(path.join(DATA, 'render'), name);
      if (!dir) return json(res, 400, { error: 'bad name' });
      if (p === '/api/render/start') {
        fs.rmSync(dir, { recursive: true, force: true });
        fs.mkdirSync(dir, { recursive: true });
        return json(res, 200, { ok: true });
      }
      if (p === '/api/render/frames') {
        const start = Number(body.start);
        if (!Number.isInteger(start) || start < 0 || start > 99999 || !Array.isArray(body.frames) || body.frames.length > 120) return json(res, 400, { error: 'bad frames' });
        fs.mkdirSync(dir, { recursive: true });
        for (let k = 0; k < body.frames.length; k++) {
          const f = inside(dir, String(start + k).padStart(5, '0') + '.jpg');
          if (!f) return json(res, 400, { error: 'bad frames' });
          fs.writeFileSync(f, Buffer.from(String(body.frames[k]).replace(/^data:image\/jpeg;base64,/, ''), 'base64'));
        }
        return json(res, 200, { ok: true });
      }
      if (p === '/api/render/done') {
        const fps = Number.isInteger(Number(body.fps)) ? Math.min(60, Math.max(1, Number(body.fps))) : 30;
        fs.mkdirSync(CLIPS, { recursive: true });
        const out = inside(CLIPS, name + '.mp4');
        if (!out) return json(res, 400, { error: 'bad name' });
        await run('ffmpeg', ['-y', '-protocol_whitelist', 'file', '-framerate', String(fps), '-i', path.join(dir, '%05d.jpg'), '-c:v', 'libx264', '-preset', 'slow', '-crf', '26', '-pix_fmt', 'yuv420p', '-movflags', '+faststart', '-an', out]);
        fs.rmSync(dir, { recursive: true, force: true });
        return json(res, 200, { ok: true, file: '/clips/' + name + '.mp4', bytes: fs.statSync(out).size });
      }
      return json(res, 404, { error: 'not found' });
    }

    if (p === '/api/groups' && req.method === 'POST') {
      const wait = createLimit.take(clientIp(req));
      if (wait) return json(res, 429, { error: 'too many', retryIn: wait });
      const body = await readJson(req);
      const sid = typeof body.scenarioId === 'string' && /^[a-z0-9_-]{1,40}$/.test(body.scenarioId) && scenarioEntry(body.scenarioId) ? body.scenarioId : 'gyeongju';
      const g = makeGroup(newCode(), sid, body.lang === 'en' ? 'en' : 'ko');
      const slot = addCar(g);
      persist(g);
      console.log('[group] new', g.code, g.scenarioId, g.lang);
      return json(res, 200, carCredentials(g, slot));
    }

    let m = p.match(/^\/api\/groups\/(\d{4})(\/[a-z]+)?$/);
    if (m) {
      const ip = clientIp(req);
      const g = groups.get(m[1]);
      const sub = m[2] || '';
      // 두 번째 차부터: 네 자리 번호로 들어오면 서버가 차 번호와 이름('2호차'), 차 키를 줌
      if (sub === '/join' && req.method === 'POST') {
        const wait = joinLimit.take(ip);
        if (wait) return json(res, 429, { error: 'too many', retryIn: wait });
        if (!g) return json(res, 404, { error: 'no such group' });
        const slot = addCar(g);
        if (!slot) return json(res, 409, { error: 'too many cars' });
        persist(g);
        console.log('[group] car joined', g.code, slot.no);
        return json(res, 200, Object.assign(carCredentials(g, slot), { cars: [...g.cars.values()].map(c => c.name), phones: g.phones.size }));
      }
      // 누가 묻는지: 차 키(X-Car-Key) → 차, 휴대폰 키(X-Group-Key 나 ?k=) → full, 키 없음 → basic
      const carKey = req.headers['x-car-key'];
      const phoneKey = req.headers['x-group-key'] || url.searchParams.get('k');
      const slot = g ? carSlotByKey(g, carKey) : null;
      const keyOk = !!slot || !!(g && !carKey && phoneKey && sameKey(g.phoneKey, String(phoneKey)));
      if (!g || ((carKey || phoneKey) && !keyOk)) {
        // 없는 번호와 틀린 키는 모두 번호 조회로 셈 (무차별 대입 막기)
        const wait = carKey || phoneKey ? lookupLimit.take(ip) : lookupWait(ip, m[1]);
        if (wait) return json(res, 429, { error: 'too many', retryIn: wait });
        return json(res, g ? 401 : 404, { error: g ? 'bad key' : 'no such group' });
      }
      const trusted = localTrusted(req);
      if (!keyOk && !trusted) {
        const wait = lookupWait(ip, m[1]);
        if (wait) return json(res, 429, { error: 'too many', retryIn: wait });
        lookupOk(ip, m[1]);
      }
      const who = slot ? { role: 'car' } : { role: 'phone', level: keyOk || trusted ? 'full' : 'basic' };
      const full = !!slot || who.level === 'full';
      if (!sub && (req.method === 'GET' || req.method === 'HEAD')) {
        return json(res, 200, Object.assign(summary(g, who), slot ? { carNo: slot.no, carName: slot.name, joinUrl: joinUrlFor(g, slot.no), recordUrl: recordUrlFor(g, slot.no) } : {}));
      }
      if (sub === '/record' && req.method === 'POST') {
        if (!slot && !trusted) return json(res, 401, { error: 'car key required' });
        g.record = cleanRecord(await readJson(req, 4 * 1024 * 1024));
        markEnded(g);
        broadcast(g, { type: 'record', record: g.record }, { full: true });
        return json(res, 200, { ok: true });
      }
      if ((sub === '/photos' || sub === '/dashcam') && !full) return failRequest(req, res, httpError(403, 'qr needed'));
      if (sub === '/photos' && req.method === 'POST') {
        if (g.photos.length >= 300) return failRequest(req, res, httpError(409, 'too many photos'));
        const body = await readJson(req, 12 * 1024 * 1024);
        const mm = /^data:image\/(jpeg|jpg|png|webp);base64,(.+)$/.exec(body.dataUrl || '');
        if (!mm) return json(res, 400, { error: 'bad image' });
        const ext = mm[1] === 'png' ? 'png' : mm[1] === 'webp' ? 'webp' : 'jpg';
        let bytes = Buffer.from(mm[2], 'base64');
        // 이름표(data:image/png)만 보지 않고 파일 앞부분으로 진짜 그림인지 확인
        const real = ext === 'png' ? bytes.subarray(0, 4).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47])) : ext === 'webp' ? bytes.toString('latin1', 0, 4) === 'RIFF' && bytes.toString('latin1', 8, 12) === 'WEBP' : bytes[0] === 0xff && bytes[1] === 0xd8;
        if (!real) return json(res, 400, { error: 'bad image' });
        if (ext === 'jpg') bytes = stripJpegMeta(bytes);
        const id = crypto.randomUUID().slice(0, 8);
        fs.mkdirSync(path.join(DATA, g.code, 'photos'), { recursive: true });
        fs.writeFileSync(path.join(DATA, g.code, 'photos', id + '.' + ext), bytes);
        const photo = { id, url: '/media/' + g.code + '/photos/' + id + '.' + ext, takenAt: Number(body.takenAt) || Date.now(), by: txt(body.by, 20) };
        g.photos.push(photo);
        g.photos.sort((a, b) => a.takenAt - b.takenAt);
        broadcast(g, { type: 'photo', photo }, { cars: false, full: true });
        return json(res, 200, photo);
      }
      if (sub === '/dashcam' && req.method === 'POST') {
        const max = MAX_UPLOAD_MB * 1024 * 1024;
        if (Number(req.headers['content-length']) > max) return failRequest(req, res, httpError(413, 'too large'));
        // 그룹마다 영상 작업은 하나만 (두 사람이 함께 올리면 뒤에 온 것은 409)
        if (g.job) return failRequest(req, res, httpError(409, 'busy'));
        g.job = { startedAt: Date.now() };
        const name = (url.searchParams.get('name') || 'dashcam.mp4').replace(/[^\w.\-]/g, '_').slice(0, 120);
        const file = path.join(DATA, g.code, 'cam-' + Date.now() + '-' + crypto.randomBytes(3).toString('hex') + '.upload');
        try { await streamUpload(req, file, max); } catch (e) { g.job = null; return failRequest(req, res, e); }
        const lastModified = Number(url.searchParams.get('lastModified')) || Date.now();
        g.highlight = { status: 'processing', startedAt: Date.now() };
        broadcast(g, { type: 'highlight', highlight: g.highlight }, { cars: false, full: true });
        makeHighlight(g, file, name, lastModified).catch(e => {
          console.warn('[highlight]', g.code, String(e.message || e).split('\n')[0].slice(0, 160));
          g.highlight = { status: 'error' };
          broadcast(g, { type: 'highlight', highlight: g.highlight }, { cars: false, full: true });
        }).finally(() => {
          // 캠 원본은 하이라이트를 만든 직후 지움 (성공이든 실패든)
          g.job = null;
          fs.rm(file, { force: true }, () => {});
          persist(g);
        });
        return json(res, 200, { ok: true });
      }
      if (sub === '/report' && req.method === 'POST') {
        const wait = reportLimit.take(ip + '|' + g.code);
        if (wait) return json(res, 429, { error: 'too many', retryIn: wait });
        const body = await readJson(req);
        const r = { placeId: txt(body.placeId, 60), name: txt(body.name, 60), note: txt(body.note, 500), by: txt(body.by, 20), at: Date.now() };
        if (!r.note.trim()) return json(res, 400, { error: 'empty' });
        capPush(g.reports, r, MAX.reports);
        fs.appendFileSync(path.join(DATA, g.code, 'reports.jsonl'), JSON.stringify(r) + '\n');
        broadcast(g, Object.assign({ type: 'report' }, r), { phones: false });
        return json(res, 200, { ok: true });
      }
      // 차가 실제로 읽은 문장과 시각 (시연 영상에 음성을 다시 입힐 때 씀). 차 키가 있어야 함
      if (sub === '/said' && req.method === 'GET') return slot || trusted ? json(res, 200, g.said || []) : json(res, 401, { error: 'car key required' });
      return json(res, 404, { error: 'unknown' });
    }

    m = p.match(/^\/api\/scenarios\/([a-z0-9_-]{1,40})$/);
    if (m) {
      const sc = scenarioEntry(m[1]);
      if (!sc) return json(res, 404, { error: 'not found' });
      res.writeHead(200, { 'Content-Type': MIME['.json'], 'Cache-Control': 'no-cache' });
      return res.end(sc.body);
    }

    // /media 는 사진과 하이라이트 영상만 연다 (group.json, record.json, 캠 원본 등은 열지 않음)
    m = p.match(/^\/media\/(\d{4})\/(photos\/[0-9a-f]{8}\.(?:jpg|png|webp)|highlight\.mp4)$/);
    if (m) {
      const g = groups.get(m[1]);
      const dir = groupDir(m[1]);
      const file = g && dir && inside(dir, m[2]);
      if (!file) { res.writeHead(404); return res.end('not found'); }
      // 사진과 영상은 QR(휴대폰 키)로 들어온 휴대폰과 차만 봄. <img>, <video> 는 머리글을 못 붙여 ?k= 로도 받음
      const k = req.headers['x-group-key'] || url.searchParams.get('k');
      if (!(k && sameKey(g.phoneKey, String(k))) && !carSlotByKey(g, req.headers['x-car-key']) && !localTrusted(req)) {
        if (k) lookupLimit.take(clientIp(req));
        res.writeHead(401); return res.end('key required');
      }
      return serveFile(res, file, req);
    }
    if (p.startsWith('/media/')) { res.writeHead(404); return res.end('not found'); }

    if (p === '/favicon.ico') { res.writeHead(204, { 'Cache-Control': 'public, max-age=86400' }); return res.end(); }
    if (p === '/' || p === '/index.html') return serveFile(res, path.join(WEB, 'index.html'), req);
    if (/^\/g\/\d{4}(\/record)?\/?$/.test(p)) return serveFile(res, path.join(WEB, 'group.html'), req);

    // 웹 폴더 밖, 점(.)으로 시작하는 파일, RENDER 가 아닐 때의 렌더 페이지는 열지 않음
    const rel = p.replace(/^\/+/, '');
    const file = rel && !rel.split('/').some(s => s.startsWith('.')) && inside(WEB, rel);
    if (!file || (!RENDER && (rel === 'render.html' || rel === 'js/render-page.js'))) { res.writeHead(404); return res.end('not found'); }
    return serveFile(res, file, req);
  } catch (e) {
    if (e && e.status) return failRequest(req, res, e);
    console.warn('[http]', req.method, e.message);
    if (!res.headersSent) json(res, 500, { error: 'server error' }); else res.destroy();
  }
});
server.requestTimeout = 30 * 60 * 1000;   // 큰 캠 영상을 느린 와이파이로 올려도 5분에 끊지 않게
server.on('error', e => { console.error('서버를 열지 못했어요:', e.message); process.exit(1); });

// ---------------------------------------------------------------- WebSocket
const wss = new WebSocketServer({ server, path: '/ws', maxPayload: 64 * 1024 });
wss.on('error', e => console.warn('[ws]', e.message));
// 30초마다 ping. 답이 없는 연결(터널에서 끊긴 휴대폰 등)은 정리해 인원 수가 맞게
setInterval(() => {
  for (const ws of wss.clients) {
    if (ws.isAlive === false) { ws.terminate(); continue; }
    ws.isAlive = false;
    try { ws.ping(); } catch {}
  }
}, 30 * 1000).unref();
wss.on('connection', (ws, req) => {
  // 소켓마다 error 를 받아 둠. 없으면 깨진 프레임 하나에 서버가 꺼짐
  ws.on('error', e => console.warn('[ws] 연결 오류', e.code || e.message));
  ws.isAlive = true;
  ws.on('pong', () => { ws.isAlive = true; });
  let url;
  try { url = new URL(req.url || '/', 'http://x'); } catch { return ws.close(4400, 'bad request'); }
  const code = url.searchParams.get('code') || '';
  if (!/^\d{4}$/.test(code)) { send(ws, { type: 'error', error: 'bad code' }); return ws.close(4400, 'bad code'); }
  const role = url.searchParams.get('role') === 'car' ? 'car' : 'phone';
  const ip = clientIp(req);
  const g = groups.get(code);
  const refuse = (closeCode, error, extra) => { send(ws, Object.assign({ type: 'error', error }, extra || {})); ws.close(closeCode, error); };
  let slot = null;
  if (role === 'car') {
    // 차는 그룹을 만들 때(POST /api/groups, /join) 받은 차 키로만 붙음. 예전처럼 WebSocket 으로 그룹을 만들지 않음
    if (!g) { lookupLimit.take(ip); return refuse(4404, 'no such group'); }
    const key = url.searchParams.get('key') || '';
    slot = carSlotByKey(g, key);
    if (!slot && !key && localTrusted(req)) {
      // 시연 녹화 도구가 키 없이 붙이는 차 (DEMO_LOCAL_TRUST=1 일 때만). 이름이 같으면 같은 차로 봄
      const asked = String(url.searchParams.get('name') || '').slice(0, 20);
      slot = g.carSlots.find(s => s.name === asked) || addCar(g);
      if (slot) persist(g);
    }
    if (!slot) { lookupLimit.take(ip); return refuse(4401, 'bad key'); }
  } else {
    // 휴대폰: QR 주소의 k 가 맞으면 full, 네 자리만 넣고 들어오면 basic (번호 조회 속도 제한)
    const k = url.searchParams.get('k') || '';
    if (k) {
      if (!g || !sameKey(g.phoneKey, k)) { lookupLimit.take(ip); return g ? refuse(4401, 'bad key') : refuse(4404, 'no such group'); }
      ws.level = 'full';
    } else {
      const wait = lookupWait(ip, code);
      if (wait) return refuse(4429, 'too many', { retryIn: wait });
      if (!g) return refuse(4404, 'no such group');
      lookupOk(ip, code);
      ws.level = localTrusted(req) ? 'full' : 'basic';
    }
  }
  const id = crypto.randomUUID();
  if (role === 'car') {
    // 같은 차가 다시 붙으면 예전 연결은 닫음 (같은 메시지가 두 번 가지 않게)
    for (const [oid, c] of g.cars) if (c.no === slot.no) { g.cars.delete(oid); try { c.ws.close(4000, 'replaced'); } catch {} }
    g.cars.set(id, { id, name: slot.name, no: slot.no, ws });
    if (!slot.seen) { slot.seen = true; persist(g); broadcast(g, { type: 'car_joined', name: slot.name, carNo: slot.no }, { except: ws }); }
  } else {
    // 휴대폰 구분 값(pid)은 서버가 정한다. 서버가 준 값으로 다시 붙으면 이어 쓰고(내 답 표시), 모르는 값이면 새로 줌
    let pid = url.searchParams.get('pid') || '';
    const fresh = !g.pidSet.has(pid);
    if (fresh) {
      const wait = pidLimit.take(ip + '|' + code);
      if (wait) return refuse(4429, 'too many', { retryIn: wait });
      pid = 'p' + crypto.randomBytes(9).toString('base64url');
      g.pidSet.add(pid);
      g.pids.push(pid);
      if (g.pids.length > 500) g.pidSet.delete(g.pids.shift());
      persist(g);
    }
    ws.pid = pid;
    g.phones.add(ws);
    if (fresh) broadcast(g, { type: 'phone_joined' }, { phones: false });
  }
  console.log('[ws]', code, role, role === 'car' ? slot.no : ws.level, 'cars', g.cars.size, 'phones', g.phones.size);
  const who = role === 'car' ? { role } : { role, level: ws.level, pid: ws.pid };
  const you = role === 'car' ? { carNo: slot.no, carName: slot.name } : { pid: ws.pid, level: ws.level };
  const hello = Object.assign({ type: 'hello', role, you }, summary(g, who));
  if (role === 'car') Object.assign(hello, { joinUrl: joinUrlFor(g, slot.no), recordUrl: recordUrlFor(g, slot.no) });
  send(ws, hello);
  members(g);

  ws.on('message', data => {
    let msg; try { msg = JSON.parse(String(data)); } catch { return; }
    // null, 숫자, 문자열, 배열은 버림 (예전에는 msg.type 에서 서버가 꺼졌음)
    if (!msg || typeof msg !== 'object' || Array.isArray(msg) || typeof msg.type !== 'string') return;
    try {
      if (role === 'car') { const car = g.cars.get(id); if (car) onCarMessage(g, car, msg); }
      else onPhoneMessage(g, ws, msg);
    } catch (e) { console.warn('[ws] 메시지 처리 실패', msg.type, e.message); }
  });
  ws.on('close', () => {
    if (role === 'car') g.cars.delete(id); else g.phones.delete(ws);
    members(g);
  });
});

// ---------------------------------------------------------------- 차·휴대폰 메시지
// 필드를 하나씩 검사하고(글자 수, 숫자 범위), 쌓이는 배열에는 상한을 둠
const MAX = { passes: 300, picks: 100, acks: 200, reports: 200, qa: 50, answers: 2000, quizzes: 100, said: 1000 };
const txt = (v, n) => (typeof v === 'string' ? v : typeof v === 'number' ? String(v) : '').replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/g, '').slice(0, n);
const num = (v, d) => (typeof v === 'number' && Number.isFinite(v) ? v : d);
const int = (v, lo, hi, d) => (Number.isInteger(v) ? Math.min(hi, Math.max(lo, v)) : d);
const lang2 = (v, d) => (v === 'en' || v === 'ko' ? v : d);
function capPush(arr, item, n) { arr.push(item); if (arr.length > n) arr.splice(0, arr.length - n); }

// 차가 올린 여행 기록: 모양을 검사하고, 차에 말로 물어본 문장과 신고 내용은 남기지 않음
function cleanRecord(b) {
  const route = (Array.isArray(b.route) ? b.route : []).slice(0, 20000)
    .filter(p => Array.isArray(p) && p.length >= 3 && p.slice(0, 3).every(v => typeof v === 'number' && Number.isFinite(v)) && Math.abs(p[1]) <= 90 && Math.abs(p[2]) <= 180)
    .map(p => [p[0], p[1], p[2]]);
  const events = (Array.isArray(b.events) ? b.events : []).slice(0, 5000).filter(e => e && typeof e === 'object').map(e => {
    const type = txt(e.type, 20);
    return { t: num(e.t, 0), type, placeId: txt(e.placeId, 60), title: type === 'ask' ? '' : txt(e.title, 80), detail: type === 'qa' || type === 'report' ? '' : txt(e.detail, 80) };
  });
  return { tripId: txt(b.tripId, 40), scenarioId: txt(b.scenarioId, 40), lang: lang2(b.lang, 'ko'), startedAt: num(b.startedAt, 0), endedAt: num(b.endedAt, Date.now()), distanceM: Math.max(0, Math.round(num(b.distanceM, 0))), route, events };
}

function onCarMessage(g, car, msg) {
  const now = Date.now(), type = msg.type;
  if (now - (car.winAt || 0) > 10000) { car.winAt = now; car.winN = 0; }
  if (++car.winN > 120) return;   // 10초에 120개 넘게 보내면 버림
  const tag = o => Object.assign(o, { car: car.name, carNo: car.no });
  if (type === 'story') {
    g.story = tag({ type, placeId: txt(msg.placeId, 60), name: txt(msg.name, 60), model: /^[a-z0-9_]{0,40}$/.test(msg.model || '') ? (msg.model || '') : '', kind: /^[a-z_]{1,16}$/.test(msg.kind || '') ? msg.kind : 'story', length: txt(msg.length, 12), lang: lang2(msg.lang, g.lang), text: txt(msg.text, 6000), rain: msg.rain === true, night: msg.night === true, color: txt(msg.color, 12), at: now, done: false });
    g.line = null;
    g.storyBy = g.storyBy || {}; g.lineBy = g.lineBy || {};
    g.storyBy[car.no] = g.story; g.lineBy[car.no] = null;
    broadcast(g, g.story, { cars: false });
  } else if (type === 'line') {
    g.line = tag({ type, placeId: txt(msg.placeId, 60), index: int(msg.index, 0, 999, 0), at: now });
    g.lineBy = g.lineBy || {}; g.lineBy[car.no] = g.line;
    broadcast(g, g.line, { cars: false });
  } else if (type === 'story_end') {
    const placeId = txt(msg.placeId, 60);
    // 다시 붙은 휴대폰이 끝난 이야기를 '지금 하는 중'으로 그리지 않게 저장해 둠
    for (const s of [g.story, g.storyBy && g.storyBy[car.no]]) if (s && s.placeId === placeId && (!s.carNo || s.carNo === car.no)) s.done = true;
    broadcast(g, tag({ type, placeId, at: now }), { cars: false });
  } else if (type === 'pass') {
    const ps = tag({ placeId: txt(msg.placeId, 60), name: txt(msg.name, 60), at: num(msg.at, now) });
    if (!ps.placeId) return;
    capPush(g.passes, ps, MAX.passes);
    broadcast(g, Object.assign({ type }, ps), { cars: false });
  } else if (type === 'trip') {
    if (typeof msg.scenarioId === 'string' && /^[a-z0-9_-]{1,40}$/.test(msg.scenarioId) && scenarioEntry(msg.scenarioId)) g.scenarioId = msg.scenarioId;
    g.lang = lang2(msg.lang, g.lang);
    broadcast(g, tag({ type, scenarioId: g.scenarioId, lang: g.lang, entered: txt(msg.entered, 600) }), { cars: false });
  } else if (type === 'pick') {
    // 먼저 도착한 차가 주차장 같은 곳을 다른 차에 보냄
    if (!(Math.abs(num(msg.lat, 999)) <= 90 && Math.abs(num(msg.lon, 999)) <= 180) || now - (car.lastShare || 0) < 3000) return;
    car.lastShare = now;
    const pick = tag({ type, id: txt(msg.id, 40) || crypto.randomUUID().slice(0, 8), placeId: txt(msg.placeId || msg.id, 60), name: txt(msg.name, 60), lat: msg.lat, lon: msg.lon, kind: txt(msg.kind, 16) || 'place', by: car.name, from: car.name, fromCar: true, at: now });
    capPush(g.picks, pick, MAX.picks);
    broadcast(g, pick, { except: car.ws });
  } else if (type === 'pick_ack') {
    const ack = tag({ type, id: txt(msg.id, 40), name: txt(msg.name, 60), mode: txt(msg.mode, 16), accepted: msg.accepted !== false && msg.mode !== 'later', at: now });
    if (!ack.id) return;
    if (g.pending && g.pending.id === ack.id) g.pending = null;   // 운전자가 답했으니 다음 장소를 받을 수 있음
    capPush(g.acks, ack, MAX.acks);
    broadcast(g, ack, { except: car.ws });
  } else if (type === 'say') {
    capPush(g.said, { at: now, text: txt(msg.text, 600), lang: lang2(msg.lang, g.lang), kind: txt(msg.kind, 20), car: car.name, carNo: car.no }, MAX.said);
  } else if (type === 'window' || type === 'trip_end') {
    if (type === 'trip_end') markEnded(g);   // 기록을 올리지 않고 끝내도 보관 기간을 셈
    broadcast(g, tag({ type, placeId: txt(msg.placeId, 60), open: msg.open !== false, at: now }), { cars: false });
  } else if (type === 'quiz') {
    // 차가 둘이면 같은 명소 퀴즈가 겹치지 않게 '차 번호:명소' 로 구분. 차에 돌려줄 때는 명소 id(placeId)로
    const placeId = txt(msg.id, 60), answer = msg.answer;
    const choices = Array.isArray(msg.choices) ? msg.choices.slice(0, 6).map(c => txt(c, 120)) : [];
    if (!placeId || choices.length < 2 || !Number.isInteger(answer) || answer < 0 || answer >= choices.length) return;
    const id = car.no + ':' + placeId;
    const q = { id, placeId, name: txt(msg.name, 60), q: txt(msg.q, 300), choices, answer, lang: lang2(msg.lang, g.lang), at: now, car: car.name, carNo: car.no };
    delete g.quizzes[id];   // 다시 내면 맨 뒤로 (오래된 것부터 지울 때 순서가 맞게)
    g.quizzes[id] = q;
    const ids = Object.keys(g.quizzes);
    if (ids.length > MAX.quizzes) delete g.quizzes[ids[0]];
    broadcast(g, Object.assign({ type, stars: int(msg.stars, 0, 50, 0), need: int(msg.need, 0, 50, 0) }, publicQuiz(q)), { cars: false });
  } else if (type === 'qa') {
    const item = tag({ q: txt(msg.q, 200), a: txt(msg.a, 1200), title: txt(msg.title, 60), placeId: txt(msg.placeId, 60), at: num(msg.at, now) });
    if (!item.a) return;
    capPush(g.qa, item, MAX.qa);
    broadcast(g, Object.assign({ type }, item), { cars: false, full: true });
  } else if (type === 'handoff') {
    g.handoff = tag({ placeId: txt(msg.placeId, 60), name: txt(msg.name, 60), text: txt(msg.text, 6000), lang: lang2(msg.lang, g.lang), at: now });
    broadcast(g, Object.assign({ type }, g.handoff), { cars: false });
  } else if (type === 'treasure') {
    g.treasure = tag({ stars: int(msg.stars, 0, 50, 0), unlocked: msg.unlocked === true, at: now });
    broadcast(g, Object.assign({ type }, g.treasure), { cars: false });
  } else if (type === 'stars') {
    g.stars = tag({ stars: int(msg.stars, 0, 50, 0), need: int(msg.need, 0, 50, 0), ready: msg.ready === true, by: txt(msg.by, 20), at: now });
    broadcast(g, Object.assign({ type }, g.stars), { cars: false });
  } else if (type === 'kid') {
    const kid = tag({ type, placeId: txt(msg.placeId, 60), text: txt(msg.text, 800), at: now });
    if (kid.text) broadcast(g, kid, { cars: false });
  }
}

// 운전자 확인을 기다리는 장소는 그룹에 하나. 답이 없어도 90초 뒤에는 다음 장소를 받음
const PICK_HOLD_MS = 90 * 1000;

/** 휴대폰이 고른 곳. 이름과 위치는 시나리오에서 서버가 채움(휴대폰이 보낸 이름은 쓰지 않음). 지도에서 찍은 곳은 코스 근처(약 30km)만 */
function pickPlace(g, msg) {
  const sc = scenarioEntry(g.scenarioId);
  const data = (sc && sc.data) || {};
  if (msg.custom === true) {
    const lat = num(msg.lat, NaN), lon0 = num(msg.lon, NaN);
    if (!(Math.abs(lat) <= 90) || !Number.isFinite(lon0)) return null;
    const lon = ((lon0 + 180) % 360 + 360) % 360 - 180;
    const pts = (Array.isArray(data.route) ? data.route : []).map(p => [p[0], p[1]]).concat((data.places || []).map(p => [p.lat, p.lon]));
    if (pts.length && !pts.some(p => Math.abs(p[0] - lat) < 0.3 && Math.abs(p[1] - lon) < 0.4)) return null;
    return { placeId: '', name: g.lang === 'en' ? 'Spot picked on the map' : '지도에서 고른 곳', lat: Math.round(lat * 1e6) / 1e6, lon: Math.round(lon * 1e6) / 1e6, kind: 'place', custom: true };
  }
  const id = txt(msg.placeId, 60);
  const p = id && [].concat(data.pickables || [], (data.places || []).filter(x => x.kind !== 'city')).find(x => x && x.id === id);
  if (!p || !Number.isFinite(p.lat) || !Number.isFinite(p.lon)) return null;
  const name = p.name && typeof p.name === 'object' ? (p.name[g.lang] || p.name.ko || p.name.en || '') : String(p.name || '');
  return { placeId: p.id, name: txt(name, 60), lat: p.lat, lon: p.lon, kind: txt(p.kind, 16) || 'place' };
}

function onPhoneMessage(g, ws, msg) {
  const now = Date.now();
  if (now - (ws.winAt || 0) > 10000) { ws.winAt = now; ws.winN = 0; }
  if (++ws.winN > 30) return;   // 10초에 30개 넘게 보내면 버림
  if (msg.type === 'pick') {
    // 못 보낼 때는 보낸 휴대폰에만 까닭을 알림: bad(모르는 곳), nocar(차 없음), soon(20초 안에 또), busy(다른 곳 확인 중)
    const refuse = (reason, extra) => send(ws, Object.assign({ type: 'pick_wait', reason }, extra || {}));
    const place = pickPlace(g, msg);
    if (!place) return refuse('bad');
    if (!g.cars.size) return refuse('nocar');
    g.pickAt = g.pickAt || new Map();
    const left = 20000 - (now - (g.pickAt.get(ws.pid) || 0));
    if (left > 0) return refuse('soon', { retryIn: Math.ceil(left / 1000) });
    if (g.pending && now - g.pending.at < PICK_HOLD_MS) return refuse('busy', { name: g.pending.name, retryIn: Math.ceil((PICK_HOLD_MS - (now - g.pending.at)) / 1000) });
    g.pickAt.set(ws.pid, now);
    const by = txt(msg.by, 20).trim() || (g.lang === 'en' ? 'Passenger' : '동승자');
    const pick = Object.assign({ type: 'pick', id: crypto.randomBytes(4).toString('hex') }, place, { by, fromCar: false, at: now });
    g.pending = { id: pick.id, name: pick.name, at: now };
    capPush(g.picks, pick, MAX.picks);
    persist(g);
    for (const c of g.cars.values()) send(c.ws, pick);
    for (const p of g.phones) send(p, Object.assign({ mine: p === ws }, pick));
  } else if (msg.type === 'quiz_answer') {
    // 채점은 서버가 한다. pid 는 서버가 정한 값만 쓰고, 휴대폰 한 대가 같은 퀴즈에 한 번만 답할 수 있다
    const q = g.quizzes[txt(msg.id, 80)];
    const pid = ws.pid, choice = msg.choice;
    if (!q || g.answers.some(a => a.id === q.id && a.pid === pid)) return;
    if (!Number.isInteger(choice) || choice < 0 || choice >= q.choices.length) return;
    const a = { id: q.id, by: txt(msg.by, 20) || '동승자', pid, choice, correct: choice === q.answer, at: now };
    capPush(g.answers, a, MAX.answers);
    persist(g);
    console.log('[quiz]', g.code, q.id, a.correct ? '정답' : '오답');
    // 정답은 답한 휴대폰에만. 다른 휴대폰에는 누가 맞혔는지만, 퀴즈를 낸 차에는 맞혔는지만
    const mine = Object.assign({ type: 'quiz_answer' }, publicAnswer(g, a, pid));
    const others = Object.assign({ type: 'quiz_answer' }, publicAnswer(g, a, null));
    for (const p of g.phones) send(p, p.pid === pid ? mine : others);
    for (const c of g.cars.values()) if (!q.carNo || c.no === q.carNo) send(c.ws, { type: 'quiz_answer', id: q.placeId || q.id, by: a.by, correct: a.correct, at: a.at });
  }
}

// ---------------------------------------------------------------- 빌트인 캠 하이라이트
function run(cmd, args) {
  return new Promise((resolve, reject) => {
    const p = spawn(cmd, args);
    let out = '', err = '';
    p.stdout.on('data', d => out += d);
    p.stderr.on('data', d => err += d);
    p.on('close', code => code === 0 ? resolve(out) : reject(new Error(cmd + ' failed: ' + err.slice(-400))));
    p.on('error', reject);
  });
}

function timeFromName(name) {
  // 블랙박스 파일 이름에 흔한 20261109_131530 같은 시각을 읽는다
  const m = /(20\d{2})(\d{2})(\d{2})[_-]?(\d{2})(\d{2})(\d{2})/.exec(name);
  if (!m) return null;
  return new Date(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +m[6]).getTime();
}

async function makeHighlight(g, file, name, lastModified) {
  // 올린 파일은 MP4/MOV 로만 읽음 (-f mov). 재생 목록 같은 파일로 다른 파일을 읽지 못하게 -protocol_whitelist file
  const probe = JSON.parse(await run('ffprobe', ['-v', 'error', '-protocol_whitelist', 'file', '-f', 'mov', '-show_entries', 'format=duration', '-of', 'json', file]));
  const duration = Number(probe.format.duration);
  const start = timeFromName(name) || (lastModified - duration * 1000);
  const passes = (g.record ? g.record.events.filter(e => e.type === 'pass').map(e => ({ at: e.t, title: e.title, placeId: e.placeId })) : g.passes.map(p => ({ at: p.at, title: p.name, placeId: p.placeId })))
    .filter(p => !/^(경주|제주|여의도|Gyeongju|Jeju|Yeouido)$/.test(p.title));
  let clips = passes.map(p => ({ title: p.title, t: (p.at - start) / 1000 })).filter(c => c.t >= 0 && c.t <= duration);
  let mode = 'time';
  if (clips.length === 0 && passes.length) {
    // 시각이 맞지 않으면(시연 영상 등) 지나온 순서대로 영상을 나눠 붙인다
    mode = 'order';
    clips = passes.map((p, i) => ({ title: p.title, t: duration * (i + 0.5) / passes.length }));
  }
  if (clips.length === 0) clips = [{ title: '', t: duration / 2 }];
  const seg = Math.min(6, Math.max(2.5, duration / Math.max(clips.length, 1) / 1.2));
  const out = path.join(DATA, g.code, 'highlight.mp4');
  const part = path.join(DATA, g.code, 'highlight.part.mp4');
  const args = ['-y', '-protocol_whitelist', 'file', '-f', 'mov', '-i', file];
  const parts = [];
  const esc = s => s.replace(/\\/g, '\\\\').replace(/:/g, '\\:').replace(/'/g, "\u2019");
  // 자막: ffmpeg drawtext 가 있으면 그대로 쓰고, 없으면 Pillow 로 만든 투명 PNG 를 겹친다
  const captions = [];
  if (!HAS_DRAWTEXT && HAS_PIL && FONT) {
    for (let i = 0; i < clips.length; i++) {
      if (!clips[i].title) continue;
      const png = path.join(DATA, g.code, 'cap-' + i + '.png');
      try { await run('python3', [path.join(HERE, 'tools', 'caption_png.py'), clips[i].title, FONT, png]); captions[i] = png; } catch (e) { console.warn('[caption]', e.message); }
    }
    captions.forEach(p => { if (p) args.push('-loop', '1', '-i', p); });
  }
  let capInput = 1;
  clips.forEach((c, i) => {
    const s = Math.max(0, Math.min(duration - seg, c.t - seg / 2));
    let f = '[0:v]trim=start=' + s.toFixed(2) + ':duration=' + seg.toFixed(2) + ',setpts=PTS-STARTPTS,scale=1280:720:force_original_aspect_ratio=decrease,pad=1280:720:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=30';
    if (HAS_DRAWTEXT && FONT && c.title) {
      f += ",drawtext=fontfile='" + FONT + "':expansion=none:text='" + esc(c.title) + "':x=48:y=h-110:fontsize=54:fontcolor=white:box=1:boxcolor=black@0.45:boxborderw=18";
      parts.push(f + '[v' + i + ']');
    } else if (captions[i]) {
      parts.push(f + '[b' + i + ']');
      parts.push('[b' + i + '][' + (capInput++) + ':v]overlay=0:0:shortest=1[v' + i + ']');
    } else parts.push(f + '[v' + i + ']');
  });
  const filter = parts.join(';') + ';' + clips.map((_, i) => '[v' + i + ']').join('') + 'concat=n=' + clips.length + ':v=1:a=0[out]';
  // 같은 노트북의 에뮬레이터가 버벅이지 않게 스레드 둘만. 다 만든 뒤에 이름을 바꿔 덜 된 파일이 보이지 않게 함
  args.push('-filter_complex', filter, '-map', '[out]', '-c:v', 'libx264', '-preset', 'veryfast', '-crf', '23', '-threads', '2', '-pix_fmt', 'yuv420p', '-movflags', '+faststart', part);
  try {
    await run('ffmpeg', args);
    fs.renameSync(part, out);
  } finally {
    for (const f of [part, ...captions.filter(Boolean)]) fs.rm(f, { force: true }, () => {});
  }
  g.highlight = { status: 'done', url: '/media/' + g.code + '/highlight.mp4?v=' + Date.now(), clips: clips.map(c => c.title), mode };
  broadcast(g, { type: 'highlight', highlight: g.highlight }, { cars: false, full: true });
  console.log('[highlight] done', g.code, clips.length, mode);
}

server.listen(PORT, '0.0.0.0', () => {
  console.log('구르는 가이드 그룹 서버: ' + PUBLIC_BASE + ' (에뮬레이터에서는 http://10.0.2.2:' + PORT + ')');
});

// ffmpeg 자막 기능 확인
run('ffmpeg', ['-hide_banner', '-filters']).then(out => { HAS_DRAWTEXT = /\bdrawtext\b/.test(out); }).catch(() => console.warn('ffmpeg 가 없어 하이라이트 영상을 만들 수 없어요'));
run('python3', ['-c', 'import PIL']).then(() => { HAS_PIL = true; }).catch(() => {});

// 마지막 안전망: 처리하지 못한 예외가 있어도 서버 전체가 꺼지지 않게 기록만 남김
process.on('uncaughtException', e => console.error('[fatal]', e && e.message));
process.on('unhandledRejection', e => console.error('[fatal]', e && e.message));

// 끌 때(Ctrl+C, kill) 아직 쓰지 않은 그룹을 바로 저장하고 끝냄. 다시 켜면 이야기·퀴즈가 이어짐
function flushAndExit() {
  for (const [code, timer] of saveTimers) { clearTimeout(timer); const g = groups.get(code); if (g) writeGroup(g); }
  saveTimers.clear();
  process.exit(0);
}
process.on('SIGINT', flushAndExit);
process.on('SIGTERM', flushAndExit);
