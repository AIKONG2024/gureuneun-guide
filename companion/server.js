'use strict';
/*
 * 구르는 가이드 동반 서버
 * 차량 앱과 휴대폰 웹이 같은 여행(trip)을 주고받는 API. Node 내장 모듈만 쓴다(npm 설치 없음).
 *   실행: node companion/server.js   또는   cd companion && npm start
 *   포트 8787(환경 변수 PORT 로 바꿈), 0.0.0.0 에 붙는다. 같은 와이파이 휴대폰은 /api/info 의 lanUrl 로 접속
 * 저장: 메모리 + companion/state/<여행코드>.json(다시 켜도 이어짐). 휴대폰이 올린 사진은 companion/uploads/
 *
 * 화면 주소
 *   /t/:code        휴대폰 웹 '우리 여행'(web/index.html). ?me=mom 이면 그 일행으로 바로 시작
 *   /reel/:code     여행 영상(web/reel/index.html). 영상은 ../photos ../data ../audio ../map 상대 주소를 쓰므로
 *                   /photos /data /audio /map /vendor 도 web/ 아래로 연결한다(차량은 file:///android_asset/reel/ 로 같은 구조)
 *   /web/...        companion/web 정적 파일,  /uploads/...  휴대폰이 올린 사진
 */
const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');
const zlib = require('zlib');
const crypto = require('crypto');

const PORT = Number(process.env.PORT) || 8787;
const HOST = process.env.HOST || '0.0.0.0';
const ROOT = __dirname;
const WEB = path.join(ROOT, 'web');
// 시험용으로 서버를 하나 더 띄울 때는 STATE_DIR, UPLOAD_DIR 을 따로 줘서 시연 서버의 상태를 건드리지 않게 함
const STATE_DIR = path.resolve(process.env.STATE_DIR || path.join(ROOT, 'state'));
const UPLOAD_DIR = path.resolve(process.env.UPLOAD_DIR || path.join(ROOT, 'uploads'));
const SEED_FILE = path.join(WEB, 'data', 'trip_seed.json');
const PLACES_FILE = path.join(WEB, 'data', 'places.json');
const DEFAULT_CODE = 'GJ1016';
const MAX_JSON = 5 * 1024 * 1024;
const MAX_PHOTO = 25 * 1024 * 1024;
const INBOX_KEEP = 1000;
const CAR_ONLINE_MS = 15000;
// 차량이 'add' 를 가져간 뒤 이 시간 동안 차량 상태에 그 장소가 없으면 차량이 넣지 않기로 한 것으로 본다
const PENDING_GRACE_MS = 15000;

fs.mkdirSync(STATE_DIR, { recursive: true });
fs.mkdirSync(UPLOAD_DIR, { recursive: true });

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.mjs': 'application/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.txt': 'text/plain; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.png': 'image/png',
  '.webp': 'image/webp',
  '.gif': 'image/gif',
  '.heic': 'image/heic',
  '.ico': 'image/x-icon',
  '.ogg': 'audio/ogg',
  '.oga': 'audio/ogg',
  '.wav': 'audio/wav',
  '.mp3': 'audio/mpeg',
  '.m4a': 'audio/mp4',
  '.mp4': 'video/mp4',
  '.webm': 'video/webm',
  '.woff2': 'font/woff2',
  '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};
const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, HEAD, POST, PUT, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type, Accept, If-None-Match, X-Requested-With',
  'Access-Control-Allow-Private-Network': 'true',
  'Access-Control-Max-Age': '600',
};

// ---------------------------------------------------------------- 작은 도구
const now = () => Date.now();
const clone = (o) => (o == null ? o : JSON.parse(JSON.stringify(o)));
const str = (v, max) => (v == null ? '' : String(v).trim().slice(0, max || 200));
const num = (v) => (v === null || v === undefined || v === '' || !isFinite(Number(v)) ? null : Number(v));
const md5 = (s) => crypto.createHash('md5').update(s).digest('hex');
const normName = (s) => String(s || '').replace(/[\s·\-_.,]+/g, '').toLowerCase();

function readJsonFile(file, fallback) {
  try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch (e) { return fallback; }
}

// 파일이 바뀌면 다시 읽는 JSON(data/ 를 고쳐도 서버를 다시 켤 필요 없게)
const jsonCache = new Map();
function cachedJson(file) {
  let st;
  try { st = fs.statSync(file); } catch (e) { return null; }
  const c = jsonCache.get(file);
  if (c && c.mtime === st.mtimeMs && c.size === st.size) return c.data;
  const data = readJsonFile(file, null);
  jsonCache.set(file, { mtime: st.mtimeMs, size: st.size, data });
  return data;
}

function lanAddress() {
  if (process.env.LAN_IP) return process.env.LAN_IP;
  const nets = os.networkInterfaces();
  const cands = [];
  for (const [name, list] of Object.entries(nets)) {
    for (const a of list || []) {
      const fam = typeof a.family === 'string' ? a.family : (a.family === 4 ? 'IPv4' : 'IPv6');
      if (fam !== 'IPv4' || a.internal) continue;
      const ip = a.address;
      if (ip.startsWith('169.254.')) continue;
      let score = 0;
      if (/^(en|eth|wlan|wl|wi-?fi)/i.test(name)) score += 10;
      if (/^(en0|wlan0|eth0)$/i.test(name)) score += 5;
      if (/^(bridge|utun|vmnet|vboxnet|docker|veth|br-|llw|awdl|tun|tap|zt|tailscale|anpi)/i.test(name)) score -= 20;
      if (/^192\.168\./.test(ip)) score += 4;
      else if (/^10\./.test(ip)) score += 3;
      else if (/^172\.(1[6-9]|2\d|3[01])\./.test(ip)) score += 2;
      cands.push({ ip, name, score });
    }
  }
  cands.sort((a, b) => b.score - a.score);
  return cands.length ? cands[0].ip : '127.0.0.1';
}

function normCode(c) {
  c = String(c || '').trim().toUpperCase();
  return /^[A-Z0-9_-]{2,32}$/.test(c) ? c : null;
}

// ---------------------------------------------------------------- 여행 저장소
// st = { code, trip(차량이 보낸 마지막 상태, 없으면 null → trip_seed.json), inbox[], seq, carSeq(차량이 가져간 마지막 seq),
//        carAt(차량이 마지막으로 연락한 때), stateAt(마지막 PUT state), shares{일행id:true|false}, uploads[{url,by,at}] }
const store = new Map();
const dirty = new Set();
let saveTimer = null;

function seedTrip(code) {
  const seed = cachedJson(SEED_FILE) || {
    title: '우리 여행', region: 'gyeongju', members: [], places: [], now: null, heard: [],
    mode: { active: false }, car: null, record: null, rev: 0,
  };
  const t = clone(seed);
  t.code = code;
  return t;
}

function getStore(code) {
  let st = store.get(code);
  if (st) return st;
  const saved = readJsonFile(path.join(STATE_DIR, code + '.json'), null);
  st = Object.assign({ code, trip: null, inbox: [], seq: 0, carSeq: 0, carAt: 0, stateAt: 0, shares: {}, uploads: [] }, saved || {});
  st.code = code;
  if (!Array.isArray(st.inbox)) st.inbox = [];
  if (!Array.isArray(st.uploads)) st.uploads = [];
  if (!st.shares || typeof st.shares !== 'object') st.shares = {};
  store.set(code, st);
  return st;
}

function persist(st) {
  dirty.add(st.code);
  if (!saveTimer) saveTimer = setTimeout(flush, 120);
}

function flush() {
  saveTimer = null;
  for (const code of dirty) {
    const st = store.get(code);
    if (!st) continue;
    const file = path.join(STATE_DIR, code + '.json');
    const tmp = file + '.tmp';
    try {
      fs.writeFileSync(tmp, JSON.stringify(st, null, 1));
      fs.renameSync(tmp, file);
    } catch (e) {
      console.error('[저장 실패]', code, e.message);
    }
  }
  dirty.clear();
}

const samePlace = (a, b) => !!(a && b) && ((a.id && b.id && a.id === b.id) || (normName(a.name) && normName(a.name) === normName(b.name)));
const uploadKey = (u) => { const m = /uploads\/([^?#]+)/.exec(String(u || '')); return m ? m[1] : String(u || ''); };

// 휴대폰에 보여 줄 여행: 차량 상태 + 아직 차량에 반영되지 않은 휴대폰 입력(담기, 위치 공유, 올린 사진)
function buildView(st) {
  const trip = st.trip ? clone(st.trip) : seedTrip(st.code);
  trip.code = st.code;
  if (!Array.isArray(trip.places)) trip.places = [];
  if (Array.isArray(trip.members)) {
    for (const m of trip.members) {
      if (m && Object.prototype.hasOwnProperty.call(st.shares, m.id)) m.sharing = !!st.shares[m.id];
    }
  }
  for (const it of st.inbox) {
    if (it.type !== 'add' || !it.place) continue;
    if (trip.places.some((p) => samePlace(p, it.place))) continue;
    if (it.takenAt && st.stateAt > it.takenAt + PENDING_GRACE_MS) continue;
    const p = Object.assign({}, it.place, { by: it.by || it.place.by || null, status: 'pending', time: it.place.time || null });
    delete p.link;
    trip.places.push(p);
  }
  if (trip.record && typeof trip.record === 'object' && st.uploads.length) {
    const photos = Array.isArray(trip.record.photos) ? trip.record.photos.slice() : [];
    const have = new Set(photos.map(uploadKey));
    for (const u of st.uploads) if (!have.has(uploadKey(u.url))) photos.push(u.url);
    trip.record.photos = photos;
  }
  return trip;
}

// 차량에 주는 inbox 항목은 계약서 모양 그대로: {seq, type, by, place | id | url | on}
const publicItem = (it) => {
  const o = { seq: it.seq, type: it.type, by: it.by || null };
  if (it.type === 'add' && it.place) {
    o.place = Object.assign({}, it.place);
    delete o.place.link;
  } else if (it.type === 'visit') o.id = it.id;
  else if (it.type === 'photo') o.url = it.url;
  else if (it.type === 'share') o.on = !!it.on;
  return o;
};

function pushInbox(st, item) {
  item.seq = ++st.seq;
  item.at = item.at || now();
  st.inbox.push(item);
  if (st.inbox.length > INBOX_KEEP) st.inbox.splice(0, st.inbox.length - INBOX_KEEP);
  persist(st);
  return item;
}

// ---------------------------------------------------------------- 휴대폰 접속 수(차량 상단 '휴대폰 N대 연결')
// /t/:code 페이지는 폴링할 때 cid(접속별 임의 id)와 me(일행 id)를 붙여 보낸다. 최근 15초 안에 폴링한 휴대폰을
// me 가 있으면 me 로, 없으면 cid 로 묶어 센다. 저장하지 않고 메모리에만 둔다
const PRESENCE_MS = 15000;
const presence = new Map(); // code -> Map(cid -> { me, at })

function touchPresence(code, cid, me) {
  let m = presence.get(code);
  if (!m) {
    m = new Map();
    presence.set(code, m);
  }
  m.set(cid, { me: me || null, at: now() });
}

function presenceOf(code, memberIds) {
  const m = presence.get(code);
  const t = now();
  const keys = new Set();
  const who = new Set();
  if (m) {
    for (const [cid, p] of m) {
      if (t - p.at > 60000) { m.delete(cid); continue; }
      if (t - p.at > PRESENCE_MS) continue;
      keys.add(p.me ? 'me:' + p.me : 'cid:' + cid);
      if (p.me) who.add(p.me);
    }
  }
  const order = memberIds || [];
  const rank = (id) => (order.indexOf(id) < 0 ? 99 : order.indexOf(id));
  return { phones: keys.size, who: Array.from(who).sort((a, b) => rank(a) - rank(b) || a.localeCompare(b)) };
}

// ---------------------------------------------------------------- 링크 속 장소 찾기
function safeDecode(s) {
  let out = String(s || '');
  for (let i = 0; i < 2; i++) {
    if (!/%[0-9a-f]{2}/i.test(out)) break;
    try {
      out = decodeURIComponent(out.replace(/\+/g, ' '));
    } catch (e) {
      out = out.replace(/(%[0-9a-f]{2})+/gi, (m) => { try { return decodeURIComponent(m); } catch (e2) { return m; } });
    }
  }
  return out;
}

const looksLikeUrl = (s) => /^[a-z][a-z0-9+.-]*:\/\//i.test(s) || /^www\./i.test(s) || /^[a-z0-9-]+(\.[a-z0-9-]+)+(\/|$)/i.test(s);

function sourceOf(link) {
  const s = String(link || '').trim();
  if (!looksLikeUrl(s)) return '직접 입력';
  let host = '';
  let p = '';
  try {
    const u = new URL(/^[a-z][a-z0-9+.-]*:\/\//i.test(s) ? s : 'https://' + s);
    host = u.hostname.toLowerCase();
    p = u.pathname.toLowerCase();
  } catch (e) {
    host = s.toLowerCase();
  }
  const hp = host + p;
  if (/(^|\.)map\.naver\.com|(^|\.)naver\.me$|place\.naver\.com|map\.kakao\.com|place\.map\.kakao|(^|\.)kko\.to$|maps\.google\.|google\.[a-z.]+\/maps|goo\.gl\/maps|maps\.app\.goo\.gl|tmap|(^|\.)map\.|kakaomap/.test(hp)) return '지도 앱';
  if (/instagram\.com|instagr\.am/.test(host)) return '인스타그램';
  if (/chatgpt\.com|chat\.openai\.com|gemini\.google|g\.co\/gemini|perplexity\.ai|claude\.ai|copilot\.microsoft|wrtn\.ai|clova-x|(^|\.)grok\.com/.test(hp)) return 'AI 답변';
  if (/blog\.naver\.com|(^|\.)blog\.|tistory\.com|brunch\.co\.kr|velog\.io|post\.naver\.com|blog\.daum|medium\.com|wordpress|blogspot/.test(host)) return '블로그';
  if (/kakao|kakaotalk/.test(host)) return '카카오톡';
  return '링크';
}

function photoIfExists(name) {
  if (!name) return null;
  const f = path.join(WEB, 'photos', path.basename(String(name)));
  return fs.existsSync(f) ? path.basename(String(name)) : null;
}

function matchPlace(text, typed) {
  const places = cachedJson(PLACES_FILE) || [];
  const hay = normName(safeDecode(text).replace(/[\/?#&=:%]+/g, ' '));
  const want = typed ? normName(typed) : '';
  let best = null;
  for (const p of places) {
    let score = 0;
    let hit = null;
    const name = normName(p.name);
    if (name && hay.includes(name)) { score += 100 + name.length; hit = p.name; }
    for (const k of p.keywords || []) {
      const kk = normName(k);
      if (kk && hay.includes(kk)) { score += 10 + kk.length * 2; hit = hit || k; }
      // 직접 입력한 이름이 장소 이름이나 열쇠말의 일부일 때(예: '첨성' → 첨성대)
      if (want && want.length >= 2 && kk && kk.includes(want)) { score += 20 + want.length; hit = hit || k; }
    }
    if (want && want.length >= 2 && name && name.includes(want)) { score += 30 + want.length; hit = hit || p.name; }
    if (score > 0 && (!best || score > best.score)) best = { p, score, hit };
  }
  return best;
}

function resolveLink(body) {
  const link = str(body.link || body.url || body.text, 2000);
  const typed = str(body.name || body.query, 60);
  const source = str(body.source, 20) || sourceOf(link || typed);
  const text = [typed, link].filter(Boolean).join(' ');
  if (!text) return { ok: false, reason: 'empty', source };
  const m = matchPlace(text, typed);
  if (!m) return { ok: false, reason: 'not_found', source, query: safeDecode(text).slice(0, 200) };
  const p = m.p;
  return {
    ok: true,
    candidate: {
      id: p.id, name: p.name, kind: p.kind || 'sight', area: p.area || '', lat: num(p.lat), lon: num(p.lon),
      photo: photoIfExists(p.photo), source, note: p.note || '',
    },
    matched: m.hit,
  };
}

function cleanPlace(p, by) {
  if (!p || typeof p !== 'object') return null;
  const name = str(p.name, 60);
  if (!name) return null;
  const id = str(p.id, 64).replace(/[^A-Za-z0-9_-]/g, '') || 'p_' + md5(name).slice(0, 8);
  const out = {
    id, name,
    kind: str(p.kind, 20) || 'sight',
    area: str(p.area, 60),
    lat: num(p.lat), lon: num(p.lon),
    by: by || str(p.by, 20) || null,
    source: str(p.source, 20) || '링크',
    photo: str(p.photo, 200) || null,
    time: str(p.time, 5) || null,
    note: str(p.note, 120),
  };
  if (p.link) out.link = str(p.link, 1000);
  return out;
}

// ---------------------------------------------------------------- 요청 처리 도구
function send(res, status, body, headers) {
  const data = Buffer.isBuffer(body) || typeof body === 'string' ? body : JSON.stringify(body);
  res.writeHead(status, Object.assign({ 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' }, headers || {}));
  res.end(data);
}

function httpError(status, message) {
  const e = new Error(message);
  e.status = status;
  return e;
}

function readBody(req, limit) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    let done = false;
    req.on('data', (c) => {
      if (done) return;
      size += c.length;
      if (size > limit) {
        done = true;
        reject(httpError(413, 'too_large'));
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => { if (!done) { done = true; resolve(Buffer.concat(chunks)); } });
    req.on('error', (e) => { if (!done) { done = true; reject(e); } });
  });
}

async function readJson(req) {
  const buf = await readBody(req, MAX_JSON);
  if (!buf.length) return {};
  try {
    return JSON.parse(buf.toString('utf8'));
  } catch (e) {
    throw httpError(400, 'bad_json');
  }
}

function sniffImage(buf) {
  if (buf.length < 12) return null;
  if (buf[0] === 0xff && buf[1] === 0xd8 && buf[2] === 0xff) return 'jpg';
  if (buf[0] === 0x89 && buf[1] === 0x50 && buf[2] === 0x4e && buf[3] === 0x47) return 'png';
  if (buf.toString('ascii', 0, 4) === 'RIFF' && buf.toString('ascii', 8, 12) === 'WEBP') return 'webp';
  if (buf.toString('ascii', 0, 4) === 'GIF8') return 'gif';
  if (buf.toString('ascii', 4, 8) === 'ftyp' && /^(heic|heix|mif1|msf1|hevc)/.test(buf.toString('ascii', 8, 12))) return 'heic';
  return null;
}

// multipart/form-data 로 올린 경우 첫 파일만 꺼냄(기본은 사진 바이너리를 그대로 보냄)
function firstMultipartFile(buf, contentType) {
  const m = /boundary=(?:"([^"]+)"|([^;]+))/i.exec(contentType || '');
  if (!m) return null;
  const boundary = Buffer.from('--' + (m[1] || m[2]).trim());
  let pos = buf.indexOf(boundary);
  while (pos !== -1) {
    const headStart = pos + boundary.length + 2;
    const headEnd = buf.indexOf('\r\n\r\n', headStart);
    if (headEnd === -1) return null;
    const head = buf.toString('utf8', headStart, headEnd);
    const next = buf.indexOf(boundary, headEnd + 4);
    if (next === -1) return null;
    if (/filename=/i.test(head)) return buf.subarray(headEnd + 4, next - 2);
    pos = next;
  }
  return null;
}

function stamp(d) {
  const p = (n) => String(n).padStart(2, '0');
  return d.getFullYear() + p(d.getMonth() + 1) + p(d.getDate()) + '-' + p(d.getHours()) + p(d.getMinutes()) + p(d.getSeconds());
}

// ---------------------------------------------------------------- 정적 파일
const gzCache = new Map();
const GZIP_TYPE = /^(text\/|application\/(javascript|json)|image\/svg)/;

function safeJoin(base, rel) {
  let decoded;
  try { decoded = decodeURIComponent(rel); } catch (e) { return null; }
  if (decoded.includes('\0')) return null;
  const p = path.normalize(path.join(base, decoded));
  if (p !== base && !p.startsWith(base + path.sep)) return null;
  return p;
}

function notFound(req, res) {
  send(res, 404, { ok: false, error: 'not_found', path: req.url.split('?')[0] });
}

function serveFile(req, res, file, cacheControl) {
  fs.stat(file, (err, st) => {
    if (err || !st.isFile()) return notFound(req, res);
    const ext = path.extname(file).toLowerCase();
    const type = MIME[ext] || 'application/octet-stream';
    const etag = '"' + st.size.toString(16) + '-' + Math.round(st.mtimeMs).toString(16) + '"';
    const h = {
      'Content-Type': type,
      ETag: etag,
      'Last-Modified': st.mtime.toUTCString(),
      'Cache-Control': cacheControl || 'no-cache',
      'Accept-Ranges': 'bytes',
    };
    if (req.headers['if-none-match'] === etag) {
      res.writeHead(304, h);
      return res.end();
    }
    const range = /^bytes=(\d*)-(\d*)$/.exec(String(req.headers.range || '').trim());
    if (range && (range[1] !== '' || range[2] !== '')) {
      let start;
      let end;
      if (range[1] === '') {
        start = Math.max(0, st.size - Number(range[2]));
        end = st.size - 1;
      } else {
        start = Number(range[1]);
        end = range[2] === '' ? st.size - 1 : Math.min(Number(range[2]), st.size - 1);
      }
      if (start > end || start >= st.size) {
        res.writeHead(416, { 'Content-Range': 'bytes */' + st.size });
        return res.end();
      }
      h['Content-Range'] = 'bytes ' + start + '-' + end + '/' + st.size;
      h['Content-Length'] = end - start + 1;
      res.writeHead(206, h);
      if (req.method === 'HEAD') return res.end();
      return fs.createReadStream(file, { start, end }).pipe(res);
    }
    if (GZIP_TYPE.test(type) && st.size > 1400 && /\bgzip\b/.test(String(req.headers['accept-encoding'] || ''))) {
      let c = gzCache.get(file);
      if (!c || c.etag !== etag) {
        c = { etag, gz: zlib.gzipSync(fs.readFileSync(file)) };
        gzCache.set(file, c);
      }
      h['Content-Encoding'] = 'gzip';
      h['Content-Length'] = c.gz.length;
      h.Vary = 'Accept-Encoding';
      res.writeHead(200, h);
      return res.end(req.method === 'HEAD' ? undefined : c.gz);
    }
    h['Content-Length'] = st.size;
    res.writeHead(200, h);
    if (req.method === 'HEAD') return res.end();
    fs.createReadStream(file).pipe(res);
  });
}

function serveUnder(req, res, base, rel, cacheControl) {
  const file = safeJoin(base, rel);
  if (!file) return send(res, 400, { ok: false, error: 'bad_path' });
  serveFile(req, res, file, cacheControl);
}

function redirect(res, to) {
  res.writeHead(302, { Location: to, 'Cache-Control': 'no-store' });
  res.end();
}

function urls(code) {
  const lanUrl = 'http://' + lanAddress() + ':' + PORT;
  return {
    lanUrl,
    emuUrl: 'http://10.0.2.2:' + PORT,
    localUrl: 'http://localhost:' + PORT,
    code,
    tripUrl: lanUrl + '/t/' + code,
    reelUrl: lanUrl + '/reel/' + code,
  };
}

function landingPage() {
  const u = urls(DEFAULT_CODE);
  const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const who = [['dad', '아빠'], ['mom', '엄마'], ['kid1', '첫째'], ['kid2', '둘째']]
    .map(([id, name]) => '<a href="/t/' + DEFAULT_CODE + '?me=' + id + '">' + name + '</a>').join(' · ');
  return '<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">' +
    '<title>구르는 가이드 동반 서버</title><link rel="stylesheet" href="/web/vendor/asta-sans/asta-sans.css">' +
    '<style>body{font-family:"Asta Sans",-apple-system,sans-serif;max-width:620px;margin:40px auto;padding:0 20px;color:#111827;background:#F2F4F7;line-height:1.6}' +
    'h1{font-size:24px}a{color:#0B2A57;font-weight:700}.c{background:#fff;border-radius:16px;padding:16px 20px;margin:14px 0;box-shadow:0 6px 18px rgba(16,24,40,.05)}code{background:#F2F4F7;padding:2px 6px;border-radius:6px;font-size:13px}</style></head><body>' +
    '<h1>구르는 가이드 동반 서버</h1>' +
    '<div class="c"><b>휴대폰 웹 우리 여행</b><br><a href="/t/' + DEFAULT_CODE + '">/t/' + DEFAULT_CODE + '</a><br>일행으로 바로 열기: ' + who + '</div>' +
    '<div class="c"><b>여행 영상</b><br><a href="/reel/' + DEFAULT_CODE + '">/reel/' + DEFAULT_CODE + '</a></div>' +
    '<div class="c"><b>같은 와이파이 휴대폰</b><br><code>' + esc(u.tripUrl) + '</code><br><b>에뮬레이터</b><br><code>' + esc(u.emuUrl + '/t/' + DEFAULT_CODE) + '</code></div>' +
    '<div class="c"><b>API</b><br><a href="/api/info">/api/info</a> · <a href="/api/trip/' + DEFAULT_CODE + '">/api/trip/' + DEFAULT_CODE + '</a></div>' +
    '</body></html>';
}

// ---------------------------------------------------------------- API
async function handleApi(req, res, parts, q) {
  const method = req.method;
  // parts: ['api', ...]
  if (parts[1] === 'info' && method === 'GET') {
    const u = urls(DEFAULT_CODE);
    return send(res, 200, { lanUrl: u.lanUrl, emuUrl: u.emuUrl });
  }
  if (parts[1] === 'health') return send(res, 200, { ok: true, time: now() });

  if (parts[1] === 'resolve' && (method === 'POST' || method === 'GET')) {
    const body = method === 'POST' ? await readJson(req) : { link: q.get('link'), name: q.get('name') };
    const r = resolveLink(body || {});
    log('resolve', (r.ok ? r.candidate.name : '찾지 못함') + ' · ' + (r.source || ''), str(body.link || body.name, 80));
    return send(res, 200, r);
  }

  if (parts[1] === 'trip' && parts[2]) {
    const code = normCode(parts[2]);
    if (!code) return send(res, 400, { ok: false, error: 'bad_code' });
    const st = getStore(code);
    const sub = parts[3] || '';

    if (!sub && method === 'GET') {
      // 차량과 영상은 계약서 모양 그대로 {trip, inbox, seq}. 휴대폰 웹(cid 를 붙여 폴링)과 시험 도구(full=1)에만
      // 올린 사진 목록(uploads), 차량 연결 여부(carOnline), 바뀐 것이 없으면 짧게 답하는 ver 를 더 줌
      const cid = str(q.get('cid'), 40).replace(/[^A-Za-z0-9_-]/g, '');
      if (cid) touchPresence(code, cid, str(q.get('me'), 20).replace(/[^A-Za-z0-9_-]/g, ''));
      const body = {
        trip: buildView(st),
        inbox: st.inbox.filter((i) => i.seq > st.carSeq).map(publicItem),
        seq: st.seq,
      };
      if (!cid && q.get('full') !== '1') return send(res, 200, body);
      body.uploads = st.uploads;
      body.carOnline = now() - st.carAt < CAR_ONLINE_MS;
      const ver = md5(JSON.stringify(body)).slice(0, 16);
      if (q.get('v') && q.get('v') === ver) return send(res, 200, { same: true, ver });
      body.ver = ver;
      return send(res, 200, body);
    }

    if (sub === 'presence' && method === 'GET') {
      const t = st.trip || seedTrip(code);
      return send(res, 200, presenceOf(code, (Array.isArray(t.members) ? t.members : []).map((m) => m && m.id)));
    }

    if (sub === 'state' && (method === 'PUT' || method === 'POST')) {
      let body = await readJson(req);
      if (!body || typeof body !== 'object' || Array.isArray(body)) throw httpError(400, 'bad_trip');
      if (body.trip && typeof body.trip === 'object' && !body.members && !body.places) body = body.trip;
      const prev = st.trip || seedTrip(code);
      const next = Object.assign({}, prev, body);
      next.code = code;
      next.rev = Math.max(Number(prev.rev) || 0, Number(body.rev) || 0) + 1;
      // 휴대폰에서 바꾼 위치 공유가 차량 상태에 들어갔으면 그때부터는 차량 값을 따름
      if (Array.isArray(next.members)) {
        for (const m of next.members) {
          if (m && Object.prototype.hasOwnProperty.call(st.shares, m.id) && !!m.sharing === !!st.shares[m.id]) delete st.shares[m.id];
        }
      }
      st.trip = next;
      st.stateAt = now();
      st.carAt = now();
      persist(st);
      log('state', code + ' rev ' + next.rev, describeState(next));
      return send(res, 200, { ok: true, rev: next.rev, seq: st.seq });
    }

    if (sub === 'inbox' && method === 'GET') {
      const after = Number(q.get('after')) || 0;
      const items = st.inbox.filter((i) => i.seq > after);
      const t = now();
      let taken = false;
      for (const i of items) if (!i.takenAt) { i.takenAt = t; taken = true; }
      const last = items.length ? items[items.length - 1].seq : 0;
      const carSeq = Math.max(st.carSeq, Math.min(after, st.seq), last);
      if (carSeq !== st.carSeq) { st.carSeq = carSeq; taken = true; }
      st.carAt = t;
      if (taken) persist(st);
      return send(res, 200, items.map(publicItem));
    }

    if (sub === 'inbox' && method === 'POST') {
      const body = await readJson(req);
      const type = str(body.type, 20);
      const by = str(body.by, 20).replace(/[^A-Za-z0-9_-]/g, '') || null;
      const item = { type, by };
      if (type === 'add') {
        const place = cleanPlace(body.place || body.candidate, by);
        if (!place) throw httpError(400, 'bad_place');
        const dup = buildView(st).places.find((p) => samePlace(p, place));
        if (dup) return send(res, 200, { ok: true, seq: st.seq, already: true, by: dup.by || null, status: dup.status || null });
        item.place = place;
      } else if (type === 'visit') {
        const id = str(body.id, 64);
        if (!id) throw httpError(400, 'bad_id');
        const recent = st.inbox.slice().reverse().find((i) => i.type === 'visit' && i.id === id && now() - i.at < 30000);
        if (recent) return send(res, 200, { ok: true, seq: recent.seq, already: true });
        item.id = id;
        if (body.name) item.name = str(body.name, 60);
      } else if (type === 'photo') {
        const url = str(body.url, 500);
        if (!url) throw httpError(400, 'bad_url');
        item.url = url;
        if (!st.uploads.some((u) => uploadKey(u.url) === uploadKey(url))) st.uploads.push({ url, by, at: now() });
      } else if (type === 'share') {
        if (!by) throw httpError(400, 'bad_by');
        item.on = !!body.on;
        st.shares[by] = item.on;
      } else {
        throw httpError(400, 'bad_type');
      }
      pushInbox(st, item);
      log('inbox', code + ' #' + item.seq + ' ' + type, by + ' ' + (item.place ? item.place.name : item.id || item.url || (type === 'share' ? (item.on ? '켬' : '끔') : '')));
      return send(res, 200, { ok: true, seq: item.seq });
    }

    if (sub === 'photo' && method === 'POST') {
      let buf = await readBody(req, MAX_PHOTO);
      const ct = String(req.headers['content-type'] || '');
      if (/multipart\/form-data/i.test(ct)) buf = firstMultipartFile(buf, ct) || Buffer.alloc(0);
      const ext = sniffImage(buf);
      if (!ext) throw httpError(415, 'not_image');
      const by = str(q.get('by'), 20).replace(/[^A-Za-z0-9_-]/g, '') || null;
      const name = code + '-' + stamp(new Date()) + '-' + crypto.randomBytes(3).toString('hex') + '.' + ext;
      fs.writeFileSync(path.join(UPLOAD_DIR, name), buf);
      const url = '/uploads/' + name;
      st.uploads.push({ url, by, at: now() });
      const item = pushInbox(st, { type: 'photo', by, url });
      log('photo', code + ' #' + item.seq, (by || '') + ' ' + url + ' ' + Math.round(buf.length / 1024) + 'KB');
      return send(res, 200, { ok: true, url, seq: item.seq });
    }

    // 시연 연습용: 여행을 처음 상태(trip_seed.json)로 되돌림. seq 는 이어서 씀(차량이 after 로 가져가는 번호가 꼬이지 않게)
    if (sub === 'reset' && method === 'POST') {
      Object.assign(st, { trip: null, inbox: [], carSeq: st.seq, stateAt: 0, shares: {}, uploads: [] });
      persist(st);
      log('reset', code, '');
      return send(res, 200, { ok: true, seq: st.seq });
    }
  }
  return send(res, 404, { ok: false, error: 'no_api', path: '/' + parts.join('/') });
}

function describeState(t) {
  const bits = [];
  if (t.now && t.now.id) bits.push('now ' + t.now.id);
  if (t.mode && t.mode.active) bits.push('mode on');
  if (t.car && t.car.eta) bits.push('eta ' + t.car.eta);
  if (t.record && t.record.ready) bits.push('record');
  return bits.join(', ');
}

function log(kind, a, b) {
  const t = new Date();
  const hh = String(t.getHours()).padStart(2, '0') + ':' + String(t.getMinutes()).padStart(2, '0') + ':' + String(t.getSeconds()).padStart(2, '0');
  console.log(hh, kind.padEnd(7), a, b ? '· ' + b : '');
}

// ---------------------------------------------------------------- 라우팅
const STATIC_ROOTS = ['photos', 'data', 'audio', 'map', 'vendor'];

async function handle(req, res) {
  for (const [k, v] of Object.entries(CORS)) res.setHeader(k, v);
  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    return res.end();
  }
  const u = new URL(req.url, 'http://localhost');
  const pathname = u.pathname;
  const parts = pathname.split('/').filter(Boolean);
  const isRead = req.method === 'GET' || req.method === 'HEAD';

  if (parts[0] === 'api') return handleApi(req, res, parts, u.searchParams);
  if (!isRead) return send(res, 405, { ok: false, error: 'method' });

  if (parts.length === 0) {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
    return res.end(landingPage());
  }
  if (pathname === '/favicon.ico') {
    res.writeHead(204);
    return res.end();
  }

  // 휴대폰 웹
  if (parts[0] === 't') {
    if (parts.length === 1) return redirect(res, '/t/' + DEFAULT_CODE + u.search);
    const code = normCode(parts[1]);
    if (parts.length === 2 && code) {
      if (pathname.endsWith('/')) return redirect(res, '/t/' + parts[1] + u.search);
      return serveFile(req, res, path.join(WEB, 'index.html'), 'no-cache');
    }
    return serveUnder(req, res, WEB, parts.slice(1).join('/'));
  }

  // 여행 영상: /reel/:code 는 index.html, /reel/reel.js 처럼 점이 있으면 web/reel/ 의 파일
  if (parts[0] === 'reel') {
    if (parts.length === 1) {
      if (!pathname.endsWith('/')) return redirect(res, '/reel/' + u.search);
      return serveFile(req, res, path.join(WEB, 'reel', 'index.html'), 'no-cache');
    }
    if (parts.length === 2 && !parts[1].includes('.') && normCode(parts[1])) {
      if (pathname.endsWith('/')) return redirect(res, '/reel/' + parts[1] + u.search);
      return serveFile(req, res, path.join(WEB, 'reel', 'index.html'), 'no-cache');
    }
    return serveUnder(req, res, path.join(WEB, 'reel'), parts.slice(1).join('/'));
  }

  if (parts[0] === 'web') return serveUnder(req, res, WEB, parts.slice(1).join('/'));
  if (parts[0] === 'uploads') return serveUnder(req, res, UPLOAD_DIR, parts.slice(1).join('/'), 'public, max-age=31536000, immutable');
  if (STATIC_ROOTS.includes(parts[0])) {
    const cache = parts[0] === 'vendor' ? 'public, max-age=86400' : 'no-cache';
    return serveUnder(req, res, WEB, parts.join('/'), cache);
  }
  return notFound(req, res);
}

const server = http.createServer((req, res) => {
  handle(req, res).catch((e) => {
    const status = e.status || 500;
    if (status >= 500) console.error('[오류]', req.method, req.url, e.stack || e.message);
    if (!res.headersSent) send(res, status, { ok: false, error: e.message || 'error' });
    else res.end();
  });
});

server.on('error', (e) => {
  if (e.code === 'EADDRINUSE') {
    console.error('포트 ' + PORT + ' 를 이미 쓰고 있어요. 켜져 있는 서버를 끄거나 PORT=8788 node companion/server.js 로 실행하세요.');
    process.exit(1);
  }
  throw e;
});

server.listen(PORT, HOST, () => {
  const u = urls(DEFAULT_CODE);
  console.log('구르는 가이드 동반 서버 · 포트 ' + PORT);
  console.log('  이 컴퓨터     ' + u.localUrl + '/t/' + DEFAULT_CODE);
  console.log('  같은 와이파이 ' + u.tripUrl);
  console.log('  에뮬레이터    ' + u.emuUrl + '/t/' + DEFAULT_CODE);
  console.log('  여행 영상     ' + u.localUrl + '/reel/' + DEFAULT_CODE);
  console.log('  상태 저장     ' + STATE_DIR);
});

function shutdown() {
  if (saveTimer) clearTimeout(saveTimer);
  flush();
  process.exit(0);
}
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
