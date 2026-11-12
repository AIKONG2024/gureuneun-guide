// 구르는 가이드 여행 그룹 서버
// 차 앱과 동승자 휴대폰(웹)을 네 자리 그룹 번호로 묶는다.
//  - 차 → 휴대폰: 이야기(시작, 읽는 문장, 끝), 아이 이야기, 지난 명소, 퀴즈와 별, 차에 말로 물어본 것의 답
//  - 휴대폰 → 차: 고른 장소(운전자가 확인), 퀴즈 답(채점은 서버가 함)
//  - 여행 뒤: 차가 올린 기록(지나온 길, 지난 명소)
// 차의 실시간 위치는 받지 않는다.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import zlib from 'node:zlib';
import { pipeline } from 'node:stream';
import { fileURLToPath } from 'node:url';
import { WebSocketServer } from 'ws';

const PORT = Number(process.env.PORT || 8787);
const HERE = path.dirname(fileURLToPath(import.meta.url));
const WEB = path.join(HERE, 'web');
const PHOTOS = path.join(WEB, 'photos');
const SCENARIOS = path.join(HERE, '..', 'app', 'src', 'main', 'assets', 'scenarios');
// 시험할 때는 DATA_DIR 로 임시 폴더를 줘서 실제 data 폴더를 건드리지 않음
const DATA = path.resolve(process.env.DATA_DIR || path.join(HERE, 'data'));

function lanIp() {
  for (const list of Object.values(os.networkInterfaces())) {
    for (const a of list || []) if (a.family === 'IPv4' && !a.internal) return a.address;
  }
  return '127.0.0.1';
}
const PUBLIC_BASE = process.env.PUBLIC_BASE || ('http://' + lanIp() + ':' + PORT);
const CODE_RE = /^\d{4}$/;
// 끝난 그룹은 KEEP_DAYS(기본 3일) 뒤 폴더째 지움. 0 이나 off 면 지우지 않음
const KEEP_DAYS = process.env.KEEP_DAYS === undefined || process.env.KEEP_DAYS === '' ? 3 : (Number(process.env.KEEP_DAYS) > 0 ? Number(process.env.KEEP_DAYS) : 0);

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
const okPairs = new Map();                           // 'ip|번호' → 이미 맞힌 번호 (같은 휴대폰이 다시 붙을 때는 세지 않음)
function lookupWait(ip, code) {
  const exp = okPairs.get(ip + '|' + code);
  return exp && exp > Date.now() ? 0 : lookupLimit.take(ip);
}
const lookupOk = (ip, code) => okPairs.set(ip + '|' + code, Date.now() + 6 * 3600 * 1000);
// 속도 제한 기록은 10분마다 정리 (오래 켜 둬도 메모리가 늘지 않게)
setInterval(() => {
  for (const l of [pidLimit, lookupLimit, joinLimit, createLimit]) l.sweep();
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

// 코스 파일은 차 앱의 것을 그대로 읽어 휴대폰에도 줌 (파일이 바뀌면 다시 읽음)
const scenarioCache = new Map();
function scenarioEntry(id) {
  const file = path.join(SCENARIOS, id + '.json');
  let st;
  try { st = fs.statSync(file); } catch { return null; }
  const hit = scenarioCache.get(id);
  if (hit && hit.mtimeMs === st.mtimeMs && hit.size === st.size) return hit;
  try {
    const data = JSON.parse(fs.readFileSync(file, 'utf8'));
    const entry = { mtimeMs: st.mtimeMs, size: st.size, data, body: JSON.stringify(data) };
    scenarioCache.set(id, entry);
    return entry;
  } catch (e) { console.warn('[scenario]', id, e.message); return null; }
}

// 명소 사진 목록: web/photos 의 '<명소id>_<번호>.jpg', 밤 사진은 '<명소id>_night_<번호>.jpg'.
// 찍은 사람과 라이선스(credits.json)를 함께 줌. 폴더가 바뀌면 다시 읽음
let photoCache = null;
function photoIndex() {
  const creditFile = path.join(PHOTOS, 'credits.json');
  let stamp = '';
  try { stamp = fs.statSync(PHOTOS).mtimeMs + ':' + (fs.existsSync(creditFile) ? fs.statSync(creditFile).mtimeMs : 0); } catch { return '{"places":{},"credits":{}}'; }
  if (photoCache && photoCache.stamp === stamp) return photoCache.body;
  const places = {}, credits = {};
  const found = [];
  for (const name of fs.readdirSync(PHOTOS)) {
    const m = /^([a-z0-9_]+?)(_night)?_(\d+)\.jpg$/.exec(name);
    if (m) found.push({ id: m[1], night: !!m[2], n: Number(m[3]), name });
  }
  found.sort((a, b) => a.n - b.n);
  for (const f of found) {
    const p = places[f.id] || (places[f.id] = { day: [], night: [] });
    (f.night ? p.night : p.day).push(f.name);
  }
  try {
    for (const c of JSON.parse(fs.readFileSync(creditFile, 'utf8'))) {
      if (c && typeof c.file === 'string') credits[c.file] = { title: txt(c.title, 200), author: txt(c.author, 120), license: txt(c.license, 60), license_url: txt(c.license_url, 300), source: txt(c.source, 300) };
    }
  } catch {}
  photoCache = { stamp, body: JSON.stringify({ places, credits }) };
  return photoCache.body;
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
    story: null, line: null, passes: [], picks: [], acks: [], said: [],
    record: null, ended: false,
    qa: [], hunt: null, found: [], photos: [], kid: false,
    pids: [], pidSet: new Set(),
  };
  groups.set(code, g);
  fs.mkdirSync(dir, { recursive: true });
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
// 번호만 넣고 들어온 휴대폰(basic)에는 지나온 길과 차에 물어본 것을 주지 않음
function summary(g, who = {}) {
  const full = who.role === 'car' || who.level === 'full';
  const s = {
    code: g.code, scenarioId: g.scenarioId, lang: g.lang, publicBase: PUBLIC_BASE, access: full ? 'full' : 'basic',
    cars: [...g.cars.values()].map(c => c.name), phones: g.phones.size,
    story: g.story, line: g.line, passes: g.passes, ended: g.ended,
    // 차가 둘이면 휴대폰은 QR 의 car=N 으로 자기 차 이야기만 따름. 다시 붙을 때 차마다 마지막 이야기를 그리게 함께 줌
    stories: Object.values(g.storyBy || {}), lines: Object.values(g.lineBy || {}).filter(Boolean),
    hunt: g.hunt, found: g.found, kid: !!g.kid,
    keepDays: KEEP_DAYS,   // 휴대폰의 'N일 뒤 지워져요' 안내에 씀 (0 이면 지우지 않음)
  };
  if (full) Object.assign(s, { record: g.record, qa: g.qa.slice(-20), photos: g.photos });
  return s;
}

// 서버를 다시 켜도 여행 그룹이 이어지도록 디스크에 저장해 둔다
const saveTimers = new Map();
function writeGroup(g) {
  // group.json 에는 퀴즈 정답과 키가 들어 있음. 자료 폴더는 웹으로 열리지 않음
  if (groups.get(g.code) !== g) return;   // 그사이 지운 그룹은 다시 쓰지 않음
  const keep = { code: g.code, scenarioId: g.scenarioId, lang: g.lang, createdAt: g.createdAt, phoneKey: g.phoneKey, carSlots: g.carSlots, story: g.story, storyBy: g.storyBy, passes: g.passes, picks: g.picks, acks: g.acks, record: g.record, ended: g.ended, endedAt: g.endedAt, qa: g.qa, found: g.found, photos: g.photos, kid: !!g.kid, pids: g.pids };
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
      // 예전 그룹 파일에 남아 있을 수 있는 값(지금은 없는 기능)은 버림
      for (const old of ['reports', 'highlight', 'handoff', 'treasure', 'quizzes', 'answers', 'stars']) delete g[old];
      g.found = Array.isArray(g.found) ? g.found : [];
      g.photos = Array.isArray(g.photos) ? g.photos.filter(p => p && typeof p.url === 'string') : [];
      g.hunt = null;
      g.pids = Array.isArray(g.pids) ? g.pids : [];
      g.pidSet = new Set(g.pids);
      if (!Array.isArray(g.carSlots)) g.carSlots = [];
      if (!s.phoneKey) persist(g);   // 예전 그룹에는 키가 없어 새로 만든 키를 저장
      // 예전 그룹은 끝난 시각이 없음: 기록의 끝난 시각, 없으면 파일 시각으로
      if (g.ended && !g.endedAt) g.endedAt = Number(g.record && g.record.endedAt) || fs.statSync(f).mtimeMs;
    } catch (e) { console.warn('[restore]', code, e.message); }
  }
  if (groups.size) console.log('[group] 이전 그룹 ' + groups.size + '개를 불러왔어요');
}
restoreGroups();

const markEnded = g => { g.ended = true; g.endedAt = Date.now(); };

// 올린 사진에서 찍은 위치와 기기 정보(EXIF, 주석)를 떼어 냄
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

// 끝난 지 KEEP_DAYS 가 지난 그룹은 폴더째 지움. 차가 붙어 있으면 미룸
function sweepOld() {
  if (!(KEEP_DAYS > 0)) return;
  const limit = KEEP_DAYS * 24 * 3600 * 1000, now = Date.now();
  for (const g of [...groups.values()]) {
    if (!g.ended || !g.endedAt || now - g.endedAt < limit || g.cars.size) continue;
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

// full: QR(휴대폰 키)로 들어온 휴대폰에만 보냄 (지나온 길, 물어본 것)
function broadcast(g, obj, { cars = true, phones = true, except = null, full = false } = {}) {
  persist(g);
  if (cars) for (const c of g.cars.values()) if (c.ws !== except) send(c.ws, obj);
  if (phones) for (const p of g.phones) if (p !== except && (!full || p.level === 'full')) send(p, obj);
}

function members(g) {
  broadcast(g, { type: 'members', cars: [...g.cars.values()].map(c => c.name), phones: g.phones.size });
}

// ---------------------------------------------------------------- HTTP
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json; charset=utf-8', '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.svg': 'image/svg+xml', '.txt': 'text/plain; charset=utf-8', '.webp': 'image/webp', '.woff2': 'font/woff2', '.ttf': 'font/ttf', '.woff': 'font/woff' };

function json(res, code, obj) {
  // CORS 머리글은 두지 않음 (휴대폰 페이지는 같은 주소, 차는 OkHttp 라 필요 없음)
  res.writeHead(code, { 'Content-Type': MIME['.json'], 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(obj));
}

const gzCache = new Map();   // 웹 폴더 글자 파일의 gzip 결과 (파일이 바뀌면 다시 만듦)
function serveFile(res, file, req) {
  fs.stat(file, (err, st) => {
    try {
      if (err || !st.isFile()) { res.writeHead(404); return res.end('not found'); }
      const type = MIME[path.extname(file).toLowerCase()] || 'application/octet-stream';
      // 한 번 받은 파일은 다시 받지 않게(304). 글꼴·지도 라이브러리와 명소 사진은 1시간 동안 그대로 씀
      const lastMod = st.mtime.toUTCString();
      const cache = /[\\/](vendor|photos)[\\/]/.test(file) && !file.endsWith('.json') ? 'public, max-age=3600' : 'no-cache';
      if (req && req.headers['if-modified-since'] === lastMod) { res.writeHead(304, { 'Last-Modified': lastMod, 'Cache-Control': cache }); return res.end(); }
      // 글자 파일은 gzip 으로 보냄
      if (req && st.size > 1024 && /^(text\/|application\/json|image\/svg)/.test(type) && /\bgzip\b/.test(req.headers['accept-encoding'] || '')) {
        let hit = gzCache.get(file);
        if (!hit || hit.mtimeMs !== st.mtimeMs) { hit = { mtimeMs: st.mtimeMs, body: zlib.gzipSync(fs.readFileSync(file)) }; gzCache.set(file, hit); }
        res.writeHead(200, { 'Content-Type': type, 'Content-Encoding': 'gzip', 'Content-Length': hit.body.length, 'Cache-Control': cache, 'Last-Modified': lastMod, 'Vary': 'Accept-Encoding' });
        return res.end(req.method === 'HEAD' ? undefined : hit.body);
      }
      res.writeHead(200, { 'Content-Type': type, 'Content-Length': st.size, 'Cache-Control': cache, 'Last-Modified': lastMod });
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

/** 오류 응답을 보내고, 본문을 다 받지 않은 요청이면 남은 본문을 버리다가 5초 뒤 끊음 (큰 본문을 끝까지 받지 않게).
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

const server = http.createServer(async (req, res) => {
  try {
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('Referrer-Policy', 'strict-origin-when-cross-origin');
    // 주소가 깨져 있으면(%, // 등) 400. 예전에는 try 밖이라 서버가 통째로 꺼졌음
    let url, p;
    try { url = new URL(req.url || '/', 'http://x'); p = decodeURIComponent(url.pathname); } catch { return json(res, 400, { error: 'bad url' }); }
    if (p.includes('\0')) return json(res, 400, { error: 'bad url' });
    if (req.method === 'OPTIONS') { res.writeHead(204, { Allow: 'GET, HEAD, POST' }); return res.end(); }

    if (p === '/api/info') return json(res, 200, { ok: true, version: 3, publicBase: PUBLIC_BASE });

    if (p === '/api/photos' && (req.method === 'GET' || req.method === 'HEAD')) {
      res.writeHead(200, { 'Content-Type': MIME['.json'], 'Cache-Control': 'no-cache' });
      return res.end(req.method === 'HEAD' ? undefined : photoIndex());
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
      // 함께 탄 사람이 올린 사진. 여행 영상에 들어감. QR 로 들어온 휴대폰만 올릴 수 있음
      if (sub === '/photos' && req.method === 'POST') {
        if (slot || !(keyOk || trusted)) return failRequest(req, res, httpError(403, 'qr needed'));
        if (g.photos.length >= MAX.photos) return failRequest(req, res, httpError(409, 'too many photos'));
        const body = await readJson(req, 8 * 1024 * 1024);
        const mm = /^data:image\/(jpeg|png|webp);base64,([A-Za-z0-9+/=]+)$/.exec(typeof body.dataUrl === 'string' ? body.dataUrl : '');
        if (!mm) return json(res, 400, { error: 'bad image' });
        const ext = mm[1] === 'jpeg' ? 'jpg' : mm[1];
        let bytes = Buffer.from(mm[2], 'base64');
        // 이름표(data:image/png)만 보지 않고 파일 앞부분으로 진짜 그림인지 확인
        const real = ext === 'png' ? bytes.subarray(0, 4).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47]))
          : ext === 'webp' ? bytes.toString('latin1', 0, 4) === 'RIFF' && bytes.toString('latin1', 8, 12) === 'WEBP'
          : bytes[0] === 0xff && bytes[1] === 0xd8;
        if (!real || bytes.length > 5 * 1024 * 1024) return json(res, 400, { error: 'bad image' });
        if (ext === 'jpg') bytes = stripJpegMeta(bytes);
        const id = crypto.randomBytes(4).toString('hex');
        fs.mkdirSync(path.join(DATA, g.code, 'photos'), { recursive: true });
        fs.writeFileSync(path.join(DATA, g.code, 'photos', id + '.' + ext), bytes);
        const photo = { id, url: '/media/' + g.code + '/photos/' + id + '.' + ext, takenAt: num(body.takenAt, Date.now()), by: txt(body.by, 20) };
        g.photos.push(photo);
        g.photos.sort((a, b) => a.takenAt - b.takenAt);
        persist(g);
        broadcast(g, { type: 'photo', photo, count: g.photos.length }, { full: true });
        return json(res, 200, photo);
      }
      // 차가 실제로 읽은 문장과 시각 (시연 영상에 음성을 다시 입힐 때 씀). 차 키가 있어야 함
      if (sub === '/said' && req.method === 'GET') return slot || trusted ? json(res, 200, g.said || []) : json(res, 401, { error: 'car key required' });
      // 모르는 주소는 본문을 끝까지 받지 않고 404
      return failRequest(req, res, httpError(404, 'unknown'));
    }

    // /media 는 올린 사진만 연다 (group.json 같은 다른 파일은 열지 않음)
    m = p.match(/^\/media\/(\d{4})\/(photos\/[0-9a-f]{8}\.(?:jpg|png|webp))$/);
    if (m) {
      const g = groups.get(m[1]);
      const dir = groupDir(m[1]);
      const file = g && dir && inside(dir, m[2]);
      if (!file) { res.writeHead(404); return res.end('not found'); }
      // 사진은 QR(휴대폰 키)로 들어온 휴대폰과 차만 봄. <img> 는 머리글을 못 붙여 ?k= 로도 받음
      const k = req.headers['x-group-key'] || url.searchParams.get('k');
      if (!(k && sameKey(g.phoneKey, String(k))) && !carSlotByKey(g, req.headers['x-car-key'] || url.searchParams.get('ck')) && !localTrusted(req)) {
        if (k) lookupLimit.take(clientIp(req));
        res.writeHead(401); return res.end('key required');
      }
      return serveFile(res, file, req);
    }
    if (p.startsWith('/media/')) { res.writeHead(404); return res.end('not found'); }

    m = p.match(/^\/api\/scenarios\/([a-z0-9_-]{1,40})$/);
    if (m) {
      const sc = scenarioEntry(m[1]);
      if (!sc) return json(res, 404, { error: 'not found' });
      res.writeHead(200, { 'Content-Type': MIME['.json'], 'Cache-Control': 'no-cache' });
      return res.end(sc.body);
    }

    if (p === '/favicon.ico') { res.writeHead(204, { 'Cache-Control': 'public, max-age=86400' }); return res.end(); }
    if (p === '/' || p === '/index.html') return serveFile(res, path.join(WEB, 'index.html'), req);
    if (/^\/g\/\d{4}(\/record)?\/?$/.test(p)) return serveFile(res, path.join(WEB, 'group.html'), req);

    // 웹 폴더 밖과 점(.)으로 시작하는 파일은 열지 않음
    const rel = p.replace(/^\/+/, '');
    const file = rel && !rel.split('/').some(s => s.startsWith('.')) && inside(WEB, rel);
    if (!file) { res.writeHead(404); return res.end('not found'); }
    return serveFile(res, file, req);
  } catch (e) {
    if (e && e.status) return failRequest(req, res, e);
    console.warn('[http]', req.method, e.message);
    if (!res.headersSent) json(res, 500, { error: 'server error' }); else res.destroy();
  }
});
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
const MAX = { passes: 300, picks: 100, acks: 200, qa: 50, said: 1000, photos: 60 };
const STICKER_RE = /^[a-z0-9_]{1,24}$/;
const txt = (v, n) => (typeof v === 'string' ? v : typeof v === 'number' ? String(v) : '').replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/g, '').slice(0, n);
const num = (v, d) => (typeof v === 'number' && Number.isFinite(v) ? v : d);
const int = (v, lo, hi, d) => (Number.isInteger(v) ? Math.min(hi, Math.max(lo, v)) : d);
const lang2 = (v, d) => (v === 'en' || v === 'ko' ? v : d);
function capPush(arr, item, n) { arr.push(item); if (arr.length > n) arr.splice(0, arr.length - n); }

// 차가 올린 여행 기록: 모양을 검사하고, 차에 말로 물어본 문장과 답은 남기지 않음
function cleanRecord(b) {
  const route = (Array.isArray(b.route) ? b.route : []).slice(0, 20000)
    .filter(p => Array.isArray(p) && p.length >= 3 && p.slice(0, 3).every(v => typeof v === 'number' && Number.isFinite(v)) && Math.abs(p[1]) <= 90 && Math.abs(p[2]) <= 180)
    .map(p => [p[0], p[1], p[2]]);
  const events = (Array.isArray(b.events) ? b.events : []).slice(0, 5000).filter(e => e && typeof e === 'object').map(e => {
    const type = txt(e.type, 20);
    return { t: num(e.t, 0), type, placeId: txt(e.placeId, 60), title: type === 'ask' ? '' : txt(e.title, 80), detail: type === 'qa' ? '' : txt(e.detail, 80) };
  });
  return { tripId: txt(b.tripId, 40), scenarioId: txt(b.scenarioId, 40), lang: lang2(b.lang, 'ko'), startedAt: num(b.startedAt, 0), endedAt: num(b.endedAt, Date.now()), distanceM: Math.max(0, Math.round(num(b.distanceM, 0))), route, events };
}

function onCarMessage(g, car, msg) {
  const now = Date.now(), type = msg.type;
  if (now - (car.winAt || 0) > 10000) { car.winAt = now; car.winN = 0; }
  if (++car.winN > 120) return;   // 10초에 120개 넘게 보내면 버림
  const tag = o => Object.assign(o, { car: car.name, carNo: car.no });
  if (type === 'story') {
    g.story = tag({ type, placeId: txt(msg.placeId, 60), name: txt(msg.name, 60), kind: /^[a-z_]{1,16}$/.test(msg.kind || '') ? msg.kind : 'story', length: txt(msg.length, 12), lang: lang2(msg.lang, g.lang), text: txt(msg.text, 6000), rain: msg.rain === true, night: msg.night === true, color: txt(msg.color, 12), at: now, done: false });
    g.line = null;
    g.storyBy = g.storyBy || {}; g.lineBy = g.lineBy || {};
    g.storyBy[car.no] = g.story; g.lineBy[car.no] = null;
    broadcast(g, g.story, { cars: false });
  } else if (type === 'line') {
    // 아이 이야기를 읽는 중이면 kid: true (문장 번호가 아이 이야기 안에서 다시 0부터 셈)
    const placeId = txt(msg.placeId, 60), cur = g.storyBy && g.storyBy[car.no];
    g.line = tag({ type, placeId, index: int(msg.index, 0, 999, 0), kid: !!(cur && cur.placeId === placeId && cur.kid), at: now });
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
  } else if (type === 'pick_ack') {
    const ack = tag({ type, id: txt(msg.id, 40), name: txt(msg.name, 60), mode: txt(msg.mode, 16), accepted: msg.accepted !== false && msg.mode !== 'later', at: now });
    if (!ack.id) return;
    if (g.pending && g.pending.id === ack.id) g.pending = null;   // 운전자가 답했으니 다음 장소를 받을 수 있음
    capPush(g.acks, ack, MAX.acks);
    broadcast(g, ack, { except: car.ws });
  } else if (type === 'say') {
    capPush(g.said, { at: now, text: txt(msg.text, 600), lang: lang2(msg.lang, g.lang), kind: txt(msg.kind, 20), car: car.name, carNo: car.no }, MAX.said);
  } else if (type === 'trip_end') {
    markEnded(g);   // 기록을 올리지 않고 끝내도 보관 기간을 셈
    broadcast(g, tag({ type, at: now }), { cars: false });
  } else if (type === 'qa') {
    const item = tag({ q: txt(msg.q, 200), a: txt(msg.a, 1200), title: txt(msg.title, 60), placeId: txt(msg.placeId, 60), at: num(msg.at, now) });
    if (!item.a) return;
    capPush(g.qa, item, MAX.qa);
    broadcast(g, Object.assign({ type }, item), { cars: false, full: true });
  } else if (type === 'hunt') {
    // 아이 모드의 창밖 보물찾기. 찾을 것을 휴대폰에 띄움 (사진은 가리고 창밖을 보게 함)
    const placeId = txt(msg.placeId, 60);
    if (!placeId) return;
    g.hunt = tag({ type, placeId, text: txt(msg.text, 300), sticker: STICKER_RE.test(msg.sticker || '') ? msg.sticker : '', total: int(msg.total, 0, 50, 0), at: now });
    broadcast(g, g.hunt, { cars: false });
  } else if (type === 'hunt_end') {
    if (g.hunt && g.hunt.carNo === car.no) g.hunt = null;
    broadcast(g, tag({ type, placeId: txt(msg.placeId, 60), at: now }), { cars: false });
  } else if (type === 'found') {
    const f = tag({ placeId: txt(msg.placeId, 60), by: txt(msg.by, 20), sticker: STICKER_RE.test(msg.sticker || '') ? msg.sticker : '', count: int(msg.count, 0, 50, 0), total: int(msg.total, 0, 50, 0), at: now });
    if (!f.placeId) return;
    if (g.hunt && g.hunt.placeId === f.placeId && g.hunt.carNo === car.no) g.hunt = null;
    g.found = g.found.filter(x => !(x.placeId === f.placeId && x.carNo === f.carNo));
    capPush(g.found, f, 100);
    broadcast(g, Object.assign({ type }, f), { cars: false });
  } else if (type === 'kidmode') {
    g.kid = !!msg.on;
    broadcast(g, tag({ type, on: g.kid }), { cars: false });
  } else if (type === 'kid') {
    const kid = tag({ type, placeId: txt(msg.placeId, 60), text: txt(msg.text, 800), at: now });
    if (!kid.text) return;
    // 나중에 들어온 휴대폰도 이어서 보게 그 차의 이야기에 붙여 둠
    const cur = g.storyBy && g.storyBy[car.no];
    if (cur && cur.placeId === kid.placeId) cur.kid = kid.text;
    broadcast(g, kid, { cars: false });
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
    // 확인 중인 곳의 id 도 알려 줌: 운전자 답(pick_ack)이 오면 이 휴대폰도 바로 다시 보낼 수 있게
    if (g.pending && now - g.pending.at < PICK_HOLD_MS) return refuse('busy', { id: g.pending.id, name: g.pending.name, retryIn: Math.ceil((PICK_HOLD_MS - (now - g.pending.at)) / 1000) });
    g.pickAt.set(ws.pid, now);
    const by = txt(msg.by, 20).trim() || (g.lang === 'en' ? 'Passenger' : '동승자');
    const pick = Object.assign({ type: 'pick', id: crypto.randomBytes(4).toString('hex') }, place, { by, at: now });
    g.pending = { id: pick.id, name: pick.name, at: now };
    capPush(g.picks, pick, MAX.picks);
    persist(g);
    for (const c of g.cars.values()) send(c.ws, pick);
    for (const p of g.phones) send(p, Object.assign({ mine: p === ws }, pick));
  } else if (msg.type === 'hunt_found') {
    // 창밖 보물찾기: 휴대폰에서 [찾았다]를 누르면 찾을 것을 낸 차에 알림. 스티커는 차가 줌
    const h = g.hunt, placeId = txt(msg.placeId, 60);
    if (!h || h.placeId !== placeId) return;
    const by = txt(msg.by, 20).trim();
    for (const c of g.cars.values()) if (!h.carNo || c.no === h.carNo) send(c.ws, { type: 'hunt_found', placeId, by, at: now });
  }
}

server.listen(PORT, '0.0.0.0', () => {
  console.log('구르는 가이드 그룹 서버: ' + PUBLIC_BASE + ' (에뮬레이터에서는 http://10.0.2.2:' + PORT + ')');
});

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
