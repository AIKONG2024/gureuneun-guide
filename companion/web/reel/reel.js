// 구르는 가이드 여행 영상
//   휴대폰: http://<서버>:8787/reel/GJ1016    차량: file:///android_asset/reel/index.html?code=GJ1016&car=1
// 1분 남짓 자동 재생되는 HTML 애니메이션. 1280x720 무대를 화면에 맞춰 16:9 로 꽉 채우고 남는 곳은 검정
// 데이터 순서: window.CarReel.data()(차량 JavascriptInterface, trip JSON 문자열 + "base") → window.REEL_DATA → ../api/trip/:code
// 자원은 모두 상대 주소(../photos ../audio ../map ../vendor ../data). '/uploads/..' 사진은 base(또는 ?server=)를 앞에 붙임
// car=1 이면 소리까지 자동 재생, 끝나면 CarReel.ended() 를 부름
// 확인용: ?t=23.5 는 그 시각에 멈춘 화면. 키보드 스페이스 멈춤/재생, 좌우 화살표 5초 이동. window.reel.seek(초)
(() => {
  'use strict';

  const Q = new URLSearchParams(location.search);
  const CAR = Q.get('car') === '1';
  const FREEZE = Q.has('t') ? Math.max(0, Number(Q.get('t')) || 0) : null;
  const CODE = (Q.get('code') || (location.pathname.match(/\/reel\/([A-Za-z0-9_-]+)/) || [])[1] || 'GJ1016').toUpperCase();
  const IS_FILE = location.protocol === 'file:';
  const W = 1280, H = 720, XF = 1.0;
  let BASE = String(Q.get('server') || window.REEL_SERVER || '').replace(/\/+$/, '');
  // 차량에서 data/*.json 을 못 읽을 때만 쓰는 최소 지역 정보(data/gyeongju.json 에서 옮김)
  const FALLBACK_REGION = {"id":"gyeongju","name":"경주","gate":{"name":"경주 요금소","lat":35.80687,"lon":129.1854,"approachM":1500,"enterM":150},"theme":{"name":"신라 금빛","color":"#E3B35A","deep":"#B8862F","welcome":"여기부터 신라예요","instrument":"가야금"},"sights":[{"id":"cheonmachong","name":"천마총","tag":"사적","lat":35.83859,"lon":129.2105,"photo":"cheonmachong_1.jpg","short":"하늘을 나는 흰 말 그림이 나온 신라 무덤이에요"},{"id":"daereungwon","name":"대릉원","tag":"사적","lat":35.83895,"lon":129.21193,"photo":"daereungwon_1.jpg","short":"풀로 덮인 큰 언덕이 모두 신라 왕족의 무덤이에요"},{"id":"cheomseongdae","name":"첨성대","tag":"국보","lat":35.8347,"lon":129.21898,"photo":"cheomseongdae_1.jpg","short":"신라 선덕여왕 때 세운 천문대예요"},{"id":"gyerim","name":"계림","tag":"사적","lat":35.83278,"lon":129.21889,"photo":"gyerim_1.jpg","short":"신라가 시작될 때부터 있던 숲이에요"},{"id":"wolseong","name":"월성","tag":"사적","lat":35.83083,"lon":129.22611,"photo":"wolseong_1.jpg","short":"신라 궁궐이 있던 초승달 모양 언덕이에요"},{"id":"gyochon","name":"교촌마을","tag":"","lat":35.83083,"lon":129.21611,"photo":"gyochon_1.jpg","short":"경주 최씨 고택이 있는 한옥 마을이에요"},{"id":"woljeonggyo","name":"월정교","tag":"","lat":35.82944,"lon":129.21806,"photo":"woljeonggyo_1.jpg","short":"통일신라 때 놓인 지붕 있는 다리예요"},{"id":"donggung","name":"동궁과 월지","tag":"사적","lat":35.8347,"lon":129.22644,"photo":"donggung_night_1.jpg","short":"신라 왕자가 지내던 궁궐과 연못이에요"}],"route":[[35.80687,129.1854],[35.80576,129.18575],[35.80571,129.18463],[35.80678,129.18469],[35.80833,129.18714],[35.80944,129.18925],[35.81121,129.1928],[35.81402,129.19888],[35.81531,129.20168],[35.81738,129.20613],[35.81938,129.21044],[35.82084,129.21052],[35.82334,129.21066],[35.82509,129.21044],[35.82672,129.20971],[35.82838,129.20966],[35.8312,129.20958],[35.83301,129.2089],[35.83299,129.2072],[35.83494,129.20718],[35.83835,129.20712],[35.83913,129.20757],[35.83953,129.20992],[35.83927,129.21137],[35.83935,129.213],[35.83835,129.21372],[35.83716,129.21414],[35.83612,129.21501],[35.83512,129.21632],[35.83568,129.21784],[35.83576,129.21949],[35.83585,129.22176],[35.83323,129.22638],[35.83156,129.2291],[35.83325,129.22641],[35.83369,129.2256]],"credits":{"gyeongju_1.jpg":{"author":"경주시청 시정뉴스","license":"KOGL Type 1"},"gyeongju_2.jpg":{"author":"Basile Morin","license":"CC BY-SA 4.0"},"daereungwon_1.jpg":{"author":"Bernard Gagnon","license":"CC0"},"daereungwon_2.jpg":{"author":"Basile Morin","license":"CC BY-SA 4.0"},"daereungwon_3.jpg":{"author":"Basile Morin","license":"CC BY-SA 4.0"},"cheomseongdae_1.jpg":{"author":"Basile Morin","license":"CC BY-SA 4.0"},"cheomseongdae_window.jpg":{"author":"Dudva","license":"CC0"},"cheomseongdae_night_1.jpg":{"author":"Kyklyj","license":"CC BY-SA 4.0"},"donggung_night_1.jpg":{"author":"Basile Morin","license":"CC BY-SA 4.0"},"donggung_night_2.jpg":{"author":"hyungname from Pixabay","license":"CC0"},"cheonmachong_1.jpg":{"author":"De-Shao Liu (Terry850324)","license":"CC BY-SA 4.0"},"gyerim_1.jpg":{"author":"Amlou2518","license":"CC BY-SA 4.0"},"gyerim_night_1.jpg":{"author":"한솔임","license":"CC BY-SA 4.0"},"wolseong_1.jpg":{"author":"螺钉","license":"CC BY-SA 3.0"},"woljeonggyo_1.jpg":{"author":"Bernard Gagnon","license":"CC0"},"woljeonggyo_night_1.jpg":{"author":"Basile Morin","license":"CC BY-SA 4.0"},"gyochon_1.jpg":{"author":"Seefooddiet","license":"CC BY-SA 4.0"},"museum_1.jpg":{"author":"Abasaa","license":"Public domain"},"hwangridan_1.jpg":{"author":"Seefooddiet","license":"CC BY-SA 4.0"}}};
  // 차량 WebView 가 file:// 의 map/*.json 을 못 읽을 때 쓰는 지도 그림 정보(map/gyeongju_overview.json 에서 옮김). 그림 파일이 없으면 쓰지 않음
  const FALLBACK_MAP = {"gyeongju":{"width":1920,"height":1080,"bounds":[[35.79808592787606,129.1530572938354],[35.84710021322509,129.2605227061655]]}};
  const DEFAULT_MEMBERS = [
    { id: 'dad', name: '아빠', color: '#0B4A8F' }, { id: 'mom', name: '엄마', color: '#B8456E' },
    { id: 'kid1', name: '첫째', color: '#1F7A5C' }, { id: 'kid2', name: '둘째', color: '#6B4FC8' },
  ];
  const KIND_ICON = { sight: 'landmark', cafe: 'coffee', food: 'utensils', stay: 'bed-double' };
  const LOGO = '<svg width="24" height="18" viewBox="0 0 26 20" fill="none" stroke-width="2.6" stroke-linecap="round" aria-hidden="true"><path d="M2 7c4-4 8 4 12 0s8 4 10 0" stroke="#8FE3F5"/><path d="M2 14c4-4 8 4 12 0s8 4 10 0" stroke="#E0B27A"/></svg>';

  const view = document.getElementById('view');
  const stage = document.getElementById('stage');
  const $ = (s, el) => (el || document).querySelector(s);
  const $$ = (s, el) => Array.from((el || document).querySelectorAll(s));
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const arr = (a) => (Array.isArray(a) ? a : []);
  const clamp = (x, a, b) => Math.min(b == null ? 1 : b, Math.max(a == null ? 0 : a, x));
  const easeOut = (x) => 1 - Math.pow(1 - clamp(x), 3);
  const easeIO = (x) => { x = clamp(x); return x < 0.5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2; };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const fmt = (t) => { t = Math.max(0, Math.round(t)); return Math.floor(t / 60) + ':' + String(t % 60).padStart(2, '0'); };
  const durLong = (t) => { t = Math.round(t); const m = Math.floor(t / 60); return (m ? m + '분 ' : '') + String(t % 60).padStart(2, '0') + '초'; };

  function ic(name, size, sw) {
    const pascal = name.replace(/(^|-)([a-z0-9])/g, (m, a, b) => b.toUpperCase());
    const node = window.lucide && window.lucide.icons && window.lucide.icons[pascal];
    if (!node) return '';
    return '<svg class="ic" xmlns="http://www.w3.org/2000/svg" width="' + (size || 20) + '" height="' + (size || 20) + '" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="' + (sw || 2) +
      '" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + node.map(([t, a]) => '<' + t + Object.entries(a).map(([k, v]) => ' ' + k + '="' + v + '"').join('') + '/>').join('') + '</svg>';
  }
  function josa(word, a, b) {
    const s = String(word || '');
    const c = s.charCodeAt(s.length - 1);
    if (!(c >= 0xac00 && c <= 0xd7a3)) return s + b;
    return s + ((c - 0xac00) % 28 ? a : b);
  }

  // ------------------------------------------------------------ 데이터
  // file:// 에서도 되게 XHR 로 읽음(차량 WebView 는 allowFileAccessFromFileURLs 가 꺼져 있으면 실패 → base 서버에서 다시 시도)
  function getJSON(url, timeout) {
    return new Promise((resolve) => {
      try {
        const x = new XMLHttpRequest();
        x.open('GET', url, true);
        x.timeout = timeout || 3000;
        x.onload = () => {
          if ((x.status >= 200 && x.status < 300) || (x.status === 0 && x.responseText)) {
            try { resolve(JSON.parse(x.responseText)); } catch (e) { resolve(null); }
          } else resolve(null);
        };
        x.onerror = () => resolve(null);
        x.ontimeout = () => resolve(null);
        x.send();
      } catch (e) { resolve(null); }
    });
  }
  async function getAsset(rel) {
    let j = await getJSON('../' + rel, 2500);
    if (!j && BASE) j = await getJSON(BASE + '/' + rel, 2500);
    return j;
  }
  function readCar() {
    try {
      const c = window.CarReel;
      if (c && typeof c.data === 'function') {
        const raw = c.data();
        const d = typeof raw === 'string' ? JSON.parse(raw) : raw;
        if (d && typeof d === 'object') return d;
      }
    } catch (e) { console.warn('CarReel.data() 를 읽지 못했어요', e); }
    return null;
  }
  function unwrap(d) {
    if (!d || typeof d !== 'object') return null;
    if (d.trip && typeof d.trip === 'object' && !d.members && !d.places) {
      const t = Object.assign({}, d.trip);
      if (d.base && !t.base) t.base = d.base;
      return t;
    }
    return d;
  }
  async function loadTrip() {
    for (let i = 0; ; i++) {
      const c = unwrap(readCar());
      if (c) { S.source = 'CarReel'; return c; }
      const r = unwrap(window.REEL_DATA);
      if (r) { S.source = 'REEL_DATA'; return r; }
      if (!CAR || i >= 15) break; // 차량은 데이터가 조금 늦게 들어올 수 있어 1.5초까지 기다림
      await sleep(100);
    }
    const api = IS_FILE ? (BASE || 'http://10.0.2.2:8787') + '/api/trip/' + encodeURIComponent(CODE) : '../api/trip/' + encodeURIComponent(CODE);
    const r = await getJSON(api, 3500);
    if (r && r.trip) { S.source = 'api'; return r.trip; }
    S.source = 'seed';
    return (await getAsset('data/trip_seed.json')) || {};
  }

  function photoUrl(p) {
    if (!p) return null;
    p = String(p);
    if (/^(https?:|data:|blob:)/.test(p)) return p;
    if (/^\/?uploads\//.test(p)) {
      const path = '/' + p.replace(/^\//, '');
      if (BASE) return BASE + path;
      return IS_FILE ? 'http://10.0.2.2:8787' + path : path;
    }
    if (p.startsWith('/web/')) return '../' + p.slice(5);
    if (p.startsWith('/')) return '..' + p;
    if (p.includes('/')) return '../' + p.replace(/^(\.\.?\/)+/, '').replace(/^web\//, '');
    return '../photos/' + p;
  }

  const bad = new Set();
  function preload(urls, timeout) {
    const list = Array.from(new Set(urls.filter(Boolean)));
    return Promise.race([
      Promise.all(list.map((u) => new Promise((res) => {
        const im = new Image();
        im.onload = () => res();
        im.onerror = () => { bad.add(u); res(); };
        im.src = u;
      }))),
      sleep(timeout),
    ]);
  }

  // ------------------------------------------------------------ 상태
  const S = { trip: null, region: null, catalog: [], credits: {}, map: null, rec: null, scenes: [], T: 60, t: 0, playing: false, ended: false, last: 0 };
  let members = DEFAULT_MEMBERS;
  const member = (id) => members.find((m) => m.id === id) || null;
  const sightOf = (id) => arr(S.region && S.region.sights).find((s) => s.id === id) || null;
  function placeOf(id) {
    const tp = arr(S.trip.places).find((p) => p && p.id === id) || null;
    const c = S.catalog.find((p) => p && p.id === id) || null;
    const s = sightOf(id);
    if (!tp && !c && !s) return null;
    const v = (k) => (tp && tp[k] != null && tp[k] !== '' ? tp[k] : c && c[k] != null && c[k] !== '' ? c[k] : s ? s[k] : null);
    return { id, name: v('name'), kind: v('kind') || 'sight', by: tp && tp.by, time: tp && tp.time, photo: v('photo'), area: v('area') || '', lat: Number(v('lat')), lon: Number(v('lon')) };
  }
  function credit(p) {
    if (!p) return '';
    if (/uploads\//.test(p)) return '';
    const c = S.credits[String(p).split('/').pop()];
    return c ? '사진 ' + c.author + ' · ' + c.license : '';
  }
  function dateLabel(d) {
    const m = /^(\d{4})\D(\d{1,2})\D(\d{1,2})/.exec(String(d || ''));
    if (!m) return String(d || '');
    const day = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3])).getDay();
    return m[1] + '.' + m[2].padStart(2, '0') + '.' + m[3].padStart(2, '0') + ' ' + '일월화수목금토'[day] + '요일';
  }
  function parseDur(s) {
    const m = /^(\d+):(\d{1,2})$/.exec(String(s || '').trim());
    return m ? Number(m[1]) * 60 + Number(m[2]) : 0;
  }

  function normRecord() {
    const t = S.trip;
    const r = Object.assign({}, t.record || {});
    const ps = arr(t.places).filter(Boolean);
    if (!arr(r.visited).length) r.visited = ps.filter((p) => p.status === 'visited').map((p) => p.id);
    r.visited = arr(r.visited).filter((id) => placeOf(id));
    if (!arr(r.stories).length) r.stories = arr(t.heard).filter((id) => !r.visited.includes(id)).slice(0, 3);
    r.stories = arr(r.stories).filter((id) => sightOf(id));
    r.photos = arr(r.photos);
    r.title = r.title || '오늘의 ' + (t.to || (S.region && S.region.name) || '여행');
    r.entryAt = r.entryAt || (t.mode && t.mode.enteredAt) || '';
    r.storyCount = r.storyCount != null ? r.storyCount : Math.max(arr(t.heard).length, r.stories.length);
    r.photoCount = r.photoCount != null ? r.photoCount : r.photos.length;
    return r;
  }

  // ------------------------------------------------------------ 장면 도구
  function el(cls, html) {
    const d = document.createElement('div');
    d.className = cls;
    d.innerHTML = html;
    return d;
  }
  function bgImg(url, pos) {
    return '<img class="bgimg" src="' + esc(url) + '" alt="" style="object-position:' + (pos || 'center') + '">';
  }
  // 켄번스: 사진을 천천히 확대하며 조금씩 흘림
  function kb(img, p, v) {
    if (!img) return;
    const s = v === 3 ? 1.17 - 0.12 * p : 1.04 + 0.12 * p;
    const dx = v === 1 ? -2.2 + 4.4 * p : v === 2 ? 2.2 - 4.4 * p : 0;
    const dy = v === 0 ? 1.2 - 2.4 * p : v === 3 ? -1 + 2 * p : 0;
    img.style.transform = 'translate(' + dx.toFixed(3) + '%,' + dy.toFixed(3) + '%) scale(' + s.toFixed(4) + ')';
  }
  // data-at(초) 가 붙은 글자는 그 시각에 아래에서 떠오름, data-to 숫자는 세어 올라감
  function revealer(root) {
    const items = $$('[data-at]', root).map((e) => ({ e, at: Number(e.dataset.at) || 0, dy: e.dataset.dy != null ? Number(e.dataset.dy) : 20, dur: Number(e.dataset.dur) || 0.75 }));
    const cnt = $$('[data-to]', root).map((e) => ({ e, to: Number(e.dataset.to) || 0, at: Number(e.dataset.cat) || 0, dur: Number(e.dataset.cdur) || 1.3, last: '' }));
    return (lt) => {
      for (const it of items) {
        const p = easeOut((lt - it.at) / it.dur);
        it.e.style.opacity = p.toFixed(3);
        it.e.style.transform = p >= 1 ? '' : 'translateY(' + ((1 - p) * it.dy).toFixed(2) + 'px)';
      }
      for (const c of cnt) {
        const v = Math.round(c.to * easeOut((lt - c.at) / c.dur)).toLocaleString('ko-KR');
        if (v !== c.last) { c.e.textContent = v; c.last = v; }
      }
    };
  }

  // ------------------------------------------------------------ 1 제목
  function sceneTitle() {
    const t = S.trip;
    const photo = (S.region && S.region.cover) || 'gyeongju_2.jpg';
    const url = photoUrl(photo);
    const ms = members;
    const root = el('sc sc-title', (bad.has(url) ? '' : bgImg(url, 'center 62%')) + '<div class="shade sh-title"></div>' +
      '<div class="tt"><div class="tt-k" data-at=".35"><span class="logo">' + LOGO + '</span>구르는 가이드 여행 영상</div>' +
      '<h1 data-at=".6" data-dy="28">' + esc(S.rec.title) + '</h1>' +
      '<div class="tt-d" data-at="1.05">' + esc(dateLabel(t.date)) + (t.from && t.to ? ' · ' + esc(t.from) + '에서 ' + esc(t.to) + '까지' : '') + '</div>' +
      '<div class="tt-m" data-at="1.45">' + ms.map((m) => '<span class="av" style="background:' + esc(m.color) + '">' + esc(String(m.name).slice(0, 1)) + '</span>').join('') +
      '<span>' + esc(ms.map((m) => m.name).join(' · ')) + '</span></div></div>');
    const img = $('.bgimg', root);
    const rv = revealer(root);
    return { root, credit: bad.has(url) ? '' : credit(photo), update(lt, d) { kb(img, lt / d, 0); rv(lt); } };
  }

  // ------------------------------------------------------------ 2 지도와 이동 경로
  function sceneMap() {
    const t = S.trip;
    const r = S.rec;
    const region = S.region || {};
    const map = S.map;
    const merc = (lat) => Math.log(Math.tan(Math.PI / 4 + (lat * Math.PI) / 360));
    let proj;
    if (map) {
      const [[s, w], [n, e]] = map.bounds;
      proj = (lat, lon) => [((lon - w) / (e - w)) * map.width, ((merc(n) - merc(lat)) / (merc(n) - merc(s))) * map.height];
    } else {
      proj = (lat, lon) => [lon * 1000, (-merc(lat) * 180 * 1000) / Math.PI];
    }
    const ok = (a, b) => isFinite(a) && isFinite(b) && a !== 0 && b !== 0;
    const vis = r.visited.map(placeOf).filter((p) => p && ok(p.lat, p.lon));
    let route = arr(region.route).filter((p) => Array.isArray(p) && ok(Number(p[0]), Number(p[1])));
    if (route.length < 2) {
      route = [];
      if (region.gate && ok(region.gate.lat, region.gate.lon)) route.push([region.gate.lat, region.gate.lon]);
      vis.forEach((p) => route.push([p.lat, p.lon]));
    }
    const wpts = route.map(([la, lo]) => proj(Number(la), Number(lo)));
    const pins = [];
    if (region.gate && ok(region.gate.lat, region.gate.lon)) pins.push({ gate: true, name: region.gate.name, sub: r.entryAt, w: proj(region.gate.lat, region.gate.lon) });
    vis.forEach((p) => { const m = member(p.by); pins.push({ name: p.name, color: m ? m.color : '#00AAD2', w: proj(p.lat, p.lon) }); });
    const others = map ? [] : arr(region.sights).filter((s) => !r.visited.includes(s.id) && ok(s.lat, s.lon)).map((s) => ({ name: s.name, w: proj(s.lat, s.lon) }));

    let svg = '';
    let len = 0;
    let fit = null;
    if (wpts.length >= 2) {
      const all = wpts.concat(pins.map((p) => p.w));
      let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
      for (const [x, y] of all) { x0 = Math.min(x0, x); x1 = Math.max(x1, x); y0 = Math.min(y0, y); y1 = Math.max(y1, y); }
      const box = { x0: 560, x1: 1190, y0: 120, y1: 610 };
      let sc = Math.min((box.x1 - box.x0) / Math.max(1e-9, x1 - x0), (box.y1 - box.y0) / Math.max(1e-9, y1 - y0));
      // 지도 그림은 화면을 빈틈없이 덮게(최소 cover 배율), 너무 키우면 흐려지니 2.4배까지
      if (map) sc = Math.max(Math.min(sc, 2.4), W / map.width, H / map.height);
      let ox = (box.x0 + box.x1) / 2 - ((x0 + x1) / 2) * sc;
      let oy = (box.y0 + box.y1) / 2 - ((y0 + y1) / 2) * sc;
      if (map) {
        const iw = map.width * sc, ih = map.height * sc;
        if (iw >= W) ox = Math.min(0, Math.max(W - iw, ox));
        if (ih >= H) oy = Math.min(0, Math.max(H - ih, oy));
      }
      const P = ([x, y]) => [x * sc + ox, y * sc + oy];
      const pts = wpts.map(P);
      const cum = [0];
      for (let i = 1; i < pts.length; i++) cum.push(cum[i - 1] + Math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]));
      const total = cum[cum.length - 1] || 1;
      pins.forEach((p) => {
        p.s = P(p.w);
        let bi = 0, bd = Infinity;
        pts.forEach((q, i) => { const dd = Math.hypot(q[0] - p.s[0], q[1] - p.s[1]); if (dd < bd) { bd = dd; bi = i; } });
        p.f = p.gate ? 0 : cum[bi] / total;
      });
      // 이름표가 핀과 다른 이름표를 가리지 않게 오른쪽 → 왼쪽 → 위 → 아래 순으로 자리를 고름
      const boxes = pins.map((p) => [p.s[0] - 13, p.s[1] - 13, p.s[0] + 13, p.s[1] + 13]);
      const textW = (s, fs) => Array.from(String(s)).reduce((a, ch) => a + (/[가-힣]/.test(ch) ? 1.02 : 0.55), 0) * fs;
      const hit = (b) => b[0] < 8 || b[2] > W - 8 || b[1] < 64 || b[3] > H - 64 || boxes.some((o) => !(b[2] < o[0] || b[0] > o[2] || b[3] < o[1] || b[1] > o[3]));
      const place = (x, y, wd, hd, must) => {
        const cands = [[x + 18, y + 7, 'start'], [x - 18, y + 7, 'end'], [x + 14, y - 20, 'start'], [x + 14, y + 36, 'start'], [x - 14, y - 20, 'end'], [x - 14, y + 36, 'end']];
        for (const [tx, ty, anchor] of cands) {
          const bx = anchor === 'end' ? tx - wd : tx;
          const b = [bx - 3, ty - hd, bx + wd + 3, ty + 6];
          if (!hit(b)) { boxes.push(b); return [tx, ty, anchor]; }
        }
        if (!must) return null;
        boxes.push([x + 15, y - 15, x + 21 + wd, y + 13]);
        return [x + 18, y + 7, 'start'];
      };
      pins.forEach((p) => { p.lab = place(p.s[0], p.s[1], textW(p.name, 19) + (p.sub ? 56 : 0), 22, true); });
      const d = 'M' + pts.map(([x, y]) => x.toFixed(1) + ' ' + y.toFixed(1)).join(' L');
      svg += '<svg class="map" viewBox="0 0 1280 720" xmlns="http://www.w3.org/2000/svg">';
      if (map) {
        svg += '<image href="' + esc(map.url) + '" x="' + ox.toFixed(1) + '" y="' + oy.toFixed(1) + '" width="' + (map.width * sc).toFixed(1) + '" height="' + (map.height * sc).toFixed(1) + '" preserveAspectRatio="none"/>';
      } else {
        for (let gx = 0; gx <= W; gx += 64) svg += '<line class="gl" x1="' + gx + '" y1="0" x2="' + gx + '" y2="720"/>';
        for (let gy = 0; gy <= H; gy += 64) svg += '<line class="gl" x1="0" y1="' + gy + '" x2="1280" y2="' + gy + '"/>';
        others.forEach((o) => {
          const [x, y] = P(o.w);
          if (x < 520 || x > W - 20 || y < 70 || y > H - 80 || hit([x - 6, y - 6, x + 6, y + 6])) return;
          const lab = place(x, y, textW(o.name, 15), 17, false);
          svg += '<g class="other"><circle cx="' + x.toFixed(1) + '" cy="' + y.toFixed(1) + '" r="4.5"/>' +
            (lab ? '<text x="' + lab[0].toFixed(1) + '" y="' + (lab[1] - 2).toFixed(1) + '" text-anchor="' + lab[2] + '">' + esc(o.name) + '</text>' : '') + '</g>';
        });
      }
      svg += '<path class="route-bg" d="' + d + '"/><path class="route" d="' + d + '"/>';
      pins.forEach((p, i) => {
        const [x, y] = p.s;
        const [tx, ty, anchor] = p.lab;
        svg += '<g class="pin' + (p.gate ? ' gate' : '') + '" data-i="' + i + '" style="opacity:0">';
        if (p.gate) svg += '<rect x="' + (x - 9).toFixed(1) + '" y="' + (y - 9).toFixed(1) + '" width="18" height="18" rx="3" transform="rotate(45 ' + x.toFixed(1) + ' ' + y.toFixed(1) + ')"/>';
        else svg += '<circle cx="' + x.toFixed(1) + '" cy="' + y.toFixed(1) + '" r="10" fill="#fff" stroke="' + esc(p.color) + '" stroke-width="5"/>';
        svg += '<text x="' + tx.toFixed(1) + '" y="' + ty.toFixed(1) + '" text-anchor="' + anchor + '">' + esc(p.name) + (p.sub ? '<tspan class="sub" dx="8">' + esc(p.sub) + '</tspan>' : '') + '</text></g>';
      });
      svg += '<circle class="halo" r="20" cx="-99" cy="-99"/><circle class="cardot" r="9" cx="-99" cy="-99"/></svg>';
      fit = true;
    }
    const km = S.rec.km != null && isFinite(Number(S.rec.km)) ? Number(S.rec.km) : null;
    const root = el('sc sc-map', svg + '<div class="shade sh-map"></div>' +
      '<div class="mp"><div class="mp-k" data-at=".4">' + ic('route', 22) + '오늘 달린 길</div>' +
      '<div class="mp-r" data-at=".7">' + esc(t.from || '') + ic('arrow-right', 44, 2.4) + esc(t.to || '') + '</div>' +
      (km != null ? '<div class="mp-km" data-at="1.0"><span class="km">0</span><small>km</small></div>' : '') +
      '<div class="mp-s" data-at="1.4">' + esc([r.startAt ? r.startAt + ' 출발' : '', r.entryAt ? r.entryAt + ' ' + (t.to || '') + ' 도착' : ''].filter(Boolean).join(' · ')) + '</div></div>');
    const rv = revealer(root);
    const svgEl = $('svg.map', root);
    const routeEl = $('.route', root);
    const routeBg = $('.route-bg', root);
    const car = $('.cardot', root);
    const halo = $('.halo', root);
    const pinEls = $$('.pin', root);
    const kmEl = $('.km', root);
    let lastKm = '';
    return {
      root,
      credit: map ? map.credit : '',
      update(lt, dur) {
        rv(lt);
        const p = easeIO((lt - 0.9) / Math.max(1, dur * 0.64));
        if (fit && routeEl) {
          if (!len) {
            len = routeEl.getTotalLength() || 1;
            routeEl.style.strokeDasharray = routeBg.style.strokeDasharray = len.toFixed(1) + ' ' + len.toFixed(1);
          }
          const off = (len * (1 - p)).toFixed(1);
          routeEl.style.strokeDashoffset = off;
          routeBg.style.strokeDashoffset = off;
          const pt = routeEl.getPointAtLength(len * p);
          car.setAttribute('cx', pt.x.toFixed(1));
          car.setAttribute('cy', pt.y.toFixed(1));
          halo.setAttribute('cx', pt.x.toFixed(1));
          halo.setAttribute('cy', pt.y.toFixed(1));
          halo.setAttribute('r', (18 + 5 * Math.sin(lt * 5)).toFixed(1));
          pinEls.forEach((g) => {
            const pin = pins[Number(g.dataset.i)];
            const q = pin.gate ? easeOut((lt - 0.6) / 0.6) : easeOut((p - pin.f + 0.015) / 0.06);
            g.style.opacity = q.toFixed(3);
          });
          svgEl.style.transform = 'scale(' + (1 + 0.05 * clamp(lt / dur)).toFixed(4) + ')';
        }
        if (kmEl) {
          const v = Math.round(km * (fit ? p : easeOut((lt - 1) / 2))).toLocaleString('ko-KR');
          if (v !== lastKm) { kmEl.textContent = v; lastKm = v; }
        }
      },
    };
  }

  // ------------------------------------------------------------ 3 여행지 진입
  function sceneEntry() {
    const region = S.region || {};
    const theme = region.theme || {};
    const gate = region.gate || {};
    const photo = theme.photo || 'woljeonggyo_night_1.jpg';
    const url = photoUrl(photo);
    const sub = [region.name ? region.name + ' 여행 모드' : '', theme.name, theme.instrument].filter(Boolean).join(' · ');
    const root = el('sc sc-entry', (bad.has(url) ? '' : bgImg(url, 'center 55%')) + '<div class="tint"></div><div class="shade sh-entry"></div><div class="flash"></div><div class="gold-line"></div>' +
      '<div class="en"><div class="en-k" data-at=".45">' + esc([S.rec.entryAt, gate.name].filter(Boolean).join(' ')) + '</div>' +
      '<div class="en-h" data-at=".85" data-dy="26">' + esc(theme.welcome || '여기부터 ' + (region.name || '여행지') + '예요') + '</div>' +
      '<div class="en-s" data-at="1.6">' + esc(sub) + '</div></div>');
    const img = $('.bgimg', root);
    const line = $('.gold-line', root);
    const flash = $('.flash', root);
    const tint = $('.tint', root);
    const rv = revealer(root);
    return {
      root,
      credit: bad.has(url) ? '' : credit(photo),
      update(lt, d) {
        kb(img, lt / d, 3);
        rv(lt);
        line.style.transform = 'scaleX(' + easeOut((lt - 0.3) / 1.4).toFixed(4) + ')';
        tint.style.opacity = (0.55 * easeOut((lt - 0.35) / 1.4)).toFixed(3); // 사진이 신라 금빛으로 물듦
        flash.style.opacity = (lt < 0.2 ? 0 : lt < 0.55 ? (lt - 0.2) / 0.35 : Math.max(0, 1 - (lt - 0.55) / 1.1)) * 0.75;
      },
    };
  }

  // ------------------------------------------------------------ 4 들른 곳
  function timeWord(time) {
    const h = Number(String(time || '').split(':')[0]);
    if (!isFinite(h) || !time) return '';
    if (h >= 19) return '밤, ';
    if (h >= 17) return '해 질 무렵, ';
    return '';
  }
  function scenePlace(p, i, n) {
    const url = photoUrl(p.photo);
    const hasPhoto = url && !bad.has(url);
    const m = member(p.by);
    const who = m ? josa(m.name, '이', '가') + ' 담은 ' : '우리가 들른 ';
    const root = el('sc sc-place', (hasPhoto ? bgImg(url) : '<div class="ph-none k-' + esc(p.kind) + '"><div class="big">' + ic(KIND_ICON[p.kind] || 'map-pin', 92, 1.6) + '</div></div>') +
      '<div class="shade sh-place"></div>' +
      '<div class="cap"><div class="cap-k" data-at=".35"><span class="chip key">' + ic('map-pin', 18) + '들른 곳 ' + (i + 1) + ' / ' + n + '</span>' + (p.time ? '<span class="chip">' + ic('clock', 17) + esc(p.time) + '</span>' : '') + (p.area ? '<span class="chip">' + esc(p.area) + '</span>' : '') + '</div>' +
      '<div class="cap-m" data-at=".65">' + (m ? '<i class="dot" style="background:' + esc(m.color) + '"></i>' : '') + '<span>' + esc(timeWord(p.time) + who + p.name) + '</span></div></div>');
    const img = $('.bgimg', root);
    const rv = revealer(root);
    const v = [0, 1, 2, 3][i % 4];
    return { root, credit: hasPhoto ? credit(p.photo) : '', update(lt, d) { kb(img, lt / d, v); rv(lt); } };
  }

  // ------------------------------------------------------------ 5 차가 들려준 이야기
  function sceneStory(s, i, n) {
    const url = photoUrl(s.photo);
    const hasPhoto = url && !bad.has(url);
    const root = el('sc sc-story', (hasPhoto ? bgImg(url, 'center 40%') : '<div class="ph-none"></div>') + '<div class="shade sh-story"></div>' +
      '<div class="stx"><div class="stx-k" data-at=".3"><span class="wv"><i></i><i></i><i></i><i></i><i></i></span>차가 들려준 이야기</div>' +
      '<div class="stx-n" data-at=".6" data-dy="26">' + esc(s.name) + (s.tag ? '<span>' + esc(s.tag) + '</span>' : '') + '</div>' +
      '<div class="stx-l" data-at="1.0">' + esc(s.short || '') + '</div>' +
      '<div class="stx-c" data-at="1.4">' + (i + 1) + ' / ' + n + '</div></div>');
    const img = $('.bgimg', root);
    const rv = revealer(root);
    return { root, credit: hasPhoto ? credit(s.photo) : '', update(lt, d) { kb(img, lt / d, i % 2 ? 2 : 1); rv(lt); } };
  }

  // ------------------------------------------------------------ 6 일행이 올린 사진
  const SLOTS = {
    1: [[640, 395, 660, 440, -2]],
    2: [[440, 400, 500, 350, -4], [850, 385, 500, 350, 3]],
    3: [[330, 410, 400, 290, -5], [655, 370, 420, 300, 2], [960, 420, 380, 275, 5]],
    4: [[360, 290, 400, 260, -4], [760, 275, 410, 265, 3], [430, 525, 390, 255, 2], [880, 515, 400, 260, -3]],
    5: [[300, 300, 330, 225, -4], [640, 285, 340, 230, 2], [980, 300, 330, 225, 5], [470, 525, 340, 230, 3], [820, 530, 340, 230, -3]],
    6: [[300, 300, 330, 225, -4], [640, 285, 340, 230, 2], [980, 300, 330, 225, 5], [300, 530, 330, 225, 3], [640, 535, 340, 230, -2], [980, 530, 330, 225, -4]],
  };
  function scenePhotos(urls) {
    const list = urls.slice(0, 6);
    const slots = SLOTS[list.length];
    const total = S.rec.photoCount || urls.length;
    const root = el('sc sc-photos', '<div class="pg-bg" style="background-image:url(&quot;' + esc(list[0]) + '&quot;)"></div>' +
      '<div class="pg-h" data-at=".25">' + ic('images', 30) + '일행이 올린 사진 <b>' + total + '장</b></div>' +
      list.map((u, i) => {
        const [cx, cy, w, h] = slots[i];
        return '<figure class="pf" style="left:' + (cx - w / 2) + 'px;top:' + (cy - h / 2) + 'px;width:' + w + 'px;height:' + h + 'px"><img src="' + esc(u) + '" alt=""></figure>';
      }).join(''));
    const figs = $$('.pf', root);
    const rv = revealer(root);
    return {
      root,
      credit: '',
      update(lt, d) {
        rv(lt);
        figs.forEach((f, i) => {
          const rot = slots[i][4];
          const q = easeOut((lt - 0.55 - i * 0.32) / 0.6);
          f.style.opacity = q.toFixed(3);
          f.style.transform = 'translateY(' + ((1 - q) * 30 - 10 * (lt / d)).toFixed(2) + 'px) rotate(' + (rot * (0.6 + 0.4 * q)).toFixed(2) + 'deg) scale(' + (0.88 + 0.12 * q).toFixed(4) + ')';
        });
      },
    };
  }

  // ------------------------------------------------------------ 7 여행 티켓
  function sceneTicket() {
    const t = S.trip;
    const r = S.rec;
    const photo = r.cover || (r.visited.length ? (placeOf(r.visited[r.visited.length - 1]) || {}).photo : null) || 'donggung_night_2.jpg';
    let url = photoUrl(photo);
    if (!url || bad.has(url)) url = photoUrl('donggung_night_2.jpg');
    const names = r.visited.map((id) => (placeOf(id) || {}).name).filter(Boolean);
    const km = r.km != null && isFinite(Number(r.km)) ? Number(r.km) : null;
    const num = (v, unit, at) => '<b><span data-to="' + v + '" data-cat="' + at + '">0</span><small>' + unit + '</small></b>';
    const root = el('sc sc-ticket', (bad.has(url) ? '' : '<img class="bgimg soft" src="' + esc(url) + '" alt="">') + '<div class="shade sh-ticket"></div>' +
      '<div class="tkw"><div class="tkbox" data-at=".25" data-dy="46" data-dur=".9"><div class="tk">' +
      '<div class="tk-h"><span>' + ic('ticket', 24) + '여행 티켓' + (t.date ? ' · ' + esc(t.date) : '') + '</span></div>' +
      '<div class="tk-r"><div><b>' + esc(t.from || '') + '</b><small>' + esc(r.startAt ? r.startAt + ' 출발' : '출발') + '</small></div>' +
      '<div class="tk-ln"><i>' + ic('car-front', 24) + '</i></div>' +
      '<div class="r"><b>' + esc(t.to || '') + '</b><small>' + esc(r.entryAt ? r.entryAt + ' 도착' : '도착') + '</small></div></div>' +
      '<div class="tk-cut"><i></i><i></i></div>' +
      '<div class="tk-g"><div>달린 길' + (km != null ? num(km, 'km', 1.0) : '<b>-</b>') + '</div><div>들른 곳' + num(r.visited.length, '곳', 1.15) + '</div>' +
      '<div>들은 설명' + num(r.storyCount, '편', 1.3) + '</div><div>사진' + num(r.photoCount, '장', 1.45) + '</div></div>' +
      (names.length ? '<div class="tk-p">' + ic('map-pin', 20) + '<span>' + esc(names.join(' · ')) + '</span></div>' : '') +
      '<div class="tk-f"><span class="logo">' + LOGO + '</span>구르는 가이드<span class="avs">' +
      members.map((m) => '<span class="av" style="background:' + esc(m.color) + '">' + esc(String(m.name).slice(0, 1)) + '</span>').join('') + '</span>' +
      (r.sentTo ? '<em>가족 휴대폰 ' + esc(r.sentTo) + '대에 보냈어요</em>' : '<em>' + esc(t.title || '') + '</em>') + '</div>' +
      '</div><div class="stamp">' + esc(t.to || '') + '<small>' + esc(t.date || '') + '</small></div></div></div>');
    const img = $('.bgimg', root);
    const stamp = $('.stamp', root);
    const rv = revealer(root);
    return {
      root,
      credit: bad.has(url) ? '' : credit(photo),
      update(lt, d) {
        kb(img, lt / d, 0);
        rv(lt);
        const q = easeOut((lt - 2.3) / 0.45);
        stamp.style.opacity = q.toFixed(3);
        stamp.style.transform = 'rotate(-14deg) scale(' + (1.7 - 0.7 * q).toFixed(4) + ')';
      },
    };
  }

  // ------------------------------------------------------------ 장면 짜기
  function buildScenes() {
    const r = S.rec;
    const plan = [{ make: sceneTitle, dur: 5 }, { make: sceneMap, dur: 8.5 }, { make: sceneEntry, dur: 6.5 }];
    const vis = r.visited.map(placeOf).filter(Boolean);
    vis.forEach((p, i) => plan.push({ make: () => scenePlace(p, i, vis.length), dur: 5.2 }));
    const st = r.stories.map(sightOf).filter(Boolean);
    st.forEach((s, i) => plan.push({ make: () => sceneStory(s, i, st.length), dur: 4.8 }));
    const photos = r.photos.map(photoUrl).filter((u) => u && !bad.has(u));
    if (photos.length) plan.push({ make: () => scenePhotos(photos), dur: 6.5 });
    plan.push({ make: sceneTicket, dur: 8.5 });
    // 영상 길이를 record.duration(예: 1:02)에 맞춤
    const sum = plan.reduce((a, s) => a + s.dur, 0);
    const target = parseDur(r.duration);
    const k = target ? clamp((target + XF * (plan.length - 1)) / sum, 0.7, 1.35) : 1;
    let t0 = 0;
    S.scenes = plan.map((s, i) => {
      const sc = s.make();
      const dur = s.dur * k;
      Object.assign(sc, { t0, t1: t0 + dur, first: i === 0, last: i === plan.length - 1, vis: false });
      t0 += dur - XF;
      stage.appendChild(sc.root);
      return sc;
    });
    S.T = S.scenes[S.scenes.length - 1].t1;
  }

  function buildHud() {
    stage.insertAdjacentHTML('beforeend',
      '<div class="hud-shade top"></div><div class="hud-shade bot"></div>' +
      '<div class="hud-t" id="hudT">' + ic('film', 22) + '<span>' + esc(S.rec.title + ' · ' + durLong(S.T)) + '</span></div>' +
      '<div class="hud-c" id="hudC"></div>' +
      '<div class="hud-b"><div class="bar"><b id="bar"></b></div><div class="tm"><span id="tNow">0:00</span><span>' + fmt(S.T) + '</span></div></div>' +
      '<button type="button" class="snd" id="snd">' + ic('volume-x', 20) + '화면을 누르면 소리가 나와요</button>' +
      '<button type="button" class="again" id="again">' + ic('rotate-ccw', 20, 2.4) + '다시 보기</button>' +
      '<div class="pz" id="pz"></div>');
    // 소리 자동 재생이 막혔을 때 덮개. 무대 밖(#view)에 두어 작은 세로 화면에서도 버튼이 작아지지 않게 함
    view.insertAdjacentHTML('beforeend', '<div class="tap" id="tap"><span class="tap-b">' + ic('play', 34) + '</span><b>' + esc(S.rec.title) + '</b><small>' + esc(durLong(S.T)) + ' · 누르면 소리와 함께 재생돼요</small></div>');
  }

  // ------------------------------------------------------------ 그리기
  let hudCredit = null, hudTime = '', hudOp = '';
  // 화면을 누르기 기다리는 동안은 제목이 다 나온 순간(POSTER)을 보여 주고 시간은 0:00 으로 둠
  const POSTER = 2.2;
  function render(t) {
    const ht = S.waitTap ? 0 : t;
    if (S.waitTap) t = POSTER;
    let top = null;
    for (const sc of S.scenes) {
      const on = t >= sc.t0 - 1e-3 && (t <= sc.t1 + 1e-3 || sc.last);
      if (!on) { if (sc.vis) { sc.root.classList.remove('v'); sc.vis = false; } continue; }
      // 겹치는 동안 새 장면만 서서히 나타남(앞 장면은 그대로) → 어두워지지 않는 크로스페이드
      const op = sc.first ? clamp(t / 0.6) : clamp((t - sc.t0) / XF);
      if (!sc.vis) { sc.root.classList.add('v'); sc.vis = true; }
      sc.root.style.opacity = op.toFixed(3);
      sc.update(Math.max(0, t - sc.t0), sc.t1 - sc.t0);
      if (op >= 0.5) top = sc;
    }
    const c = top ? top.credit || '' : '';
    if (c !== hudCredit) { $('#hudC').textContent = c; hudCredit = c; }
    const o = clamp((t - (S.scenes[0].t1 - XF)) / XF).toFixed(2);
    if (o !== hudOp) { $('#hudT').style.opacity = o; hudOp = o; }
    $('#bar').style.width = ((ht / S.T) * 100).toFixed(3) + '%';
    const tn = fmt(Math.min(ht, S.T));
    if (tn !== hudTime) { $('#tNow').textContent = tn; hudTime = tn; }
  }

  // ------------------------------------------------------------ 소리
  const audio = new Audio();
  let audioDead = FREEZE != null;
  let soundOn = false;
  let audioReady = null;
  function setupAudio() {
    if (audioDead) return;
    const types = { ogg: 'audio/ogg', m4a: 'audio/mp4', mp3: 'audio/mpeg', wav: 'audio/wav' };
    const srcs = ['ogg', 'mp3', 'm4a', 'wav'].filter((e) => audio.canPlayType(types[e]) !== '').map((e) => '../audio/reel_music.' + e);
    let i = 0;
    audio.preload = 'auto';
    audioReady = new Promise((resolve) => {
      const done = () => resolve();
      audio.addEventListener('canplay', done, { once: true });
      audio.addEventListener('error', () => {
        i++;
        if (i < srcs.length) { audio.src = srcs[i]; audio.load(); } else { audioDead = true; done(); if (soundOn) soundOn = false; }
      });
      setTimeout(done, 1200);
    });
    if (!srcs.length) { audioDead = true; return; }
    audio.src = srcs[0];
    audio.load();
  }
  function volumeAt(t) { return clamp(t / 1.5) * clamp((S.T - t) / 3) * 0.9; }
  function playAudio() {
    if (audioDead) return Promise.resolve('none');
    try { if (isFinite(audio.duration) && S.t < audio.duration) audio.currentTime = S.t; } catch (e) { }
    audio.volume = volumeAt(S.t);
    const pr = audio.play();
    if (!pr || !pr.then) { soundOn = true; return Promise.resolve('ok'); }
    return pr.then(() => { soundOn = true; $('#snd').classList.remove('on'); return 'ok'; })
      .catch((e) => (e && e.name === 'NotAllowedError' ? 'blocked' : 'none'));
  }

  // ------------------------------------------------------------ 재생
  function frame(now) {
    const dt = S.last ? Math.min(0.1, (now - S.last) / 1000) : 0;
    S.last = now;
    if (S.playing) {
      S.t += dt;
      if (soundOn && !audio.paused) {
        audio.volume = volumeAt(S.t);
        if (isFinite(audio.duration) && S.t < audio.duration - 0.5 && Math.abs(audio.currentTime - S.t) > 0.35) audio.currentTime = S.t;
      }
      if (S.t >= S.T) { S.t = S.T; finish(); }
    }
    render(S.t);
    requestAnimationFrame(frame);
  }
  function finish() {
    if (S.ended) return;
    S.ended = true;
    S.playing = false;
    if (soundOn) setTimeout(() => audio.pause(), 300);
    $('#again').classList.add('on');
    try { if (window.CarReel && typeof window.CarReel.ended === 'function') window.CarReel.ended(); } catch (e) { console.warn('CarReel.ended() 실패', e); }
    try { if (window.parent !== window) window.parent.postMessage({ type: 'reel-end', code: CODE }, '*'); } catch (e) { }
  }
  function play() {
    if (S.ended) restart();
    S.playing = true;
    if (soundOn) playAudio();
    flashIcon('play');
  }
  function pause() {
    S.playing = false;
    if (soundOn) audio.pause();
    flashIcon('pause');
  }
  function seek(t) {
    S.t = clamp(Number(t) || 0, 0, S.T);
    if (S.t < S.T) { S.ended = false; $('#again').classList.remove('on'); }
    if (soundOn && !audio.paused) { try { audio.currentTime = S.t; } catch (e) { } }
    render(S.t);
  }
  function restart() {
    S.ended = false;
    $('#again').classList.remove('on');
    S.t = 0;
    S.playing = true;
    if (soundOn) playAudio();
  }
  let pzTimer = 0;
  function flashIcon(name) {
    const pz = $('#pz');
    pz.innerHTML = ic(name, 46);
    pz.classList.add('on');
    clearTimeout(pzTimer);
    pzTimer = setTimeout(() => pz.classList.remove('on'), 600);
  }

  function fit() {
    const vw = window.innerWidth, vh = window.innerHeight;
    const s = Math.min(vw / W, vh / H);
    stage.style.transform = 'translate(' + ((vw - W * s) / 2).toFixed(2) + 'px,' + ((vh - H * s) / 2).toFixed(2) + 'px) scale(' + s.toFixed(5) + ')';
    view.style.setProperty('--s', s.toFixed(4));
    document.body.classList.toggle('portrait', vh > vw * 1.1);
  }

  async function start() {
    if (FREEZE != null) {
      seek(FREEZE);
      if (S.t >= S.T) $('#again').classList.add('on');
      requestAnimationFrame(frame);
      document.title = 'ready';
      return;
    }
    requestAnimationFrame(frame);
    if (audioReady) await audioReady;
    if (audioDead) { S.playing = true; return; }
    if (CAR) {
      S.playing = true;
      const res = await playAudio();
      if (res === 'blocked') $('#snd').classList.add('on');
      return;
    }
    const res = await playAudio();
    if (res === 'blocked') {
      // 휴대폰 브라우저가 소리 자동 재생을 막으면 첫 장면에서 기다렸다가 화면을 누르면 처음부터 소리와 함께 재생
      S.t = 0;
      S.waitTap = true;
      stage.classList.add('waiting');
      $('#tap').classList.add('on');
      return;
    }
    S.playing = true;
  }

  function onTap(e) {
    if (e.target.closest('#again')) { restart(); return; }
    if (S.waitTap) {
      S.waitTap = false;
      stage.classList.remove('waiting');
      $('#tap').classList.remove('on');
      S.t = 0;
      S.playing = true;
      playAudio();
      return;
    }
    if (!soundOn && !audioDead && $('#snd').classList.contains('on')) { $('#snd').classList.remove('on'); playAudio(); return; }
    if (S.ended) { restart(); return; }
    if (S.playing) pause(); else play();
  }

  async function main() {
    try { if (window.top !== window) document.body.classList.add('inframe'); } catch (e) { document.body.classList.add('inframe'); }
    if (CAR) document.body.classList.add('car');
    fit();
    window.addEventListener('resize', fit);
    stage.innerHTML = '<div class="load" id="load"><span class="logo">' + LOGO + '</span>여행 영상을 준비하고 있어요</div>';
    setupAudio();

    const trip = (await loadTrip()) || {};
    if (trip.base) BASE = String(trip.base).replace(/\/+$/, '');
    S.trip = trip;
    members = arr(trip.members).filter((m) => m && m.id && m.name);
    if (!members.length) members = DEFAULT_MEMBERS;
    const rid = trip.region || 'gyeongju';
    const [region, catalog, credits, mapMeta] = await Promise.all([
      getAsset('data/' + rid + '.json'), getAsset('data/places.json'), getAsset('photos/credits.json'), getAsset('map/' + rid + '_overview.json'),
    ]);
    S.region = region || (rid === FALLBACK_REGION.id ? FALLBACK_REGION : { id: rid, name: trip.to || '', sights: [] });
    S.catalog = arr(catalog);
    if (arr(credits).length) { for (const c of arr(credits)) if (c && c.file) S.credits[c.file] = c; } else S.credits = FALLBACK_REGION.credits || {};
    const mm = mapMeta || FALLBACK_MAP[rid];
    if (mm && Array.isArray(mm.bounds) && mm.bounds.length === 2 && mm.width > 0 && mm.height > 0) {
      S.map = { bounds: mm.bounds, width: Number(mm.width), height: Number(mm.height), url: '../map/' + (mm.image || rid + '_overview.jpg'), credit: mm.credit || '지도 © OpenMapTiles © OpenStreetMap contributors' };
    }
    S.rec = normRecord();

    // 사진을 먼저 받아 두고(최대 4초) 못 받은 사진은 장면에서 빼거나 그림으로 바꿈
    const urls = [photoUrl((S.region && S.region.cover) || 'gyeongju_2.jpg'), photoUrl(((S.region && S.region.theme) || {}).photo || 'woljeonggyo_night_1.jpg'), photoUrl('donggung_night_2.jpg')];
    S.rec.visited.forEach((id) => { const p = placeOf(id); if (p) urls.push(photoUrl(p.photo)); });
    S.rec.stories.forEach((id) => { const s = sightOf(id); if (s) urls.push(photoUrl(s.photo)); });
    S.rec.photos.forEach((p) => urls.push(photoUrl(p)));
    if (S.rec.cover) urls.push(photoUrl(S.rec.cover));
    if (S.map) urls.push(S.map.url);
    await preload(urls, 4000);
    if (S.map && bad.has(S.map.url)) S.map = null;

    buildScenes();
    buildHud();
    try {
      await Promise.race([Promise.all([document.fonts.load('800 100px "Asta Sans"', stage.textContent), document.fonts.load('600 30px "Asta Sans"', stage.textContent)]), sleep(2500)]);
    } catch (e) { }
    render(FREEZE != null ? FREEZE : 0);
    const loader = $('#load');
    if (FREEZE != null) loader.remove();
    else { loader.classList.add('off'); setTimeout(() => loader.remove(), 320); }
    view.addEventListener('click', onTap);
    document.addEventListener('keydown', (e) => {
      if (e.key === ' ') { e.preventDefault(); if (S.playing) pause(); else play(); }
      if (e.key === 'ArrowRight') seek(S.t + 5);
      if (e.key === 'ArrowLeft') seek(S.t - 5);
    });
    document.addEventListener('visibilitychange', () => {
      if (document.hidden && soundOn && !audio.paused) { audio.pause(); S.resumeAudio = true; }
      else if (!document.hidden && S.resumeAudio) { S.resumeAudio = false; if (S.playing) playAudio(); }
    });
    window.reel = {
      play, pause, seek, restart,
      get t() { return S.t; },
      get duration() { return S.T; },
      get playing() { return S.playing; },
      get ended() { return S.ended; },
      get sound() { return soundOn && !audio.paused; },
      get source() { return S.source; },
      get map() { return !!S.map; },
      get scenes() { return S.scenes.map((s) => [s.root.className.replace('sc ', '').replace(' v', ''), +s.t0.toFixed(2), +s.t1.toFixed(2)]); },
    };
    start();
  }

  main();
})();
