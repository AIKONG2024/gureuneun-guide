// 구르는 가이드 휴대폰 웹 '우리 여행' (/t/:code)
// - GET /api/trip/:code 를 1.5초마다 읽고 바뀐 부분만 다시 그린다(줄 단위로 비교해서 사진이 깜빡이지 않게)
// - 휴대폰 → 차량: POST /api/trip/:code/inbox (add, visit, share), 사진은 POST /api/trip/:code/photo
// - 아이콘은 vendor/lucide.min.js 의 아이콘 정의로 SVG 문자열을 만들어 씀. 외부 CDN 없음
(() => {
  'use strict';

  const Q = new URLSearchParams(location.search);
  const CODE = ((location.pathname.match(/\/t\/([A-Za-z0-9_-]+)/) || [])[1] || Q.get('code') || 'GJ1016').toUpperCase();
  const WEB = '/web/';
  const API = '/api/trip/' + CODE;
  const POLL_MS = 1500;
  const LS_ME = 'gg.me.' + CODE;
  const LS_SAVED = 'gg.saved.' + CODE;
  const LS_REC = 'gg.rec.' + CODE;
  // 차량 상단 '휴대폰 N대 연결' 셈에 쓰는 접속 id(탭마다 하나, 새로 고쳐도 유지). 서버는 me 가 있으면 me 로 묶어 셈
  const CID = (() => {
    const make = () => Math.random().toString(36).slice(2, 10) + Date.now().toString(36).slice(-4);
    try {
      let c = sessionStorage.getItem('gg.cid');
      if (!c) { c = make(); sessionStorage.setItem('gg.cid', c); }
      return c;
    } catch (e) { return make(); }
  })();

  const DEFAULT_MEMBERS = [
    { id: 'dad', name: '아빠', color: '#0B4A8F', driver: true },
    { id: 'mom', name: '엄마', color: '#B8456E' },
    { id: 'kid1', name: '첫째', color: '#1F7A5C' },
    { id: 'kid2', name: '둘째', color: '#6B4FC8' },
  ];
  const KIND = {
    sight: { label: '명소', icon: 'landmark' },
    cafe: { label: '카페', icon: 'coffee' },
    food: { label: '식당', icon: 'utensils' },
    stay: { label: '숙소', icon: 'bed-double' },
  };
  const SOURCE_ICON = { '블로그': 'book-open', '인스타그램': 'camera', '지도 앱': 'map', '카카오톡': 'message-circle', 'AI 답변': 'sparkles', '직접 입력': 'pencil', '링크': 'link' };
  const STATUS = {
    planned: '일정', open: '제안 예정', next: '가는 길', visited: '들름', skipped: '건너뜀', pending: '전달 중',
  };
  const SIDE = { right: ['arrow-right', '오른쪽에 보여요'], left: ['arrow-left', '왼쪽에 보여요'], ahead: ['arrow-up', '앞쪽에 보여요'] };
  // 시연용 예시 링크(입력칸 아래 회색 칩). 서버가 주소를 풀어 data/places.json 의 열쇠말로 장소를 찾음
  const EXAMPLES = [
    'https://blog.naver.com/gureun_family/' + encodeURIComponent('경주-황리단길-카페-후기'),
    'https://www.instagram.com/explore/locations/7710016/' + encodeURIComponent('경주-교촌마을') + '/',
  ];
  const LOGO = '<svg width="24" height="18" viewBox="0 0 26 20" fill="none" stroke-width="2.6" stroke-linecap="round" aria-hidden="true"><path d="M2 7c4-4 8 4 12 0s8 4 10 0" stroke="#8FE3F5"/><path d="M2 14c4-4 8 4 12 0s8 4 10 0" stroke="#E0B27A"/></svg>';

  const S = {
    trip: null, ver: '', uploads: [], carOnline: false,
    region: null, regionId: '', regionLoading: '', catalog: [], credits: {},
    me: null, tab: 'now',
    saved: readSaved(), visitReq: {},
    nowKey: undefined, recReady: undefined, first: true,
    draft: { link: '', state: 'empty', cand: null, source: '', req: 0, busy: false },
    bad: new Set(), fails: 0, polling: false, again: false, timer: 0,
    uploading: '', afterPick: null, sheetId: '',
  };

  // ------------------------------------------------------------ 작은 도구
  const $ = (s, el) => (el || document).querySelector(s);
  const $$ = (s, el) => Array.from((el || document).querySelectorAll(s));
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const arr = (a) => (Array.isArray(a) ? a : []);
  function lsGet(k) { try { return localStorage.getItem(k); } catch (e) { return null; } }
  function lsSet(k, v) { try { localStorage.setItem(k, v); } catch (e) { } }
  function readSaved() { try { const v = JSON.parse(lsGet(LS_SAVED) || '[]'); return Array.isArray(v) ? v : []; } catch (e) { return []; } }

  const iconCache = {};
  function ic(name, size, sw, cls) {
    size = size || 20;
    sw = sw || 2;
    const key = name + '|' + size + '|' + sw + '|' + (cls || '');
    if (iconCache[key]) return iconCache[key];
    const pascal = name.replace(/(^|-)([a-z0-9])/g, (m, a, b) => b.toUpperCase());
    const node = window.lucide && window.lucide.icons && window.lucide.icons[pascal];
    if (!node) { console.warn('아이콘 없음', name); return ''; }
    const inner = node.map(([tag, attrs]) => '<' + tag + Object.entries(attrs).map(([k, v]) => ' ' + k + '="' + v + '"').join('') + '/>').join('');
    iconCache[key] = '<svg class="ic' + (cls ? ' ' + cls : '') + '" xmlns="http://www.w3.org/2000/svg" width="' + size + '" height="' + size +
      '" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="' + sw + '" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + inner + '</svg>';
    return iconCache[key];
  }

  // 받침에 따라 조사 고르기: josa('엄마', '이', '가') → '엄마가'
  function josa(word, a, b) {
    const s = String(word || '');
    const c = s.charCodeAt(s.length - 1);
    if (!(c >= 0xac00 && c <= 0xd7a3)) return s + b;
    return s + ((c - 0xac00) % 28 ? a : b);
  }

  function members() { const m = S.trip ? arr(S.trip.members) : []; return m.length ? m : DEFAULT_MEMBERS; }
  function member(id) { return members().find((m) => m.id === id) || null; }
  function nameOf(id) { const m = member(id); return m ? m.name : '일행'; }
  function av(id, size) {
    const m = member(id);
    const color = m ? m.color : '#9CA3AF';
    const ch = m ? m.name.slice(0, 1) : '?';
    return '<span class="av' + (size ? ' av' + size : '') + '" style="background:' + esc(color) + '">' + esc(ch) + '</span>';
  }

  function photoUrl(p) {
    if (!p) return null;
    p = String(p);
    if (/^(https?:|data:|blob:)/.test(p)) return p;
    if (p.startsWith('/')) return p;
    if (p.includes('/')) return WEB + p.replace(/^(\.\.?\/)+/, '').replace(/^web\//, '');
    return WEB + 'photos/' + p;
  }
  function img(p, attrs) {
    const u = photoUrl(p);
    if (!u || S.bad.has(u)) return '';
    return '<img src="' + esc(u) + '" alt="" decoding="async" onerror="gg.bad(this)"' + (attrs || '') + '>';
  }
  window.gg = { bad(el) { S.bad.add(el.getAttribute('src')); el.remove(); } };
  function credit(p) {
    if (!p || /uploads\//.test(p)) return '';
    const c = S.credits[String(p).split('/').pop()];
    return c ? '사진 ' + c.author + ' · ' + c.license : '';
  }
  function thumb(photo, kind) {
    const k = KIND[kind] || KIND.sight;
    return '<div class="th th-' + esc(kind || 'sight') + '">' + ic(k.icon, 20) + img(photo) + '</div>';
  }

  function sight(id) { return (S.region && arr(S.region.sights).find((s) => s.id === id)) || null; }
  function cat(id) { return S.catalog.find((p) => p.id === id) || null; }
  function places() { return S.trip ? arr(S.trip.places).filter((p) => p && p.name) : []; }
  function tripPlace(id) { return places().find((p) => p.id === id) || null; }
  function regionName() { return (S.region && S.region.name) || (S.trip && S.trip.to) || ''; }
  function modeOn() { return !!(S.trip && S.trip.mode && S.trip.mode.active); }
  function tripSub() { return (S.trip.title || '우리 여행') + ' · ' + members().length + '명'; }

  function setHTML(el, html) {
    if (!el || el.__h === html) return false;
    el.innerHTML = html;
    el.__h = html;
    return true;
  }
  // 키가 같은 줄은 그대로 두고 바뀐 줄만 바꿔 끼움
  function patchList(box, items, keyOf, htmlOf) {
    const old = new Map();
    for (const ch of Array.from(box.children)) {
      if (ch.__k != null) old.set(ch.__k, ch);
      else ch.remove();
    }
    const seen = {};
    let prev = null;
    const keep = new Set();
    for (const it of items) {
      let k = String(keyOf(it));
      seen[k] = (seen[k] || 0) + 1;
      if (seen[k] > 1) k += '#' + seen[k];
      const h = htmlOf(it).trim();
      keep.add(k);
      let el = old.get(k);
      if (!el || el.__h !== h) {
        const tpl = document.createElement('template');
        tpl.innerHTML = h;
        const nel = tpl.content.firstElementChild;
        nel.__k = k;
        nel.__h = h;
        if (el) el.replaceWith(nel);
        el = nel;
      }
      const want = prev ? prev.nextSibling : box.firstChild;
      if (want !== el) box.insertBefore(el, want);
      prev = el;
    }
    for (const [k, el] of old) if (!keep.has(k)) el.remove();
  }
  function show(el, on) { if (el) el.hidden = !on; }

  // ------------------------------------------------------------ 서버
  async function getJSON(url) {
    const r = await fetch(url, { cache: 'no-store' });
    if (!r.ok) throw new Error(String(r.status));
    return r.json();
  }
  async function post(url, body) {
    const r = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    const d = await r.json().catch(() => null);
    if (!r.ok || !d) throw new Error((d && d.error) || String(r.status));
    return d;
  }

  async function poll() {
    if (S.polling) { S.again = true; return; }
    S.polling = true;
    clearTimeout(S.timer);
    try {
      const d = await getJSON(API + '?cid=' + CID + (S.me ? '&me=' + encodeURIComponent(S.me) : '') + (S.ver ? '&v=' + encodeURIComponent(S.ver) : ''));
      S.fails = 0;
      net(true);
      if (!d.same) { S.ver = d.ver || ''; apply(d); }
    } catch (e) {
      S.fails++;
      if (S.fails >= 2) net(false);
    } finally {
      S.polling = false;
      if (S.again) { S.again = false; poll(); } else S.timer = setTimeout(poll, document.hidden ? 6000 : POLL_MS);
    }
  }
  function refresh() { S.ver = ''; poll(); }

  function apply(d) {
    if (!d.trip) return;
    S.trip = d.trip;
    S.uploads = arr(d.uploads);
    S.carOnline = !!d.carOnline;
    ensureRegion();
    const n = S.trip.now;
    const key = n ? [n.type || 'sight', n.id || n.title || '', n.at || ''].join('|') : null;
    const nowChanged = !S.first && key && key !== S.nowKey;
    S.nowKey = key;
    const ready = !!(S.trip.record && S.trip.record.ready);
    const becameReady = !S.first && ready && S.recReady === false;
    S.recReady = ready;
    renderAll();
    if (nowChanged) announceNow(n);
    if (becameReady) announceRecord();
    if (S.first) {
      S.first = false;
      if (!S.me || !member(S.me)) showWho();
    }
  }

  function ensureRegion() {
    const id = (S.trip && S.trip.region) || 'gyeongju';
    if (S.regionId === id || S.regionLoading === id) return;
    S.regionLoading = id;
    getJSON(WEB + 'data/' + encodeURIComponent(id) + '.json')
      .then((j) => { S.region = j; S.regionId = id; renderAll(); })
      .catch(() => { })
      .finally(() => { S.regionLoading = ''; });
  }

  function net(ok) { $('#net').classList.toggle('on', !ok); }

  // ------------------------------------------------------------ 그리기
  function renderAll() {
    if (!S.trip) return;
    renderHeader();
    renderNow();
    renderToday();
    renderHeard();
    renderAdd();
    renderPlan();
    renderRec();
    renderTabs();
  }

  function renderHeader() {
    setHTML($('#hdSub'), esc(tripSub()));
    setHTML($('#hdMe'), S.me && member(S.me) ? av(S.me, 30) : ic('circle-user-round', 24));
  }

  function modeChip() {
    return modeOn() ? '<span class="mode">' + ic('landmark', 13) + esc(regionName()) + ' 여행 모드</span>' : '';
  }

  function visitLabel(id) {
    const p = tripPlace(id);
    if (p && p.status === 'next') return [ic('navigation', 16), '가는 길이에요', true];
    if (p && p.status === 'visited') return [ic('check', 17, 2.4), '들른 곳이에요', true];
    if (S.visitReq[id]) return [ic('check', 17, 2.4), '요청했어요', true];
    return [ic('map-pin-plus', 17), '들르기', false];
  }

  function nowHTML(n) {
    const sg = sight(n.id) || {};
    const type = n.type || 'sight';
    const live = { sight: '차가 지금 설명하고 있어요', offer: '차가 들를지 묻고 있어요', mode: '여행 모드로 바뀌었어요', arrive: '도착했어요' }[type] || '차가 지금 설명하고 있어요';
    const photo = n.photo || sg.photo || (cat(n.id) || {}).photo || (tripPlace(n.id) || {}).photo || (type === 'mode' ? 'gyeongju_1.jpg' : null);
    const side = SIDE[n.side];
    const title = n.title || sg.name || (cat(n.id) || {}).name || '';
    const tag = n.tag || sg.tag || '';
    const line = type !== 'sight' && n.line ? n.line : '';
    const desc = n.text || (type === 'sight' ? sg.text || sg.short : '') || '';
    const cr = credit(photo);
    const id = n.id || '';
    let btns = '';
    if (id && (type === 'sight' || type === 'offer')) {
      const [vi, vt, vdone] = visitLabel(id);
      const saved = S.saved.includes(id);
      btns = '<div class="btns">' +
        '<button type="button" class="btn' + (vdone ? ' done' : '') + '" data-act="visit" data-id="' + esc(id) + '" data-name="' + esc(title) + '">' + vi + vt + '</button>' +
        '<button type="button" class="btn' + (saved ? ' done' : '') + '" data-act="save" data-id="' + esc(id) + '">' + (saved ? ic('bookmark-check', 16) + '저장됨' : ic('bookmark', 16) + '저장') + '</button></div>';
    }
    return '<article class="card nowcard t-' + esc(type) + '">' +
      '<div class="live">' + (type === 'sight' || type === 'offer' ? '<span class="wv"><i></i><i></i><i></i><i></i><i></i></span>' : ic(type === 'mode' ? 'landmark' : 'flag', 15)) + esc(live) + modeChip() + '</div>' +
      (photo && img(photo) ? '<div class="photo">' + img(photo) + (side ? '<span class="dir">' + ic(side[0], 15, 2.6) + side[1] + '</span>' : '') + (cr ? '<span class="cr">' + esc(cr) + '</span>' : '') + '</div>' : '') +
      '<div class="ttl">' + esc(title) + (tag ? '<span>' + esc(tag) + '</span>' : '') + '</div>' +
      (line ? '<p class="line">' + esc(line) + '</p>' : '') +
      (desc ? '<p class="desc">' + esc(desc) + '</p>' : '') +
      btns + '</article>';
  }

  function renderNow() {
    const n = S.trip.now;
    let html;
    if (n && (n.id || n.title)) {
      html = nowHTML(n);
    } else {
      const rn = regionName() || '여행지';
      html = '<div class="card empty-now"><div class="em-ic">' + ic('audio-lines', 26) + '</div>' +
        '<b>차가 지나는 명소를 먼저 알려 드려요</b>' +
        '<p>' + (modeOn() ? '지금은 ' + esc(rn) + ' 여행 모드예요. 명소 가까이 가면 사진과 설명이 여기에 떠요' : esc(josa(rn, '에', '에')) + ' 들어서면 지나는 곳의 설명이 사진과 함께 여기에 떠요') + '</p>' +
        modeChip() + '</div>';
    }
    setHTML($('#nowBox'), html);
  }

  function statusHTML(p) {
    const s = p.status || 'planned';
    const label = STATUS[s] || STATUS.planned;
    if (s === 'planned' && p.time) return '<div class="st st-planned">' + label + '<small>' + esc(p.time) + '</small></div>';
    if (s === 'next' && p.time) return '<div class="st st-next">' + label + '<small>' + esc(p.time) + '</small></div>';
    return '<div class="st st-' + esc(s) + '">' + label + '</div>';
  }
  function byLine(p) {
    if (!p.by) return esc(p.source ? p.source + '에서 담음' : '담은 곳');
    return av(p.by) + esc(josa(nameOf(p.by), '이', '가')) + ' 담음' + (p.source ? ' · ' + esc(p.source) : '');
  }
  function placeRow(p) {
    return '<div class="row" data-act="place" data-id="' + esc(p.id) + '">' + thumb(p.photo, p.kind) +
      '<div class="rb"><div class="n">' + esc(p.name) + '</div><div class="w">' + byLine(p) + '</div></div>' + statusHTML(p) + '</div>';
  }
  const emptyRow = (msg) => '<div class="row none">' + esc(msg) + '</div>';
  const placeKey = (p) => (p.id || p.name) + (p.status === 'pending' ? ':p' : '');

  function renderToday() {
    const list = places();
    if (!list.length) patchList($('#todayList'), [0], () => 'none', () => emptyRow('아직 담은 곳이 없어요. 담기에서 링크를 붙여 넣어 보세요'));
    else patchList($('#todayList'), list, placeKey, placeRow);
  }

  function renderHeard() {
    const ids = arr(S.trip.heard).slice().reverse().filter((id, i, a) => id && a.indexOf(id) === i);
    show($('#heardSec'), ids.length > 0);
    show($('#heardList'), ids.length > 0);
    if (!ids.length) return;
    setHTML($('#heardCount'), ids.length + '곳');
    patchList($('#heardList'), ids, (id) => id, (id) => {
      const sg = sight(id) || cat(id) || tripPlace(id) || { name: id };
      const saved = S.saved.includes(id);
      return '<div class="row" data-act="place" data-id="' + esc(id) + '">' + thumb(sg.photo, 'sight') +
        '<div class="rb"><div class="n">' + esc(sg.name) + '</div><div class="w2">' + esc(sg.short || sg.note || '') + '</div></div>' +
        (saved ? ic('bookmark-check', 17, 2, 'sv') : '') + ic('chevron-right', 18, 2, 'chev') + '</div>';
    });
  }

  // ---- 담기
  function guessSource(link) {
    const s = String(link || '').toLowerCase();
    if (!/^[a-z]+:\/\/|^www\.|^[a-z0-9-]+(\.[a-z0-9-]+)+\//.test(s)) return '직접 입력';
    if (/map\.naver|naver\.me|place\.naver|map\.kakao|kko\.to|google\.[a-z.]+\/maps|maps\.google|maps\.app\.goo\.gl|tmap/.test(s)) return '지도 앱';
    if (/instagram\.com|instagr\.am/.test(s)) return '인스타그램';
    if (/chatgpt\.com|chat\.openai|gemini\.google|perplexity|claude\.ai|copilot\.microsoft|wrtn/.test(s)) return 'AI 답변';
    if (/blog\.|tistory|brunch\.co\.kr|velog|post\.naver/.test(s)) return '블로그';
    if (/kakao/.test(s)) return '카카오톡';
    return '링크';
  }
  function decodeSafe(s) { try { return decodeURIComponent(s); } catch (e) { return s; } }
  function prettyHTML(link, source) {
    let tail = link;
    try {
      const u = new URL(/^[a-z]+:\/\//i.test(link) ? link : 'https://' + link);
      const segs = u.pathname.split('/').filter(Boolean);
      tail = segs.length ? '…/' + decodeSafe(segs[segs.length - 1]) : u.hostname;
    } catch (e) { }
    const label = source && source !== '링크' ? source + ' 링크' : '링크';
    return '<span class="a">' + esc(label) + '</span><span class="b">' + esc(tail) + '</span>';
  }

  function dupOf(c) {
    if (!c) return null;
    const nn = (s) => String(s || '').replace(/\s+/g, '');
    return places().find((p) => (c.id && p.id === c.id) || nn(p.name) === nn(c.name)) || null;
  }

  function renderAdd() {
    const d = S.draft;
    const box = $('#inpBox');
    const input = $('#link');
    const has = !!d.link;
    box.classList.toggle('has', has);
    const isUrl = guessSource(d.link) !== '직접 입력';
    box.classList.toggle('pretty', has && isUrl && document.activeElement !== input);
    setHTML($('#pretty'), has ? prettyHTML(d.link, d.source || guessSource(d.link)) : '');
    if (document.activeElement !== input && input.value !== d.link) input.value = d.link;

    let pv = '';
    if (d.state === 'loading') {
      pv = '<div class="pv pv-load"><div class="pv-ph"></div><div class="pv-bd"><div class="sk l1"></div><div class="sk l2"></div></div></div>';
    } else if (d.state === 'found' && d.cand) {
      const c = d.cand;
      const k = KIND[c.kind] || KIND.sight;
      const src = c.source || d.source || '링크';
      const dup = dupOf(c);
      pv = '<div class="pv"><div class="pv-ph th-' + esc(c.kind || 'sight') + '">' + ic(k.icon, 34, 1.8) + img(c.photo) + '</div>' +
        '<div class="pv-bd"><div class="n">' + esc(c.name) + '</div><div class="m"><span>' + esc(k.label + (c.area ? ' · ' + c.area : '')) + '</span>' +
        '<span class="src">' + ic(SOURCE_ICON[src] || 'link', 12) + esc(src === '직접 입력' ? src : src + '에서') + '</span></div>' +
        (dup ? '<div class="pv-dup">' + ic('info', 14) + esc(dup.by ? josa(nameOf(dup.by), '이', '가') + ' 이미 담은 곳이에요' : '이미 담긴 곳이에요') + '</div>' : '') +
        '</div></div>';
    } else if (d.state === 'notfound') {
      pv = '<div class="nf"><div class="nf-t">' + ic('search-x', 18) + '<span>장소를 찾지 못했어요. 장소 이름을 직접 입력해 주세요</span></div>' +
        '<div class="nf-row"><input id="nfName" type="text" enterkeyhint="search" autocomplete="off" placeholder="예: 황리단길 카페" aria-label="장소 이름"><button type="button" data-act="find-name">찾기</button></div></div>';
    } else if (d.state === 'error') {
      pv = '<div class="nf"><div class="nf-t">' + ic('wifi-off', 18) + '<span>서버에 닿지 못했어요. 와이파이를 확인하고 다시 해 주세요</span></div></div>';
    } else {
      pv = '<div class="pv-empty">' + ic('map-pinned', 22) + '링크를 붙여 넣으면 장소를 찾아 드려요</div>';
    }
    setHTML($('#pv'), pv);

    setHTML($('#addWho'), '<span class="kk">담는 사람</span><span class="v">' + (S.me ? av(S.me, 24) + esc(nameOf(S.me)) : '고르기') + ic('chevron-down', 16) + '</span>');
    const dup = d.state === 'found' && dupOf(d.cand);
    const btn = $('#addBtn');
    btn.disabled = !(d.state === 'found' && d.cand) || !!dup || d.busy;
    setHTML(btn, dup ? ic('check', 19, 2.4) + '이미 담긴 장소예요' : d.busy ? '담는 중이에요' : ic('bookmark-plus', 19) + '우리 여행에 담기');

    const list = places();
    setHTML($('#addedCount'), list.length + '곳');
    if (!list.length) patchList($('#addedList'), [0], () => 'none', () => emptyRow('아직 담긴 장소가 없어요'));
    else patchList($('#addedList'), list, placeKey, (p) =>
      '<div class="row" data-act="place" data-id="' + esc(p.id) + '">' + thumb(p.photo, p.kind) +
      '<div class="rb"><div class="n">' + esc(p.name) + '</div><div class="w">' + byLine(p) + '</div></div>' +
      (p.status === 'pending' ? statusHTML(p) : ic('chevron-right', 18, 2, 'chev')) + '</div>');
  }

  function setDraftLink(v) {
    S.draft.link = String(v || '').trim();
    S.draft.cand = null;
    S.draft.source = guessSource(S.draft.link);
    S.draft.state = S.draft.link ? S.draft.state : 'empty';
    $('#link').value = S.draft.link;
  }
  function clearDraft() {
    S.draft.req++;
    Object.assign(S.draft, { link: '', state: 'empty', cand: null, source: '', busy: false });
    $('#link').value = '';
    renderAdd();
  }
  async function resolveDraft(name) {
    const d = S.draft;
    const link = d.link;
    if (!link && !name) { d.state = 'empty'; renderAdd(); return; }
    const req = ++d.req;
    const slow = setTimeout(() => { if (req === d.req) { d.state = 'loading'; renderAdd(); } }, 140);
    try {
      const r = await post('/api/resolve', name ? { link, name } : { link });
      if (req !== d.req) return;
      if (r.ok && r.candidate) {
        d.cand = r.candidate;
        d.source = r.candidate.source;
        d.state = 'found';
      } else {
        d.cand = null;
        d.source = r.source || d.source;
        d.state = 'notfound';
        if (name) toast('그 이름으로도 찾지 못했어요');
      }
    } catch (e) {
      if (req === d.req) d.state = 'error';
    } finally {
      clearTimeout(slow);
    }
    renderAdd();
  }
  let inputTimer = 0;
  function onLinkInput() {
    const v = $('#link').value.trim();
    S.draft.link = v;
    S.draft.source = guessSource(v);
    clearTimeout(inputTimer);
    if (!v) { clearDraft(); return; }
    renderAdd();
    inputTimer = setTimeout(() => resolveDraft(), 450);
  }
  async function pasteClip() {
    try {
      if (!navigator.clipboard || !navigator.clipboard.readText) throw new Error('no clipboard');
      const t = (await navigator.clipboard.readText()).trim();
      if (!t) throw new Error('empty');
      setDraftLink(t);
      renderAdd();
      resolveDraft();
    } catch (e) {
      // 휴대폰 브라우저가 클립보드 읽기를 막으면 조용히 입력칸만 열어 둠(길게 눌러 붙여 넣기)
      $('#inpBox').classList.remove('pretty');
      $('#link').focus();
    }
  }
  async function addDraft() {
    const d = S.draft;
    if (!d.cand || d.busy) return;
    if (!S.me) return pickMe(addDraft);
    d.busy = true;
    renderAdd();
    const place = Object.assign({}, d.cand, { by: S.me, link: d.link || undefined });
    try {
      const r = await post(API + '/inbox', { type: 'add', by: S.me, place });
      toast(r.already ? '이미 담긴 장소예요' : '우리 여행에 담았어요');
      clearDraft();
      refresh();
    } catch (e) {
      d.busy = false;
      renderAdd();
      toast('담지 못했어요. 다시 눌러 주세요');
    }
  }

  // ---- 일정
  function renderPlan() {
    const t = S.trip;
    const car = t.car;
    let eta;
    if (car && (car.eta || car.dest)) {
      const sub = [];
      const rk = car.remainKm === null || car.remainKm === '' ? NaN : Number(car.remainKm);
      if (isFinite(rk) && rk <= 0.05 && car.driving === false) sub.push('도착했어요');
      else {
        if (isFinite(rk)) sub.push(rk < 0.1 ? '거의 다 왔어요' : (rk < 10 ? rk.toFixed(1) : Math.round(rk).toLocaleString('ko-KR')) + 'km 남음');
        sub.push(car.driving === false ? '잠시 멈춰 있어요' : '달리는 중이에요');
      }
      eta = '<div class="card eta"><div class="eta-ic">' + ic('car-front', 22) + '</div><div>' +
        '<div class="eta-v">도착 예정 <b>' + esc(car.eta || '--:--') + '</b>' + (car.dest ? ' · ' + esc(car.dest) : '') + '</div>' +
        '<div class="eta-s">' + esc(sub.join(' · ')) + '</div></div></div>';
    } else {
      eta = '<div class="card eta off"><div class="eta-ic">' + ic('car-front', 22) + '</div><div>' +
        '<div class="eta-v">차가 출발하면 도착 예정 시각이 여기에 떠요</div>' +
        '<div class="eta-s">' + (S.carOnline ? '차와 연결되어 있어요' : '시동을 걸면 담은 장소로 바로 안내해요') + '</div></div></div>';
    }
    setHTML($('#etaBox'), eta);
    setHTML($('#planDate'), esc(t.date || ''));

    const all = places();
    const hasTime = (p) => !!p.time && p.status !== 'pending';
    const onRoute = (p) => !hasTime(p) && (p.status === 'visited' || p.status === 'next');
    const sorted = all.filter(hasTime).sort((a, b) => String(a.time).localeCompare(String(b.time)));
    // 시간 없이 경유지로 들렀거나 가는 곳은 지난 일정과 남은 일정 사이에 둠
    const stops = all.filter(onRoute);
    const cut = sorted.findIndex((p) => p.status !== 'visited');
    const timed = cut < 0 ? sorted.concat(stops) : sorted.slice(0, cut).concat(stops, sorted.slice(cut));
    const later = all.filter((p) => !hasTime(p) && !onRoute(p));
    if (!timed.length) patchList($('#planList'), [0], () => 'none', () => '<div class="row none" style="min-height:64px;display:flex;align-items:center;justify-content:center;color:var(--t4);font-size:13.5px;font-weight:600">시간이 정해진 곳이 아직 없어요</div>');
    else patchList($('#planList'), timed, placeKey, (p) => {
      const s = p.status || 'planned';
      return '<div class="tl-row s-' + esc(s) + '" data-act="place" data-id="' + esc(p.id) + '">' +
        (p.time ? '<div class="tl-time">' + esc(p.time) + '</div>' : '<div class="tl-time none">경유</div>') + '<div class="tl-rail"><i></i></div>' +
        '<div class="tl-main"><div class="n">' + esc(p.name) + '</div><div class="w">' + byLine(p) + '</div></div>' +
        '<span class="badge b-' + esc(s) + '">' + (STATUS[s] || '일정') + '</span></div>';
    });
    show($('#laterSec'), later.length > 0);
    show($('#laterNote'), later.length > 0);
    show($('#laterList'), later.length > 0);
    if (later.length) {
      setHTML($('#laterCount'), later.length + '곳');
      patchList($('#laterList'), later, placeKey, placeRow);
    }

    const ms = members();
    setHTML($('#memCount'), ms.length + '명');
    patchList($('#memList'), ms, (m) => m.id, (m) => {
      const mine = m.id === S.me;
      const on = !!m.sharing;
      return '<div class="mem">' + av(m.id, 36) + '<div class="mb"><b>' + esc(m.name) + (mine ? '<em>나</em>' : '') + (m.driver ? '<span class="drv">운전</span>' : '') + '</b>' +
        (mine ? '<small' + (on ? ' class="on"' : '') + '>' + (on ? '실시간 위치를 공유하고 있어요' : '실시간 위치 공유') + '</small>'
          : '<small' + (on ? ' class="on"' : '') + '>' + (on ? '위치를 공유하고 있어요' : '위치 공유 꺼짐') + '</small>') + '</div>' +
        (mine ? '<button type="button" class="sw' + (on ? ' on' : '') + '" role="switch" aria-checked="' + on + '" aria-label="실시간 위치 공유" data-act="share"><i></i></button>'
          : on ? '<span class="shs on">공유 중</span>' : '') + '</div>';
    });
  }

  // ---- 기록
  function durLong(d) {
    const m = /^(\d+):(\d{1,2})$/.exec(String(d || ''));
    if (!m) return String(d || '');
    return (Number(m[1]) ? Number(m[1]) + '분 ' : '') + m[2].padStart(2, '0') + '초';
  }
  function recStats() {
    const t = S.trip;
    const r = t.record || {};
    const visited = arr(r.visited);
    return {
      km: r.km != null ? Number(r.km).toLocaleString('ko-KR') : '-',
      visited: r.visitedCount != null ? r.visitedCount : visited.length,
      stories: r.storyCount != null ? r.storyCount : Math.max(arr(t.heard).length, arr(r.stories).length),
      photos: r.photoCount != null ? r.photoCount : arr(r.photos).length,
      start: r.startAt || '--:--',
      arrive: r.arriveAt || r.entryAt || (t.mode && t.mode.enteredAt) || '--:--',
      names: visited.map((id) => (tripPlace(id) || cat(id) || sight(id) || { name: '' }).name).filter(Boolean),
    };
  }
  function coverPhoto() {
    const r = S.trip.record || {};
    if (r.cover) return r.cover;
    const v = arr(r.visited);
    for (let i = v.length - 1; i >= 0; i--) {
      const p = tripPlace(v[i]) || cat(v[i]) || sight(v[i]);
      if (p && p.photo && !S.bad.has(photoUrl(p.photo))) return p.photo;
    }
    return 'donggung_night_2.jpg';
  }
  function renderRec() {
    const t = S.trip;
    const r = t.record;
    const rn = t.to || regionName();
    let html;
    if (r && r.ready) {
      const s = recStats();
      const dur = r.duration || '1:02';
      html = '<div class="nt" data-act="play"><span class="logo">' + LOGO + '</span><div class="nt-b"><div class="a">구르는 가이드<small>지금</small></div>' +
        '<div class="b">오늘 ' + esc(rn) + ' 여행 영상이 도착했어요</div></div></div>' +
        '<h1 class="lt">여행 기록</h1><p class="ls">' + esc(tripSub()) + '</p>' +
        '<div class="card vcard" data-act="play"><div class="vid">' + img(coverPhoto()) + '<span class="pl">' + ic('play', 24) + '</span><span class="du">' + esc(dur) + '</span></div>' +
        '<div class="vt">' + esc((r.title || '오늘의 ' + rn) + ' · ' + durLong(dur)) + '</div><div class="vs">지나온 길 · 들른 곳 · 들은 설명 · 사진</div></div>' +
        '<div class="card tk"><div class="tk-hd">' + ic('ticket', 18) + '여행 티켓 · ' + esc(t.date || '') + '</div>' +
        '<div class="tk-rt">' + esc(t.from || '') + ic('arrow-right', 22, 2.4) + esc(t.to || rn) + '</div>' +
        '<div class="tk-tm"><div>출발<b>' + esc(s.start) + '</b></div><div>도착<b>' + esc(s.arrive) + '</b></div></div>' +
        (s.names.length ? '<div class="tk-pl">' + ic('map-pin', 13) + '<span>' + esc(s.names.join(' · ')) + '</span></div>' : '<div style="height:12px"></div>') +
        '<div class="perf"><i></i><i></i></div>' +
        '<div class="tk-st"><div><b>' + esc(s.km) + 'km</b>달린 길</div><div><b>' + s.visited + '곳</b>들른 곳</div><div><b>' + s.stories + '편</b>들은 설명</div><div><b>' + s.photos + '장</b>사진</div></div></div>' +
        '<div class="bt"><button type="button" class="p" data-act="save-ticket">' + ic('download', 17) + '저장</button>' +
        '<button type="button" data-act="share-reel" data-to="kakao">' + ic('message-circle', 17) + '카카오톡</button>' +
        '<button type="button" data-act="share-reel" data-to="insta">' + ic('camera', 17) + '인스타그램</button></div>';
    } else {
      html = '<h1 class="lt">여행 기록</h1><p class="ls">' + esc(tripSub()) + '</p>' +
        '<div class="card wait"><div class="wt-ic">' + ic('film', 26) + '</div><b>도착하면 여행 영상과 티켓이 여기로 와요</b>' +
        '<p>달려 온 길, 들른 곳, 차가 들려준 이야기, 일행이 올린 사진을 모아 1분 남짓한 영상으로 만들어요</p>' +
        '<button type="button" class="go" data-act="upload"' + (S.uploading ? ' disabled' : '') + '>' + ic('image-plus', 19) + esc(S.uploading || '사진 올리기') + '</button></div>';
    }
    setHTML($('#recTop'), html);
    const ups = r && r.ready ? [] : S.uploads.slice().reverse();
    show($('#upSec'), ups.length > 0);
    show($('#upGrid'), ups.length > 0);
    if (ups.length) {
      setHTML($('#upCount'), ups.length + '장');
      patchList($('#upGrid'), ups, (u) => u.url, (u) => '<div class="gi">' + img(u.url) + (u.by ? av(u.by, 24) : '') + '</div>');
    }
  }

  function renderTabs() {
    const r = S.trip && S.trip.record;
    const sig = r && r.ready ? [r.title, r.km, r.duration].join('|') : '';
    $('#tabbar').classList.toggle('rec-new', !!sig && S.tab !== 'rec' && lsGet(LS_REC) !== sig);
  }

  function setTab(tab) {
    if (!['now', 'add', 'plan', 'rec'].includes(tab)) tab = 'now';
    S.tab = tab;
    document.body.dataset.tab = tab;
    for (const p of $$('.panel')) p.classList.toggle('on', p.id === 'p-' + tab);
    for (const b of $$('#tabbar button')) b.classList.toggle('on', b.dataset.tab === tab);
    $('#pill').classList.remove('on');
    if (tab === 'rec' && S.trip && S.trip.record && S.trip.record.ready) {
      const r = S.trip.record;
      lsSet(LS_REC, [r.title, r.km, r.duration].join('|'));
    }
    renderTabs();
  }

  // ------------------------------------------------------------ 알림
  let toastTimer = 0;
  function toast(msg) {
    const el = $('#toast');
    el.textContent = msg;
    el.classList.add('on');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => el.classList.remove('on'), 2400);
  }
  let pillTimer = 0;
  function pill(html, tab, gold) {
    const el = $('#pill');
    el.innerHTML = html;
    el.dataset.tab = tab;
    el.classList.toggle('gold', !!gold);
    el.classList.add('on');
    clearTimeout(pillTimer);
    pillTimer = setTimeout(() => el.classList.remove('on'), 5200);
  }
  function announceNow(n) {
    const title = n.title || (sight(n.id) || {}).name || '';
    if (S.tab === 'now') {
      const p = $('#p-now');
      if (p.scrollTop > 0) p.scrollTo({ top: 0, behavior: 'smooth' });
      const card = $('#nowBox .nowcard');
      if (card) { card.classList.remove('flash'); void card.offsetWidth; card.classList.add('flash'); }
    } else {
      const type = n.type || 'sight';
      const msg = type === 'offer' ? josa(title, '을', '를') + ' 들를지 묻고 있어요' : type === 'mode' ? (regionName() || '여행지') + ' 여행 모드로 바뀌었어요' : type === 'arrive' ? josa(title, '에', '에') + ' 도착했어요' : '차가 ' + josa(title, '을', '를') + ' 설명하고 있어요';
      pill(ic(type === 'mode' ? 'landmark' : 'audio-lines', 16) + esc(msg), 'now');
    }
  }
  function announceRecord() {
    if (S.tab !== 'rec') pill(ic('film', 16) + '오늘 ' + esc(S.trip.to || regionName()) + ' 여행 영상이 도착했어요', 'rec');
  }

  // ------------------------------------------------------------ 덮개
  function showWho() {
    const ov = $('#whoOv');
    ov.innerHTML = '<div class="who-in"><span class="logo">' + LOGO + '</span><div class="who-trip">' + esc(tripSub()) + '</div>' +
      '<h1>누구세요?</h1><p>고른 이름이 담은 장소와 올린 사진에 함께 표시돼요</p>' +
      '<div class="who-grid">' + members().map((m) => '<button type="button" data-act="iam" data-id="' + esc(m.id) + '">' + av(m.id, 56) + '<b>' + esc(m.name) + '</b>' + (m.driver ? '<small>운전</small>' : '') + '</button>').join('') + '</div></div>';
    ov.classList.add('on');
  }
  function pickMe(then) {
    S.afterPick = typeof then === 'function' ? then : null;
    openSheet('<div class="sh-h">누구세요?</div><p class="sh-p">담는 사람과 위치 공유가 이 이름으로 표시돼요</p><div class="pick">' +
      members().map((m) => '<button type="button" data-act="iam" data-id="' + esc(m.id) + '">' + av(m.id, 36) + '<b>' + esc(m.name) + '</b>' + (m.id === S.me ? ic('check', 20, 2.6, 'ck') : '') + '</button>').join('') + '</div>', 'pick');
  }
  function setMe(id) {
    if (!member(id)) return;
    S.me = id;
    lsSet(LS_ME, id);
    $('#whoOv').classList.remove('on');
    closeSheet();
    renderAll();
    const f = S.afterPick;
    S.afterPick = null;
    if (f) setTimeout(f, 60);
  }
  function openSheet(html, id) {
    const ov = $('#sheetOv');
    S.sheetId = id || '';
    $('#sheet').innerHTML = '<div class="grab"></div>' + html;
    $('#sheet').scrollTop = 0;
    ov.classList.add('on');
    requestAnimationFrame(() => requestAnimationFrame(() => ov.classList.add('show')));
  }
  function closeSheet() {
    const ov = $('#sheetOv');
    if (!ov.classList.contains('on')) return;
    ov.classList.remove('show');
    S.sheetId = '';
    setTimeout(() => { if (!ov.classList.contains('show')) ov.classList.remove('on'); }, 230);
  }
  function openPlace(id) {
    const p = tripPlace(id);
    const sg = sight(id);
    const c = cat(id);
    const name = (p && p.name) || (sg && sg.name) || (c && c.name) || id;
    const kind = (p && p.kind) || (c && c.kind) || 'sight';
    const k = KIND[kind] || KIND.sight;
    const photo = (p && p.photo) || (sg && sg.photo) || (c && c.photo);
    const area = (p && p.area) || (c && c.area) || '';
    const text = (sg && sg.text) || (p && p.note) || (c && c.note) || '';
    const tag = sg && sg.tag;
    const cr = credit(photo);
    const canVisit = !(p && (p.status === 'visited' || p.status === 'next'));
    const [vi, vt, vdone] = visitLabel(id);
    const saved = S.saved.includes(id);
    openSheet('<div class="sh-ph th-' + esc(kind) + '">' + ic(k.icon, 40, 1.6) + img(photo) + (cr && img(photo) ? '<span class="cr">' + esc(cr) + '</span>' : '') + '</div>' +
      '<div class="ttl">' + esc(name) + (tag ? '<span>' + esc(tag) + '</span>' : '') + '</div>' +
      '<div class="sh-m">' + esc(k.label + (area ? ' · ' + area : '')) + (p && p.time ? ' · ' + esc(p.time) : '') + (p ? ' · ' + (STATUS[p.status] || '일정') : '') + '</div>' +
      (p && p.by ? '<div class="sh-by">' + av(p.by, 24) + esc(josa(nameOf(p.by), '이', '가')) + ' 담았어요' + (p.source ? ' · ' + esc(p.source) + '에서' : '') + '</div>' : '') +
      (text ? '<p class="desc">' + esc(text) + '</p>' : '') +
      (sg && sg.kid ? '<div class="kid">' + ic('smile', 16) + '<span>아이에게: ' + esc(sg.kid) + '</span></div>' : '') +
      '<div class="btns">' + (canVisit ? '<button type="button" class="btn' + (vdone ? ' done' : '') + '" data-act="visit" data-id="' + esc(id) + '" data-name="' + esc(name) + '">' + vi + vt + '</button>' : '') +
      '<button type="button" class="btn' + (saved ? ' done' : ' ghost') + '" data-act="save" data-id="' + esc(id) + '">' + (saved ? ic('bookmark-check', 16) + '저장됨' : ic('bookmark', 16) + '저장') + '</button></div>', 'place:' + id);
  }

  // ------------------------------------------------------------ 동작
  async function visit(id, name) {
    if (!id) return;
    if (!S.me) return pickMe(() => visit(id, name));
    if (S.visitReq[id]) { toast('이미 차량에 요청했어요'); return; }
    S.visitReq[id] = Date.now();
    renderNow();
    if (S.sheetId === 'place:' + id) openPlace(id);
    try {
      await post(API + '/inbox', { type: 'visit', by: S.me, id, name: name || undefined });
      toast('차량에 경유지 요청을 보냈어요');
    } catch (e) {
      delete S.visitReq[id];
      renderNow();
      toast('요청을 보내지 못했어요. 다시 눌러 주세요');
    }
  }
  function toggleSave(id) {
    const i = S.saved.indexOf(id);
    if (i >= 0) { S.saved.splice(i, 1); toast('저장을 취소했어요'); } else { S.saved.push(id); toast('저장했어요. 다시 보기에서 볼 수 있어요'); }
    lsSet(LS_SAVED, JSON.stringify(S.saved));
    renderNow();
    renderHeard();
    if (S.sheetId === 'place:' + id) openPlace(id);
  }
  async function toggleShare() {
    if (!S.me) return pickMe(toggleShare);
    const m = member(S.me);
    if (!m) return;
    const on = !m.sharing;
    m.sharing = on;
    renderPlan();
    try {
      await post(API + '/inbox', { type: 'share', by: S.me, on });
      toast(on ? '일행에게 내 위치를 보여 줘요' : '위치 공유를 껐어요');
      refresh();
    } catch (e) {
      m.sharing = !on;
      renderPlan();
      toast('바꾸지 못했어요. 다시 눌러 주세요');
    }
  }

  // 사진은 긴 변 1600px JPEG 로 줄여서 올림(와이파이가 느려도 빨리 가게)
  async function shrink(file) {
    const url = URL.createObjectURL(file);
    try {
      const im = await new Promise((res, rej) => { const i = new Image(); i.onload = () => res(i); i.onerror = rej; i.src = url; });
      const s = Math.min(1, 1600 / Math.max(im.naturalWidth, im.naturalHeight));
      if (s === 1 && file.type === 'image/jpeg' && file.size < 1500000) return file;
      const c = document.createElement('canvas');
      c.width = Math.round(im.naturalWidth * s);
      c.height = Math.round(im.naturalHeight * s);
      c.getContext('2d').drawImage(im, 0, 0, c.width, c.height);
      return await new Promise((res) => c.toBlob((b) => res(b || file), 'image/jpeg', 0.86));
    } finally {
      URL.revokeObjectURL(url);
    }
  }
  async function uploadFiles(files) {
    const list = Array.from(files).filter((f) => /^image\//.test(f.type) || /\.(jpe?g|png|webp|gif|heic)$/i.test(f.name));
    if (!list.length) return;
    let ok = 0;
    for (let i = 0; i < list.length; i++) {
      S.uploading = '올리는 중 ' + (i + 1) + '/' + list.length;
      renderRec();
      try {
        const blob = await shrink(list[i]).catch(() => list[i]);
        const r = await fetch(API + '/photo?by=' + encodeURIComponent(S.me || ''), { method: 'POST', headers: { 'Content-Type': blob.type || 'image/jpeg' }, body: blob });
        if (r.ok) ok++;
      } catch (e) { }
    }
    S.uploading = '';
    renderRec();
    toast(ok ? '사진 ' + ok + '장을 올렸어요. 여행 영상에 들어가요' : '사진을 올리지 못했어요');
    refresh();
  }

  async function copyText(s) {
    try {
      if (navigator.clipboard && window.isSecureContext) { await navigator.clipboard.writeText(s); return true; }
    } catch (e) { }
    try {
      const ta = document.createElement('textarea');
      ta.value = s;
      ta.setAttribute('readonly', '');
      ta.style.cssText = 'position:fixed;top:0;left:0;opacity:0;font-size:16px';
      document.body.appendChild(ta);
      ta.select();
      ta.setSelectionRange(0, s.length);
      const ok = document.execCommand('copy');
      ta.remove();
      return ok;
    } catch (e) { return false; }
  }
  async function shareOrCopy(url, title, textMsg) {
    if (navigator.share) {
      try { await navigator.share({ title, text: textMsg, url }); return; } catch (e) { if (e && e.name === 'AbortError') return; }
    }
    if (await copyText(url)) toast('링크를 복사했어요');
    else openSheet('<div class="sh-h">링크를 길게 눌러 복사해 주세요</div><div class="linkbox">' + esc(url) + '</div>');
  }
  function reelLink() { return location.origin + '/reel/' + CODE; }
  function shareReel() {
    const rn = S.trip.to || regionName();
    shareOrCopy(reelLink(), '오늘의 ' + rn, '오늘 ' + rn + ' 여행 영상이에요');
  }

  function openPlayer() {
    $('#plBox').innerHTML = '<iframe src="/reel/' + encodeURIComponent(CODE) + '?from=phone" allow="autoplay; fullscreen" title="여행 영상"></iframe>';
    $('#player').classList.add('on');
    if (/Android/i.test(navigator.userAgent) && document.fullscreenEnabled) {
      $('#player').requestFullscreen().then(() => screen.orientation && screen.orientation.lock && screen.orientation.lock('landscape')).catch(() => { });
    }
  }
  function closePlayer() {
    if (!$('#player').classList.contains('on')) return;
    $('#plBox').innerHTML = '';
    $('#player').classList.remove('on');
    if (document.fullscreenElement) document.exitFullscreen().catch(() => { });
  }

  // ---- 여행 티켓 그림 저장(1080x1350)
  function loadImage(src) {
    return new Promise((res) => { if (!src) return res(null); const i = new Image(); i.onload = () => res(i); i.onerror = () => res(null); i.src = src; });
  }
  function rrect(g, x, y, w, h, r) {
    g.beginPath();
    g.moveTo(x + r, y);
    g.arcTo(x + w, y, x + w, y + h, r);
    g.arcTo(x + w, y + h, x, y + h, r);
    g.arcTo(x, y + h, x, y, r);
    g.arcTo(x, y, x + w, y, r);
    g.closePath();
  }
  function fitText(g, s, maxW) {
    if (g.measureText(s).width <= maxW) return s;
    while (s.length > 1 && g.measureText(s + '…').width > maxW) s = s.slice(0, -1);
    return s + '…';
  }
  async function ticketBlob() {
    const t = S.trip;
    const r = t.record || {};
    const s = recStats();
    const rn = t.to || regionName();
    const W = 1080, H = 1350;
    const F = '"Asta Sans","Apple SD Gothic Neo",sans-serif';
    const strings = ['오늘의 ' + rn, r.title || '', t.title || '', t.date || '', '여행 티켓', t.from || '', t.to || '', '출발 도착 달린 길 들른 곳 들은 설명 사진 곳 편 장 구르는 가이드', s.names.join(' · '), '0123456789:.km·'].join('');
    try { await Promise.all([document.fonts.load('800 60px "Asta Sans"', strings), document.fonts.load('600 30px "Asta Sans"', strings)]); } catch (e) { }
    const cv = document.createElement('canvas');
    cv.width = W;
    cv.height = H;
    const g = cv.getContext('2d');
    g.fillStyle = '#0B1220';
    g.fillRect(0, 0, W, H);
    const cover = await loadImage(photoUrl(coverPhoto()));
    if (cover) {
      const ch = 820;
      const sc = Math.max(W / cover.width, ch / cover.height);
      g.drawImage(cover, (W - cover.width * sc) / 2, (ch - cover.height * sc) / 2, cover.width * sc, cover.height * sc);
    }
    let gr = g.createLinearGradient(0, 0, 0, 860);
    gr.addColorStop(0, 'rgba(8,12,20,.45)');
    gr.addColorStop(0.5, 'rgba(8,12,20,.35)');
    gr.addColorStop(1, 'rgba(11,18,32,1)');
    g.fillStyle = gr;
    g.fillRect(0, 0, W, 860);
    g.fillStyle = '#fff';
    g.font = '800 78px ' + F;
    g.fillText(r.title || '오늘의 ' + rn, 76, 180);
    g.font = '600 34px ' + F;
    g.fillStyle = 'rgba(255,255,255,.82)';
    g.fillText((t.title || '') + ' · ' + (t.date || ''), 78, 238);

    const x = 70, y = 430, w = 940, h = 840;
    g.save();
    g.shadowColor = 'rgba(0,0,0,.35)';
    g.shadowBlur = 40;
    g.shadowOffsetY = 16;
    rrect(g, x, y, w, h, 40);
    gr = g.createLinearGradient(0, y, 0, y + h);
    gr.addColorStop(0, '#F7EFE2');
    gr.addColorStop(1, '#EFE2CC');
    g.fillStyle = gr;
    g.fill();
    g.restore();
    g.save();
    rrect(g, x, y, w, h, 40);
    g.clip();
    g.fillStyle = '#002C5F';
    g.fillRect(x, y, w, 112);
    g.restore();
    g.fillStyle = '#fff';
    g.font = '700 40px ' + F;
    g.fillText('여행 티켓', x + 52, y + 72);
    g.textAlign = 'right';
    g.fillStyle = '#AFC2DE';
    g.font = '600 34px ' + F;
    g.fillText(t.date || '', x + w - 52, y + 70);
    // 출발지 → 도착지
    g.textAlign = 'left';
    g.fillStyle = '#0F1A2B';
    g.font = '800 104px ' + F;
    g.fillText(t.from || '', x + 52, y + 262);
    g.textAlign = 'right';
    g.fillText(t.to || rn, x + w - 52, y + 262);
    const fromW = (g.textAlign = 'left', g.measureText(t.from || '').width);
    const toW = g.measureText(t.to || rn).width;
    g.strokeStyle = '#B07A43';
    g.lineWidth = 6;
    g.setLineDash([18, 14]);
    g.beginPath();
    g.moveTo(x + 52 + fromW + 34, y + 226);
    g.lineTo(x + w - 52 - toW - 34, y + 226);
    g.stroke();
    g.setLineDash([]);
    g.font = '700 32px ' + F;
    g.fillStyle = '#7A6A55';
    g.fillText(s.start + ' 출발', x + 54, y + 322);
    g.textAlign = 'right';
    g.fillText(s.arrive + ' 도착', x + w - 52, y + 322);
    g.textAlign = 'left';
    // 절취선
    const cy = y + 384;
    g.fillStyle = '#0B1220';
    g.beginPath(); g.arc(x, cy, 26, 0, Math.PI * 2); g.fill();
    g.beginPath(); g.arc(x + w, cy, 26, 0, Math.PI * 2); g.fill();
    g.strokeStyle = 'rgba(15,26,43,.22)';
    g.lineWidth = 4;
    g.setLineDash([14, 12]);
    g.beginPath(); g.moveTo(x + 44, cy); g.lineTo(x + w - 44, cy); g.stroke();
    g.setLineDash([]);
    // 숫자 4칸
    const stats = [['달린 길', s.km + 'km'], ['들른 곳', s.visited + '곳'], ['들은 설명', s.stories + '편'], ['사진', s.photos + '장']];
    stats.forEach(([k, v], i) => {
      const sx = x + 52 + (i % 2) * 430;
      const sy = y + 470 + Math.floor(i / 2) * 140;
      g.fillStyle = '#7A6A55';
      g.font = '700 30px ' + F;
      g.fillText(k, sx, sy);
      g.fillStyle = '#0F1A2B';
      g.font = '800 64px ' + F;
      g.fillText(v, sx, sy + 72);
    });
    if (s.names.length) {
      g.fillStyle = '#7A6A55';
      g.font = '700 30px ' + F;
      g.fillText('들른 곳', x + 52, y + 760);
      g.fillStyle = '#0F1A2B';
      g.font = '700 32px ' + F;
      g.fillText(fitText(g, s.names.join(' · '), w - 104 - 140), x + 52 + 140, y + 760);
    }
    g.fillStyle = 'rgba(255,255,255,.75)';
    g.font = '700 30px ' + F;
    g.textAlign = 'center';
    g.fillText('구르는 가이드', W / 2, H - 28);
    return new Promise((res) => cv.toBlob(res, 'image/png'));
  }
  async function saveTicket() {
    try {
      const blob = await ticketBlob();
      if (!blob) throw new Error('blob');
      const name = '여행티켓_' + (S.trip.to || '여행') + '_' + String(S.trip.date || '').replace(/\D/g, '') + '.png';
      const file = window.File ? new File([blob], name, { type: 'image/png' }) : null;
      // 휴대폰(https)에서는 공유 창의 '이미지 저장'으로, 그 밖에는 그림 파일로 내려받음
      const phone = /Android|iPhone|iPad|iPod/i.test(navigator.userAgent);
      if (phone && file && navigator.canShare && navigator.canShare({ files: [file] })) {
        try { await navigator.share({ files: [file], title: '여행 티켓' }); return; } catch (e) { if (e && e.name === 'AbortError') return; }
      }
      const a = document.createElement('a');
      a.href = URL.createObjectURL(blob);
      a.download = name;
      document.body.appendChild(a);
      a.click();
      setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 4000);
      toast('여행 티켓을 저장했어요');
    } catch (e) {
      shareOrCopy(reelLink(), '여행 영상', '여행 영상 링크예요');
    }
  }

  // ------------------------------------------------------------ 이벤트
  function onClick(e) {
    const el = e.target.closest('[data-act]');
    if (!el) return;
    const act = el.dataset.act;
    const id = el.dataset.id;
    switch (act) {
      case 'tab': setTab(el.dataset.tab); break;
      case 'pill': $('#pill').classList.remove('on'); setTab(el.dataset.tab || 'now'); break;
      case 'pick-me': pickMe(); break;
      case 'iam': setMe(id); break;
      case 'share-trip': shareOrCopy(location.origin + '/t/' + CODE, '우리 여행', (S.trip ? S.trip.title + ' ' : '') + '여행 링크예요. 가고 싶은 곳을 담아 주세요'); break;
      case 'paste': pasteClip(); break;
      case 'clear': clearDraft(); $('#link').focus(); break;
      case 'example': setDraftLink(EXAMPLES[Number(el.dataset.i) || 0]); renderAdd(); resolveDraft(); break;
      case 'add': addDraft(); break;
      case 'find-name': {
        const v = ($('#nfName') && $('#nfName').value.trim()) || '';
        if (!v) { toast('장소 이름을 입력해 주세요'); break; }
        resolveDraft(v);
        break;
      }
      case 'visit': visit(id, el.dataset.name); break;
      case 'save': toggleSave(id); break;
      case 'place': openPlace(id); break;
      case 'share': toggleShare(); break;
      case 'upload': if (!S.me) pickMe(() => $('#file').click()); else $('#file').click(); break;
      case 'play': openPlayer(); break;
      case 'close-player': closePlayer(); break;
      case 'save-ticket': saveTicket(); break;
      case 'share-reel': shareReel(el.dataset.to); break;
      case 'sheet-bg': if (e.target === el) closeSheet(); break;
      default: break;
    }
  }

  function hydrateStatic() {
    for (const el of $$('[data-ic]')) {
      const svg = ic(el.dataset.ic, Number(el.dataset.s) || 20, Number(el.dataset.w) || 2, el.className || '');
      el.outerHTML = svg;
    }
  }

  function boot() {
    hydrateStatic();
    document.addEventListener('click', onClick);
    const input = $('#link');
    input.addEventListener('input', onLinkInput);
    input.addEventListener('paste', () => setTimeout(() => { clearTimeout(inputTimer); S.draft.link = input.value.trim(); if (S.draft.link) resolveDraft(); }, 0));
    input.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); clearTimeout(inputTimer); S.draft.link = input.value.trim(); input.blur(); resolveDraft(); } });
    input.addEventListener('focus', () => { $('#inpBox').classList.add('focus'); $('#inpBox').classList.remove('pretty'); });
    input.addEventListener('blur', () => { $('#inpBox').classList.remove('focus'); setTimeout(renderAdd, 0); });
    $('#inpBox').addEventListener('click', (e) => { if (!e.target.closest('button')) { $('#inpBox').classList.remove('pretty'); input.focus(); } });
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') { closeSheet(); closePlayer(); }
      if (e.key === 'Enter' && e.target && e.target.id === 'nfName') { e.preventDefault(); const v = e.target.value.trim(); if (v) resolveDraft(v); }
    });
    $('#file').addEventListener('change', (e) => { const f = e.target.files; if (f && f.length) uploadFiles(f); e.target.value = ''; });
    document.addEventListener('visibilitychange', () => { if (!document.hidden) poll(); });
    window.addEventListener('message', (e) => { if (e.data && e.data.type === 'reel-close') closePlayer(); });

    const qme = Q.get('me');
    if (qme && /^[A-Za-z0-9_-]{1,20}$/.test(qme)) { S.me = qme; lsSet(LS_ME, qme); } else S.me = lsGet(LS_ME);
    setTab(Q.get('tab') || 'now');
    renderAdd();

    getJSON(WEB + 'data/places.json').then((j) => { S.catalog = arr(j); renderAll(); }).catch(() => { });
    getJSON(WEB + 'photos/credits.json').then((j) => {
      const m = {};
      for (const c of arr(j)) if (c && c.file) m[c.file] = c;
      S.credits = m;
      renderAll();
    }).catch(() => { });
    poll();
  }

  boot();
})();
