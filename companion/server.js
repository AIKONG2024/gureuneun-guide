// 구르는 가이드 여행 그룹 서버
// 차 앱과 동승자 휴대폰(웹)을 네 자리 그룹 번호로 묶고, 이야기 시작과 고른 장소를 주고받는다.
// 퀴즈(차가 내고 휴대폰이 풀고 서버가 채점), 차에 말로 물어본 것과 답, 휴대폰에서 이어 듣기도 여기서 오간다.
// 차의 실시간 위치는 받지 않는다. 여행이 끝난 뒤 차가 올린 기록(지나온 길, 명소를 지난 시각)만 보관한다.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { WebSocketServer } from 'ws';

const PORT = Number(process.env.PORT || 8787);
const HERE = path.dirname(fileURLToPath(import.meta.url));
const WEB = path.join(HERE, 'web');
const SCENARIOS = path.join(HERE, '..', 'app', 'src', 'main', 'assets', 'scenarios');
const DATA = path.join(HERE, 'data');
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
  const g = {
    code, scenarioId, lang, createdAt: Date.now(),
    cars: new Map(), phones: new Set(),
    story: null, line: null, passes: [], picks: [], acks: [], reports: [], said: [],
    record: null, photos: [], highlight: null, ended: false,
    quizzes: {}, answers: [], qa: [], handoff: null, treasure: null, stars: null,
  };
  groups.set(code, g);
  fs.mkdirSync(path.join(DATA, code, 'photos'), { recursive: true });
  return g;
}

function summary(g) {
  return {
    code: g.code, scenarioId: g.scenarioId, lang: g.lang,
    joinUrl: PUBLIC_BASE + '/g/' + g.code, publicBase: PUBLIC_BASE,
    cars: [...g.cars.values()].map(c => c.name), phones: g.phones.size,
    story: g.story, line: g.line, passes: g.passes, picks: g.picks, acks: g.acks,
    record: g.record, photos: g.photos, highlight: g.highlight, ended: g.ended,
    quizzes: Object.values(g.quizzes).map(publicQuiz), answers: g.answers.map(publicAnswer), scores: scores(g),
    qa: g.qa.slice(-20), handoff: g.handoff, treasure: g.treasure, stars: g.stars,
  };
}

// 휴대폰에는 정답을 미리 보내지 않는다 (답을 고른 뒤에 알려 줌)
function publicQuiz(q) {
  return { id: q.id, name: q.name, q: q.q, choices: q.choices, lang: q.lang, at: q.at, car: q.car };
}

function publicAnswer(a) {
  return { id: a.id, by: a.by, pid: a.pid, choice: a.choice, correct: a.correct, at: a.at };
}

// 이름별 맞힌 개수
function scores(g) {
  const s = {};
  for (const a of g.answers) if (a.correct) s[a.by] = (s[a.by] || 0) + 1;
  return s;
}

// 서버를 다시 켜도 여행 그룹이 이어지도록 디스크에 저장해 둔다
const saveTimers = new Map();
function persist(g) {
  clearTimeout(saveTimers.get(g.code));
  saveTimers.set(g.code, setTimeout(() => {
    const keep = { code: g.code, scenarioId: g.scenarioId, lang: g.lang, createdAt: g.createdAt, story: g.story, passes: g.passes, picks: g.picks, acks: g.acks, reports: g.reports, record: g.record, photos: g.photos, highlight: g.highlight, ended: g.ended, quizzes: g.quizzes, answers: g.answers, qa: g.qa, handoff: g.handoff, treasure: g.treasure, stars: g.stars };
    try { fs.writeFileSync(path.join(DATA, g.code, 'group.json'), JSON.stringify(keep)); } catch (e) { console.warn('[persist]', e.message); }
  }, 300));
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
      if (g.highlight && g.highlight.status === 'processing') g.highlight = null;
    } catch (e) { console.warn('[restore]', code, e.message); }
  }
  if (groups.size) console.log('[group] 이전 그룹 ' + groups.size + '개를 불러왔어요');
}
restoreGroups();

function send(ws, obj) {
  try { if (ws.readyState === 1) ws.send(JSON.stringify(obj)); } catch {}
}

function broadcast(g, obj, { cars = true, phones = true, except = null } = {}) {
  persist(g);
  if (cars) for (const c of g.cars.values()) if (c.ws !== except) send(c.ws, obj);
  if (phones) for (const p of g.phones) if (p !== except) send(p, obj);
}

function members(g) {
  broadcast(g, { type: 'members', cars: [...g.cars.values()].map(c => c.name), phones: g.phones.size });
}

// ---------------------------------------------------------------- HTTP
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json; charset=utf-8', '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.mp4': 'video/mp4', '.svg': 'image/svg+xml', '.txt': 'text/plain; charset=utf-8', '.webp': 'image/webp' };

function json(res, code, obj) {
  res.writeHead(code, { 'Content-Type': MIME['.json'], 'Cache-Control': 'no-store', 'Access-Control-Allow-Origin': '*' });
  res.end(JSON.stringify(obj));
}

function serveFile(res, file, req) {
  fs.stat(file, (err, st) => {
    if (err || !st.isFile()) { res.writeHead(404); return res.end('not found'); }
    const type = MIME[path.extname(file).toLowerCase()] || 'application/octet-stream';
    const range = req && req.headers.range;
    if (range && type.startsWith('video/')) {
      const m = /bytes=(\d*)-(\d*)/.exec(range);
      const start = m && m[1] ? Number(m[1]) : 0;
      const end = m && m[2] ? Number(m[2]) : st.size - 1;
      res.writeHead(206, { 'Content-Type': type, 'Content-Range': 'bytes ' + start + '-' + end + '/' + st.size, 'Accept-Ranges': 'bytes', 'Content-Length': end - start + 1 });
      return fs.createReadStream(file, { start, end }).pipe(res);
    }
    res.writeHead(200, { 'Content-Type': type, 'Content-Length': st.size, 'Cache-Control': 'no-cache' });
    fs.createReadStream(file).pipe(res);
  });
}

function readBody(req, limit = 400 * 1024 * 1024) {
  return new Promise((resolve, reject) => {
    const chunks = []; let size = 0;
    req.on('data', c => { size += c.length; if (size > limit) { reject(new Error('too large')); req.destroy(); } else chunks.push(c); });
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

async function readJson(req) {
  const b = await readBody(req, 30 * 1024 * 1024);
  return b.length ? JSON.parse(b.toString('utf8')) : {};
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://x');
  const p = decodeURIComponent(url.pathname);
  try {
    if (req.method === 'OPTIONS') { res.writeHead(204, { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': '*', 'Access-Control-Allow-Methods': 'GET,POST' }); return res.end(); }

    if (p === '/api/info') return json(res, 200, { publicBase: PUBLIC_BASE, groups: groups.size, font: !!FONT });

    // 개발용: 3D 장면 프레임을 받아 MP4 클립으로 만든다 (web/clips 에 저장되어 차 앱과 휴대폰이 같이 씀)
    if (p === '/api/render/start' && req.method === 'POST') {
      const body = await readJson(req);
      const dir = path.join(DATA, 'render', String(body.name).replace(/[^\w]/g, ''));
      fs.rmSync(dir, { recursive: true, force: true });
      fs.mkdirSync(dir, { recursive: true });
      return json(res, 200, { ok: true });
    }
    if (p === '/api/render/frames' && req.method === 'POST') {
      const body = await readJson(req);
      const dir = path.join(DATA, 'render', String(body.name).replace(/[^\w]/g, ''));
      body.frames.forEach((d, k) => {
        const b64 = String(d).replace(/^data:image\/jpeg;base64,/, '');
        fs.writeFileSync(path.join(dir, String(body.start + k).padStart(5, '0') + '.jpg'), Buffer.from(b64, 'base64'));
      });
      return json(res, 200, { ok: true });
    }
    if (p === '/api/render/done' && req.method === 'POST') {
      const body = await readJson(req);
      const name = String(body.name).replace(/[^\w]/g, '');
      const dir = path.join(DATA, 'render', name);
      fs.mkdirSync(path.join(WEB, 'clips'), { recursive: true });
      const out = path.join(WEB, 'clips', name + '.mp4');
      await run('ffmpeg', ['-y', '-framerate', String(body.fps || 30), '-i', path.join(dir, '%05d.jpg'), '-c:v', 'libx264', '-preset', 'slow', '-crf', '26', '-pix_fmt', 'yuv420p', '-movflags', '+faststart', '-an', out]);
      fs.rmSync(dir, { recursive: true, force: true });
      return json(res, 200, { ok: true, file: '/clips/' + name + '.mp4', bytes: fs.statSync(out).size });
    }

    if (p === '/api/groups' && req.method === 'POST') {
      const body = await readJson(req);
      const g = makeGroup(newCode(), body.scenarioId || 'gyeongju', body.lang || 'ko');
      console.log('[group] new', g.code, g.scenarioId, g.lang);
      return json(res, 200, summary(g));
    }

    let m = p.match(/^\/api\/groups\/(\d{4})(\/[a-z]+)?$/);
    if (m) {
      const g = groups.get(m[1]);
      if (!g) return json(res, 404, { error: 'no such group' });
      const sub = m[2] || '';
      if (!sub && req.method === 'GET') return json(res, 200, summary(g));
      if (sub === '/record' && req.method === 'POST') {
        g.record = await readJson(req);
        g.ended = true;
        fs.writeFileSync(path.join(DATA, g.code, 'record.json'), JSON.stringify(g.record));
        broadcast(g, { type: 'record', record: g.record });
        return json(res, 200, { ok: true });
      }
      if (sub === '/photos' && req.method === 'POST') {
        const body = await readJson(req);
        const mm = /^data:image\/(jpeg|jpg|png|webp);base64,(.+)$/.exec(body.dataUrl || '');
        if (!mm) return json(res, 400, { error: 'bad image' });
        const id = crypto.randomUUID().slice(0, 8);
        const ext = mm[1] === 'png' ? 'png' : mm[1] === 'webp' ? 'webp' : 'jpg';
        fs.writeFileSync(path.join(DATA, g.code, 'photos', id + '.' + ext), Buffer.from(mm[2], 'base64'));
        const photo = { id, url: '/media/' + g.code + '/photos/' + id + '.' + ext, takenAt: Number(body.takenAt) || Date.now(), by: body.by || '', name: body.name || '' };
        g.photos.push(photo);
        g.photos.sort((a, b) => a.takenAt - b.takenAt);
        broadcast(g, { type: 'photo', photo }, { cars: false });
        return json(res, 200, photo);
      }
      if (sub === '/dashcam' && req.method === 'POST') {
        const buf = await readBody(req);
        const name = (url.searchParams.get('name') || 'dashcam.mp4').replace(/[^\w.\-]/g, '_');
        const file = path.join(DATA, g.code, 'cam-' + Date.now() + '-' + name);
        fs.writeFileSync(file, buf);
        const lastModified = Number(url.searchParams.get('lastModified')) || Date.now();
        g.highlight = { status: 'processing', startedAt: Date.now() };
        broadcast(g, { type: 'highlight', highlight: g.highlight }, { cars: false });
        makeHighlight(g, file, name, lastModified).catch(e => {
          console.error('[highlight]', e);
          g.highlight = { status: 'error', message: String(e.message || e) };
          broadcast(g, { type: 'highlight', highlight: g.highlight }, { cars: false });
        });
        return json(res, 200, { ok: true });
      }
      if (sub === '/report' && req.method === 'POST') {
        const body = await readJson(req);
        const r = { placeId: body.placeId || '', name: body.name || '', note: String(body.note || '').slice(0, 500), by: body.by || '', at: Date.now() };
        g.reports.push(r);
        fs.appendFileSync(path.join(DATA, g.code, 'reports.jsonl'), JSON.stringify(r) + '\n');
        broadcast(g, Object.assign({ type: 'report' }, r), { phones: false });
        return json(res, 200, { ok: true });
      }
      // 차가 실제로 읽은 문장과 시각 (시연 영상에 음성을 다시 입힐 때 씀)
      if (sub === '/said' && req.method === 'GET') return json(res, 200, g.said || []);
      return json(res, 404, { error: 'unknown' });
    }

    m = p.match(/^\/api\/scenarios\/([a-z0-9_-]+)$/);
    if (m) return serveFile(res, path.join(SCENARIOS, m[1] + '.json'), req);

    m = p.match(/^\/media\/(\d{4})\/(.+)$/);
    if (m) {
      const file = path.normalize(path.join(DATA, m[1], m[2]));
      if (!file.startsWith(path.join(DATA, m[1]))) { res.writeHead(403); return res.end(); }
      return serveFile(res, file, req);
    }

    if (p === '/' || p === '/index.html') return serveFile(res, path.join(WEB, 'index.html'), req);
    if (/^\/g\/\d{4}(\/record)?\/?$/.test(p)) return serveFile(res, path.join(WEB, 'group.html'), req);

    const file = path.normalize(path.join(WEB, p));
    if (!file.startsWith(WEB)) { res.writeHead(403); return res.end(); }
    return serveFile(res, file, req);
  } catch (e) {
    console.error(e);
    json(res, 500, { error: String(e.message || e) });
  }
});

// ---------------------------------------------------------------- WebSocket
const wss = new WebSocketServer({ server, path: '/ws' });
wss.on('connection', (ws, req) => {
  const url = new URL(req.url, 'http://x');
  const code = url.searchParams.get('code');
  const role = url.searchParams.get('role') === 'car' ? 'car' : 'phone';
  const name = (url.searchParams.get('name') || (role === 'car' ? '1호차' : '')).slice(0, 30);
  let g = groups.get(code);
  if (!g) {
    if (role !== 'car') { send(ws, { type: 'error', error: 'no such group' }); return ws.close(); }
    g = makeGroup(code, 'gyeongju', 'ko');
  }
  const id = crypto.randomUUID();
  if (role === 'car') {
    g.cars.set(id, { id, name, ws });
    broadcast(g, { type: 'car_joined', name }, { except: ws });
  } else {
    g.phones.add(ws);
    broadcast(g, { type: 'phone_joined' }, { phones: false });
  }
  console.log('[ws]', code, role, name, 'cars', g.cars.size, 'phones', g.phones.size);
  send(ws, Object.assign({ type: 'hello', role }, summary(g)));
  members(g);

  ws.on('message', data => {
    let msg; try { msg = JSON.parse(String(data)); } catch { return; }
    const type = msg.type;
    if (role === 'car') {
      const car = g.cars.get(id);
      msg.car = car ? car.name : name;
      if (type === 'story') { g.story = msg; g.line = null; broadcast(g, msg, { cars: false }); }
      else if (type === 'line') { g.line = msg; broadcast(g, msg, { cars: false }); }
      else if (type === 'story_end') broadcast(g, msg, { cars: false });
      else if (type === 'pass') { g.passes.push({ placeId: msg.placeId, name: msg.name, at: msg.at, car: msg.car }); broadcast(g, msg, { cars: false }); }
      else if (type === 'trip') { g.scenarioId = msg.scenarioId || g.scenarioId; g.lang = msg.lang || g.lang; broadcast(g, msg, { cars: false }); }
      else if (type === 'pick') { msg.id = msg.id || crypto.randomUUID().slice(0, 8); g.picks.push(msg); broadcast(g, msg, { except: ws }); }
      else if (type === 'pick_ack') { g.acks.push(msg); broadcast(g, msg, { except: ws }); }
      else if (type === 'say') { (g.said = g.said || []).push({ at: Date.now(), text: msg.text, lang: msg.lang, kind: msg.kind, car: msg.car }); }
      else if (type === 'window' || type === 'trip_end') broadcast(g, msg, { cars: false });
      else if (type === 'quiz') {
        const q = { id: String(msg.id), name: msg.name || '', q: msg.q || '', choices: Array.isArray(msg.choices) ? msg.choices.map(String) : [], answer: Number(msg.answer), lang: msg.lang || g.lang, at: Date.now(), car: msg.car };
        g.quizzes[q.id] = q;
        broadcast(g, Object.assign({ type: 'quiz', stars: msg.stars, need: msg.need }, publicQuiz(q)), { cars: false });
      }
      else if (type === 'qa') {
        const item = { q: String(msg.q || '').slice(0, 200), a: String(msg.a || '').slice(0, 1200), title: msg.title || '', placeId: msg.placeId || '', at: msg.at || Date.now(), car: msg.car };
        g.qa.push(item);
        if (g.qa.length > 50) g.qa.shift();
        broadcast(g, Object.assign({ type: 'qa' }, item), { cars: false });
      }
      else if (type === 'handoff') { g.handoff = { placeId: msg.placeId, name: msg.name, text: msg.text, lang: msg.lang, at: Date.now(), car: msg.car }; broadcast(g, Object.assign({ type: 'handoff' }, g.handoff), { cars: false }); }
      else if (type === 'treasure') { g.treasure = { stars: msg.stars, unlocked: !!msg.unlocked, at: Date.now(), car: msg.car }; broadcast(g, Object.assign({ type: 'treasure' }, g.treasure), { cars: false }); }
      else if (type === 'stars') { g.stars = { stars: msg.stars, need: msg.need, ready: !!msg.ready, by: msg.by || '' }; broadcast(g, Object.assign({ type: 'stars' }, g.stars), { cars: false }); }
      else if (type === 'kid') broadcast(g, msg, { cars: false });
    } else {
      if (type === 'pick') {
        const pick = { type: 'pick', id: crypto.randomUUID().slice(0, 8), name: String(msg.name || '').slice(0, 60), lat: Number(msg.lat), lon: Number(msg.lon), kind: msg.kind || 'place', by: String(msg.by || '').slice(0, 20) || '동승자', fromCar: false, at: Date.now() };
        if (!Number.isFinite(pick.lat) || !Number.isFinite(pick.lon)) return;
        g.picks.push(pick);
        broadcast(g, pick);
      }
      else if (type === 'quiz_answer') {
        // 채점은 서버가 한다. 휴대폰 한 대가 같은 퀴즈에 한 번만 답할 수 있다
        const q = g.quizzes[String(msg.id)];
        if (!q) return;
        const pid = String(msg.pid || id).slice(0, 40);
        if (g.answers.some(a => a.id === q.id && a.pid === pid)) return;
        const choice = Number(msg.choice);
        const a = { id: q.id, by: String(msg.by || '').slice(0, 20) || '동승자', pid, choice, correct: choice === q.answer, at: Date.now() };
        g.answers.push(a);
        console.log('[quiz]', g.code, q.id, a.by, a.correct ? '정답' : '오답');
        broadcast(g, Object.assign({ type: 'quiz_answer', answer: q.answer, scores: scores(g) }, publicAnswer(a)));
      }
    }
  });
  ws.on('close', () => {
    if (role === 'car') g.cars.delete(id); else g.phones.delete(ws);
    members(g);
  });
});

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
  const probe = JSON.parse(await run('ffprobe', ['-v', 'error', '-show_entries', 'format=duration', '-of', 'json', file]));
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
  const args = ['-y', '-i', file];
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
      f += ",drawtext=fontfile='" + FONT + "':text='" + esc(c.title) + "':x=48:y=h-110:fontsize=54:fontcolor=white:box=1:boxcolor=black@0.45:boxborderw=18";
      parts.push(f + '[v' + i + ']');
    } else if (captions[i]) {
      parts.push(f + '[b' + i + ']');
      parts.push('[b' + i + '][' + (capInput++) + ':v]overlay=0:0:shortest=1[v' + i + ']');
    } else parts.push(f + '[v' + i + ']');
  });
  const filter = parts.join(';') + ';' + clips.map((_, i) => '[v' + i + ']').join('') + 'concat=n=' + clips.length + ':v=1:a=0[out]';
  args.push('-filter_complex', filter, '-map', '[out]', '-c:v', 'libx264', '-preset', 'veryfast', '-crf', '23', '-pix_fmt', 'yuv420p', '-movflags', '+faststart', out);
  await run('ffmpeg', args);
  g.highlight = { status: 'done', url: '/media/' + g.code + '/highlight.mp4?v=' + Date.now(), clips: clips.map(c => c.title), mode };
  broadcast(g, { type: 'highlight', highlight: g.highlight }, { cars: false });
  console.log('[highlight] done', g.code, clips.length, mode);
}

server.listen(PORT, '0.0.0.0', () => {
  console.log('구르는 가이드 그룹 서버: ' + PUBLIC_BASE + ' (에뮬레이터에서는 http://10.0.2.2:' + PORT + ')');
});

// ffmpeg 자막 기능 확인
run('ffmpeg', ['-hide_banner', '-filters']).then(out => { HAS_DRAWTEXT = /\bdrawtext\b/.test(out); }).catch(() => console.warn('ffmpeg 가 없어 하이라이트 영상을 만들 수 없어요'));
run('python3', ['-c', 'import PIL']).then(() => { HAS_PIL = true; }).catch(() => {});
