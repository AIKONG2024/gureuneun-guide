/* 구르는 가이드 동승자 페이지 (휴대폰)
 * 차 화면의 QR 로 들어와, 차가 이야기를 시작하면 그곳 사진과 글을 같이 본다.
 * 탭은 셋: 지금(사진, 이야기, 퀴즈, 오늘 코스) / 장소(골라서 차로 보내기) / 기록(엽서 한 장)
 * 색·글꼴·아이콘은 차 화면과 같은 토큰을 쓴다.
 */
(function () {
  'use strict';
  var code = (location.pathname.match(/\/g\/(\d{4})/) || [])[1];
  var qs = new URLSearchParams(location.search);
  // QR 주소는 /g/1234?k=휴대폰키&car=1. 차 앱이 주소 끝에 /record 를 붙여 car=1/record 가 되어도 알아봄
  var carParam = qs.get('car') || '';
  var startTab = /\/record\/?$/.test(location.pathname) || /\/record\/?$/.test(carParam) ? 'record' : 'now';
  // 이 휴대폰이 따르는 차 번호. 0 이면 네 자리 번호만 넣고 들어온 것이라 가장 최근 이야기를 따름
  var myCar = parseInt(carParam, 10) || 0;
  // QR 의 k(휴대폰 키)가 있으면 기록까지 봄. 한 번 받은 키는 이 휴대폰에 두어 번호로 다시 들어와도 이어 씀
  var urlKey = qs.get('k') || '';
  var key = urlKey || store('gg_k_' + code) || '';
  if (urlKey && code) store('gg_k_' + code, urlKey);
  var S = {
    lang: store('gg_lang'), group: null, scenario: null, photos: { places: {}, credits: {} },
    story: null, lines: [], kidLines: [], linePart: 'story', lineIdx: -1, storyDone: false, qa: null,
    passes: [], picks: [], record: null, cars: [], selected: null, online: false, tab: startTab, ended: false, fatal: null,
    quizzes: [], answers: [], stars: null, level: key ? 'full' : 'basic',
    pending: null, pickWait: null, pickMsg: null, quizPending: null, nowBadge: false, carGone: false
  };
  var me = store('gg_me') || '';
  // 휴대폰 구분 값(pid)은 서버가 정해 줌. 다시 들어올 때 그 값을 보내 내가 고른 답을 이어 봄
  var pid = (code && store('gg_pid_' + code)) || '';
  function store(k, v) {
    try {
      if (v === undefined) return localStorage.getItem(k);
      if (v === null) localStorage.removeItem(k); else localStorage.setItem(k, v);
    } catch (e) { return null; }
  }
  function $(id) { return document.getElementById(id); }

  var TXT = {
    ko: {
      appName: '구르는 가이드', now: '지금', pick: '장소', record: '기록',
      waitText: '명소에 가까워지면 여기에 떠요', ended: '여행이 끝났어요', seeRecord: '기록 보기',
      name: '이름', meAsk: '이름을 알려 주세요', meTitle: '내 이름', mePh: '예: 민지', meHint: '차가 이 이름으로 불러 줘요', start: '시작', done: '완료',
      passed: '지남', nowAt: '지금', next: '다음',
      send: '차로 보내기', pickIdle: '운전자가 확인하면 길이 바뀌어요', sent: '보냈어요. 운전자가 확인 중이에요', acked: '길이 바뀌었어요',
      pickLater: '운전자가 나중에 보기로 했어요', pickSoon: '{s}초 뒤에 보낼 수 있어요', pickBusy: '운전자가 ‘{name}’ 확인 중이에요',
      pickNoCar: '연결된 차가 없어요', pickBad: '보낼 수 없는 곳이에요', pickOther: '{by} 님이 골랐어요: {name}', custom: '지도에서 고른 곳',
      kind: { cafe: '카페', parking: '주차장', landmark: '명소', sea: '바다', place: '장소' },
      quizNew: '퀴즈가 왔어요', right: '정답이에요!', wrong: '아쉬워요. 정답은 ‘{a}’{josa}', wrongShort: '아쉬워요', gotIt: '{by} 님이 맞혔어요',
      recEmpty: '여행이 끝나면 오늘 기록이 여기 생겨요', recWait: '운전자가 기록을 보내면 여기 생겨요', recBasic: '기록은 차 화면의 QR로 들어오면 보여요',
      km: '{n}km', places: '명소 {n}곳', stars: '퀴즈 별 {n}개',
      save: '엽서 저장', saved: '엽서를 저장했어요', saveFail: '엽서를 만들지 못했어요', keep: '기록은 {d}일 뒤 지워져요', credit: '사진 {a}',
      reconnecting: '다시 연결하는 중…', tooMany: '{s}초 뒤에 다시 연결해요', noCar: '차와 연결이 끊겼어요',
      problemTitle: '여행을 열 수 없어요', noGroup: '끝난 지 오래됐거나 번호가 달라요', badKey: '차 화면의 QR을 다시 찍어 주세요', again: '번호 다시 넣기',
      see: '보기', guest: '동승자', langBtn: 'EN', langAria: 'Show in English'
    },
    en: {
      appName: 'Rolling Guide', now: 'Now', pick: 'Places', record: 'Record',
      waitText: 'Stories show up here as we get close', ended: 'The trip has ended', seeRecord: 'See the record',
      name: 'Name', meAsk: "What's your name?", meTitle: 'Your name', mePh: 'e.g. Alex', meHint: 'The car will call you by this name', start: 'Start', done: 'Done',
      passed: 'Passed', nowAt: 'Now', next: 'Next',
      send: 'Send to the car', pickIdle: 'The route changes once the driver confirms', sent: 'Sent. The driver is checking', acked: 'Route updated',
      pickLater: 'The driver saved it for later', pickSoon: 'Send again in {s}s', pickBusy: 'The driver is checking “{name}”',
      pickNoCar: 'No car is connected', pickBad: "Can't send that spot", pickOther: '{by} picked {name}', custom: 'Spot on the map',
      kind: { cafe: 'Cafe', parking: 'Parking', landmark: 'Landmark', sea: 'Sea', place: 'Place' },
      quizNew: 'A quiz arrived', right: 'Correct!', wrong: 'Not quite. It was “{a}”', wrongShort: 'Not quite', gotIt: '{by} got it right',
      recEmpty: "Today's record appears here when the trip ends", recWait: 'The record appears here once the driver shares it', recBasic: 'Scan the QR on the car screen to see the record',
      km: '{n} km', places: '{n} places', place1: '1 place', stars: '{n} quiz stars', star1: '1 quiz star',
      save: 'Save postcard', saved: 'Postcard saved', saveFail: "Couldn't make the postcard", keep: 'The record is deleted in {d} days', credit: 'Photo: {a}',
      reconnecting: 'Reconnecting…', tooMany: 'Reconnecting in {s}s', noCar: 'The car is offline',
      problemTitle: "Can't open this trip", noGroup: 'It ended a while ago, or the code is different', badKey: 'Scan the QR on the car screen again', again: 'Enter the code again',
      see: 'View', guest: 'Passenger', langBtn: '한국어', langAria: '한국어로 보기'
    }
  };
  // 고른 언어 > 그룹(차)의 안내 언어 > 그룹을 아직 모르면 휴대폰 언어
  function lang() { return S.lang || (S.group && S.group.lang) || (/^ko\b/i.test(navigator.language || 'ko') ? 'ko' : 'en'); }
  function t(k) { var d = TXT[lang()] || TXT.ko; return d[k] !== undefined ? d[k] : (TXT.ko[k] !== undefined ? TXT.ko[k] : k); }
  function f(k, o) { var s = t(k); Object.keys(o || {}).forEach(function (x) { s = s.split('{' + x + '}').join(o[x]); }); return s; }
  // 영어는 하나일 때 단수로
  function count(k, one, n) { return n === 1 && TXT[lang()][one] ? t(one) : f(k, { n: n }); }
  function nm(l10n) { if (!l10n) return ''; return typeof l10n === 'string' ? l10n : (l10n[lang()] || l10n.ko || l10n.en || ''); }
  function sentences(text) { return String(text || '').replace(/([.!?])\s+/g, '$1\u0001').split('\u0001').map(function (s) { return s.trim(); }).filter(Boolean); }
  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }
  // 받침이 있으면 a(이에요), 없으면 b(예요). 한글이 아니면 b
  function josa(word, a, b) {
    var c = String(word || '').trim().slice(-1).charCodeAt(0);
    return c >= 0xAC00 && c <= 0xD7A3 && (c - 0xAC00) % 28 ? a : b;
  }
  // 이 휴대폰이 따르는 차의 소식인지 (car=N 으로 들어왔으면 그 차만, 번호로 들어왔으면 모두)
  function mineCar(m) { return !myCar || !m || !m.carNo || m.carNo === myCar; }
  function sameCar(a, b) { return !a.carNo || !b.carNo || a.carNo === b.carNo; }
  function full() { return S.level === 'full'; }
  var reduceMotion = window.matchMedia ? matchMedia('(prefers-reduced-motion: reduce)') : { matches: false };
  function smooth() { return reduceMotion.matches ? 'auto' : 'smooth'; }

  // ---------------------------------------------------------------- 아이콘 (차 화면 Icons.kt 와 같은 24칸 격자, 2.2 굵기 둥근 선)
  var STAR = (function () {
    var d = '';
    for (var i = 0; i < 10; i++) { var r = i % 2 ? 4.3 : 9.6, a = -Math.PI / 2 + i * Math.PI / 5; d += (i ? 'L' : 'M') + (12 + r * Math.cos(a)).toFixed(2) + ' ' + (12.6 + r * Math.sin(a)).toFixed(2); }
    return d + 'Z';
  })();
  var ICON = {
    speaker: '<path class="f" d="M3.8 9.4H7.4L12 5.4V18.6L7.4 14.6H3.8Z"/><path d="M14.97 9.03A4.2 4.2 0 0 1 14.97 14.97M17.22 6.2A7.8 7.8 0 0 1 17.22 17.8"/>',
    pin: '<path d="M12 21.5C9.4 18.6 5.5 14.4 5.5 9.6A6.5 6.5 0 0 1 18.5 9.6C18.5 14.4 14.6 18.6 12 21.5Z"/><circle class="d" cx="12" cy="9.6" r="2.4"/>',
    route: '<circle cx="6" cy="18" r="2.4"/><circle cx="18" cy="6" r="2.4"/><path d="M8.4 18H14.6C16.6 18 17.8 16.6 17.8 14.9C17.8 13.1 16.5 11.8 14.8 11.8H9.2C7.5 11.8 6.2 10.5 6.2 8.8C6.2 7.1 7.4 6 9.2 6H15.6"/>',
    star: '<path class="f" d="' + STAR + '"/>',
    check: '<path d="M5 12.6L9.8 17.2L19 7.2"/>',
    close: '<path d="M6.2 6.2L17.8 17.8M17.8 6.2L6.2 17.8"/>',
    chevron: '<path d="M9 5.5L15.5 12L9 18.5"/>',
    cup: '<path d="M4.6 9.4H15.6V15C15.6 17.8 13.4 20 10.6 20H9.6C6.8 20 4.6 17.8 4.6 15Z"/><path d="M15.6 11.2H17.2C18.7 11.2 19.8 12.3 19.8 13.7C19.8 15.1 18.7 16.2 17.2 16.2H15.6"/><path d="M8.2 3.6V6.2M12 3.6V6.2"/>',
    parking: '<rect x="4" y="3.5" width="16" height="17" rx="4"/><path d="M9.6 16.6V7.4H13.2C15 7.4 16.2 8.7 16.2 10.4C16.2 12.1 15 13.4 13.2 13.4H9.6"/>'
  };
  function icon(name, cls) { return '<svg class="ic' + (cls ? ' ' + cls : '') + '" viewBox="0 0 24 24" aria-hidden="true" focusable="false">' + (ICON[name] || '') + '</svg>'; }
  Array.prototype.forEach.call(document.querySelectorAll('i[data-ic]'), function (el) { el.outerHTML = icon(el.getAttribute('data-ic')); });
  var KIND_IC = { cafe: 'cup', parking: 'parking' };

  // 알림 한 줄. onTap 을 주면 눌러서 그쪽으로 갈 수 있음
  var toastTimer, toastTap = null;
  function toast(msg, onTap, act) {
    var el = $('toast');
    $('toastText').textContent = msg;
    $('toastAct').textContent = onTap ? (act || t('see')) : '';
    el.classList.add('show'); el.classList.toggle('tap', !!onTap); toastTap = onTap || null;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { el.classList.remove('show'); el.classList.remove('tap'); toastTap = null; }, onTap ? 6000 : 3500);
  }
  $('toast').onclick = function () { var fn = toastTap; if (!fn) return; toastTap = null; $('toast').classList.remove('show'); fn(); };

  // ---------------------------------------------------------------- 명소 사진
  // 사진 목록(/api/photos)에서 그 명소의 사진을 찾음. 밤이고 밤 사진이 있으면 밤 사진.
  // 시나리오에 photo 가 적힌 곳(예: 애월 카페 → aewol)은 차 화면처럼 그 이름의 사진을 씀
  function photosFor(id, night) {
    var p = placeById(id), e = S.photos.places[(p && p.photo) || id];
    return !e ? [] : night && e.night.length ? e.night : e.day.length ? e.day : e.night;
  }
  // 표지: 시나리오가 정한 사진(차 화면과 같은 것). 없으면 여행지(도시) 사진, 그것도 없으면 코스의 첫 사진
  function coverPhotos() {
    var sc = S.scenario; if (!sc) return [];
    var want = sc.cover && sc.cover + '.jpg', all = S.photos.places;
    if (want) for (var k in all) if (all[k].day.indexOf(want) >= 0 || all[k].night.indexOf(want) >= 0) return [want];
    var ids = sc.places.filter(function (p) { return p.kind === 'city'; }).concat(sc.places).map(function (p) { return p.id; });
    for (var i = 0; i < ids.length; i++) { var l = photosFor(ids[i], false); if (l.length) return l; }
    return [];
  }
  function thumb(id, kind) {
    var file = photosFor(id, false)[0];
    return file ? '<span class="thumb"><img src="/photos/' + esc(file) + '" alt="" loading="lazy" decoding="async"></span>' : '<span class="thumb">' + icon(KIND_IC[kind] || 'pin') + '</span>';
  }
  // 사진 구석의 '사진 작가 · 라이선스'. 누르면 원본 쪽으로
  function creditLine(file) {
    var c = S.photos.credits[file];
    if (!c || !(c.author || c.license)) return '';
    return (c.author ? f('credit', { a: c.author }) : '') + (c.author && c.license ? ' · ' : '') + (c.license || '');
  }
  function setCredit(el, file) {
    var c = S.photos.credits[file] || {}, by = c.author ? f('credit', { a: c.author }) : '';
    el.innerHTML = (by ? '<span class="by">' + esc(by) + '</span>' : '') + (c.license ? '<span class="lic">' + esc((by ? ' · ' : '') + c.license) + '</span>' : '');
    el.classList.toggle('hidden', !by && !c.license);
    var href = c.source || c.license_url || '';
    if (/^https?:\/\//.test(href)) el.href = href; else el.removeAttribute('href');
  }

  // 지금 탭의 사진: 두 장을 겹쳐 두고 번갈아 밝혀 부드럽게 넘김. 몇 초마다 저절로, 손으로 밀어서도 넘어감
  var stage = { key: null, list: [], i: 0, token: 0, timer: null, shown: null };
  var layers = [$('phA'), $('phB')];
  function setStage(list, key) {
    if (stage.key === key) return;
    stage.key = key; stage.list = list; stage.i = 0; stage.token++;
    $('stage').classList.toggle('empty', !list.length);
    if (!list.length) { layers.forEach(function (l) { l.classList.remove('on'); }); stage.shown = null; renderDots(); setCredit($('credit'), null); clearInterval(stage.timer); return; }
    showPhoto(0);
  }
  function showPhoto(i) {
    var n = stage.list.length; if (!n) return;
    stage.i = ((i % n) + n) % n;
    var file = stage.list[stage.i], url = '/photos/' + file, token = ++stage.token;
    var back = layers[0].classList.contains('on') ? layers[1] : layers[0], front = back === layers[0] ? layers[1] : layers[0];
    var swap = function () {
      if (token !== stage.token) return;
      back.classList.add('on'); front.classList.remove('on');
      stage.shown = file;
      renderDots(); setCredit($('credit'), file);
    };
    back.onload = swap;
    back.onerror = function () { if (token === stage.token && n > 1) showPhoto(stage.i + 1); };
    if (back.getAttribute('src') === url && back.complete && back.naturalWidth) swap(); else back.src = url;
    clearInterval(stage.timer);
    if (n > 1 && !reduceMotion.matches) stage.timer = setInterval(function () { if (S.tab === 'now' && !document.hidden) showPhoto(stage.i + 1); }, 6000);
  }
  function renderDots() {
    var n = stage.list.length, s = '';
    if (n > 1) for (var i = 0; i < n; i++) s += '<i' + (i === stage.i ? ' class="on"' : '') + '></i>';
    $('dots').innerHTML = s;
  }
  (function () {
    var x0 = null, y0 = 0, el = $('stage');
    el.addEventListener('pointerdown', function (e) { x0 = e.clientX; y0 = e.clientY; });
    el.addEventListener('pointerup', function (e) {
      if (x0 === null) return;
      var dx = e.clientX - x0, dy = e.clientY - y0; x0 = null;
      if (Math.abs(dx) > 36 && Math.abs(dx) > Math.abs(dy) * 1.2) showPhoto(stage.i + (dx < 0 ? 1 : -1));
    });
    el.addEventListener('pointercancel', function () { x0 = null; });
  })();

  // ---------------------------------------------------------------- 탭과 언어
  var TABS = ['now', 'pick', 'record'];
  function setTab(tab) {
    if (TABS.indexOf(tab) < 0) tab = 'now';
    var was = S.tab;
    S.tab = tab;
    renderShell();
    if (tab === 'now') S.nowBadge = false;
    renderBadge();
    if (was !== tab) window.scrollTo(0, 0);
    if (tab === 'pick') setTimeout(drawPickMap, 50);
    if (tab === 'record') setTimeout(drawRecMap, 50);
  }
  // 열 수 없는 그룹이면 탭 대신 안내만. 여행 이름을 화면이 크게 보여 줄 때는 머리 줄의 이름을 숨김
  function renderShell() {
    var bad = !!S.fatal;
    TABS.forEach(function (k) { $('tab-' + k).classList.toggle('hidden', bad || k !== S.tab); });
    $('problem').classList.toggle('hidden', !bad);
    $('tabs').classList.toggle('hidden', bad);
    Array.prototype.forEach.call(document.querySelectorAll('.tabs button'), function (b) { var on = b.dataset.tab === S.tab; b.classList.toggle('on', on); b.setAttribute('aria-selected', on ? 'true' : 'false'); });
    var big = !bad && ((S.tab === 'now' && (nowMode() === 'waiting' || nowMode() === 'ended')) || (S.tab === 'record' && hasRecord()));
    document.body.classList.toggle('title-below', big);
  }
  Array.prototype.forEach.call(document.querySelectorAll('.tabs button'), function (b) { b.onclick = function () { setTab(b.dataset.tab); }; });
  // 다른 탭에 있는 동안 지금 탭에 새 소식(이야기, 퀴즈)이 오면 작은 점
  function renderBadge() { $('tNow').querySelector('.badge').classList.toggle('hidden', !(S.nowBadge && S.tab !== 'now')); }
  function nowNews() { if (S.tab !== 'now') { S.nowBadge = true; renderBadge(); } }
  $('langBtn').onclick = function () { S.lang = lang() === 'en' ? 'ko' : 'en'; store('gg_lang', S.lang); card = null; renderAll(); };

  // ---------------------------------------------------------------- 이름
  // 처음 들어오면 이름을 한 번 묻고, 나중에는 머리 줄의 이름을 눌러 바꿈
  var asked = store('gg_intro') === '1' || !!me;
  var sheetMode = null, sheetTimer = null;
  $('me').value = me;
  $('me').oninput = function () { me = $('me').value.trim(); store('gg_me', me); renderTop(); };
  $('me').onkeydown = function (e) { if (e.key === 'Enter') { e.preventDefault(); closeSheet(); } };
  function openSheet(mode) {
    sheetMode = mode;
    clearTimeout(sheetTimer);
    renderSheet();
    var w = $('sheet');
    w.classList.remove('hidden');
    void w.offsetWidth;
    w.classList.add('open');
  }
  function closeSheet() {
    if (!sheetMode) return;
    asked = true; store('gg_intro', '1');
    sheetMode = null;
    var w = $('sheet');
    w.classList.remove('open');
    try { $('me').blur(); } catch (e) {}
    sheetTimer = setTimeout(function () { w.classList.add('hidden'); }, 260);
  }
  function renderSheet() {
    if (!sheetMode) return;
    $('sheetTitle').textContent = sheetMode === 'first' ? t('meAsk') : t('meTitle');
    $('sheetOk').textContent = sheetMode === 'first' ? t('start') : t('done');
    $('me').placeholder = t('mePh'); $('meHint').textContent = t('meHint');
  }
  $('sheetOk').onclick = closeSheet;
  $('sheet').onclick = function (e) { if (e.target === $('sheet')) closeSheet(); };
  document.addEventListener('keydown', function (e) { if (e.key === 'Escape') closeSheet(); });
  $('meBtn').onclick = function () { openSheet('edit'); };

  function renderTop() {
    var sc = S.scenario;
    document.documentElement.lang = lang();
    $('title').textContent = sc ? nm(sc.title) : t('appName');
    document.title = sc ? nm(sc.title) + ' · ' + t('appName') : t('appName');
    $('meName').textContent = me || t('name');
    $('meBtn').classList.toggle('empty', !me);
    $('meBtn').setAttribute('aria-label', t('meTitle') + (me ? ': ' + me : ''));
    $('langBtn').textContent = t('langBtn'); $('langBtn').setAttribute('aria-label', t('langAria')); $('langBtn').lang = lang() === 'en' ? 'ko' : 'en';
    $('tNowL').textContent = t('now'); $('tPickL').textContent = t('pick'); $('tRecordL').textContent = t('record');
    $('toRecordL').textContent = t('seeRecord');
    setCredit($('credit'), stage.shown);
    renderSheet();
  }

  // ---------------------------------------------------------------- 지금: 사진, 이름, 이야기
  function placeById(id) {
    var sc = S.scenario; if (!sc || !id) return null;
    return sc.places.concat(sc.sights || [], sc.pickables || []).filter(function (p) { return p.id === id; })[0] || null;
  }
  function courseStops() { return S.scenario ? S.scenario.places.filter(function (p) { return p.kind !== 'city'; }) : []; }
  function passedMap() { var m = {}; S.passes.forEach(function (p) { if (p && mineCar(p) && !m[p.placeId]) m[p.placeId] = p.at || 1; }); return m; }
  function nowPlaceId() { return S.story && !S.storyDone && S.story.kind !== 'announce' && !S.ended ? S.story.placeId : null; }
  // 다음 명소: 곧 보인다고 알린 곳이 있으면 그곳. 아니면 지금 이야기하는 곳과 지난 곳 가운데 가장 뒤의 다음
  function nextStop() {
    var passed = passedMap(), stops = courseStops(), st = S.story && !S.storyDone && !S.ended ? S.story : null;
    if (st && st.kind === 'announce') { var a = stops.filter(function (p) { return p.id === st.placeId && !passed[p.id]; })[0]; if (a) return a; }
    var cur = nowPlaceId(), at = -1;
    stops.forEach(function (p, i) { if (p.id === cur || passed[p.id]) at = i; });
    var left = function (from) { return stops.filter(function (p, i) { return i > from && !passed[p.id] && p.id !== cur; })[0] || null; };
    return left(at) || left(-1);
  }

  // loading(연결 중) / waiting(이야기 전) / story(이야기) / qa(물어본 것의 답) / ended(여행 끝)
  function nowMode() {
    return (!S.group || !S.scenario) ? 'loading' : S.qa ? 'qa' : S.ended && (!S.story || S.storyDone) ? 'ended' : !S.story ? 'waiting' : 'story';
  }
  function renderNow() {
    var sc = S.scenario, st = S.story, mode = nowMode();
    renderShell();
    if (S.fatal) { $('problemTitle').textContent = t('problemTitle'); $('problemText').textContent = t(S.fatal); $('problemBtn').textContent = t('again'); return; }
    $('toRecord').classList.toggle('hidden', mode !== 'ended');
    if (mode === 'loading') { $('nowName').textContent = ''; $('lines').innerHTML = ''; return; }
    if (mode === 'waiting' || mode === 'ended') {
      setStage(coverPhotos(), 'cover');
      $('nowName').textContent = nm(sc.title);
      $('lines').className = 'lines';
      $('lines').innerHTML = '<p>' + esc(t(mode === 'ended' ? 'ended' : 'waitText')) + '</p>';
    } else if (mode === 'qa') {
      var ql = photosFor(S.qa.placeId, S.qa.night);
      if (ql.length) setStage(ql, 'p:' + ql[0]);
      $('nowName').textContent = S.qa.name;
      $('lines').className = 'lines say';
      $('lines').innerHTML = '<p>' + esc(S.qa.a) + '</p>';
    } else {
      var place = placeById(st.placeId), list = photosFor(st.placeId, st.night);
      if (!list.length) list = coverPhotos();
      setStage(list, 'p:' + list[0]);
      $('nowName').textContent = place ? nm(place.name) : st.name;
      renderLines();
    }
  }

  // 이야기 글. 지금 읽는 문장만 밝게. 아이 이야기는 같은 흐름의 다음 문단으로 이어 붙임
  var kidTimer = null, touchedAt = 0;
  function renderLines() {
    if (!S.story || nowMode() !== 'story') return;
    var kidOn = S.linePart === 'kid' && S.lineIdx >= 0, storyOn = S.linePart === 'story' && !S.storyDone && S.lineIdx >= 0;
    var spans = function (list, on) { return list.map(function (s, i) { return '<span' + (on && i === S.lineIdx ? ' class="cur"' : '') + '>' + esc(s) + ' </span>'; }).join(''); };
    $('lines').className = 'lines' + (kidOn || storyOn ? ' reading' : '');
    $('lines').innerHTML = '<p>' + spans(S.lines, storyOn) + '</p>' + (S.kidLines.length ? '<p>' + spans(S.kidLines, kidOn) + '</p>' : '');
    // 읽는 문장이 화면 밖이면 따라감 (방금 손으로 민 사람은 그대로 둠)
    var cur = $('lines').querySelector('.cur');
    if (cur && S.tab === 'now' && !sheetMode && Date.now() - touchedAt > 4000) {
      var r = cur.getBoundingClientRect(), bottom = window.innerHeight - $('tabs').offsetHeight - 12;
      if (r.bottom > bottom || r.top < 0) cur.scrollIntoView({ behavior: smooth(), block: 'center' });
    }
  }
  ['touchstart', 'wheel'].forEach(function (ev) { window.addEventListener(ev, function () { touchedAt = Date.now(); }, { passive: true }); });
  function setLine(part, index) {
    clearTimeout(kidTimer);
    S.linePart = part; S.lineIdx = index;
    if (part === 'kid') {
      // 아이 이야기는 끝났다는 신호가 따로 없어, 마지막 문장을 읽을 만큼 지나면 강조를 거둠
      var s = S.kidLines[index];
      if (!s) S.lineIdx = -1;
      else kidTimer = setTimeout(function () { if (S.linePart === 'kid') { S.lineIdx = -1; renderLines(); } }, Math.max(3500, s.length * 180));
    }
    renderLines();
  }

  // quiet: 다시 연결했을 때 다시 그리기만 (알림을 띄우지 않음)
  function showStory(m, quiet) {
    clearTimeout(kidTimer);
    var moved = !S.story || S.story.placeId !== m.placeId;
    S.story = m; S.lines = sentences(m.text); S.kidLines = sentences(m.kid);
    S.linePart = 'story'; S.lineIdx = m.done ? -1 : 0; S.storyDone = !!m.done;
    if (!quiet) endQa(true);
    renderNow(); renderQuiz(); renderCourse();
    if (quiet) return;
    if (moved) toTop();
    if (S.tab !== 'now') { var p = placeById(m.placeId); toast(p ? nm(p.name) : m.name, function () { setTab('now'); }); nowNews(); }
  }
  // 새 명소나 답이 오면 사진부터 보이게 맨 위로 (방금 손으로 민 사람은 그대로 둠)
  function toTop() { if (S.tab === 'now' && !sheetMode && window.scrollY > 0 && Date.now() - touchedAt > 4000) window.scrollTo({ top: 0, behavior: smooth() }); }

  // 차에 말로 물은 것의 답: 그곳 사진과 이름, 답을 차가 말하는 동안만 보여 주고 하던 이야기로 돌아감
  var qaTimer = null;
  function showQa(m) {
    if (!m.a) return;
    var p = placeById(m.placeId);
    S.qa = { placeId: m.placeId, name: p ? nm(p.name) : (m.title || ''), a: m.a, night: !!(S.story && S.story.night) };
    clearTimeout(qaTimer);
    qaTimer = setTimeout(endQa, Math.min(40000, Math.max(8000, 3000 + m.a.length * (/[가-힣]/.test(m.a) ? 170 : 75))));
    renderNow(); renderQuiz();
    toTop();
    if (S.tab !== 'now') { toast(S.qa.name || m.a, function () { setTab('now'); }); nowNews(); }
  }
  function endQa(silent) {
    clearTimeout(qaTimer);
    if (!S.qa) return;
    S.qa = null;
    if (silent !== true) { renderNow(); renderQuiz(); }
  }

  $('toRecord').onclick = function () { setTab('record'); };

  // 오늘 코스: 사진, 이름, 지남/지금/다음
  function renderCourse() {
    var stops = courseStops(), show = stops.length && S.group && !S.fatal;
    $('course').classList.toggle('hidden', !show);
    if (!show) return;
    var passed = passedMap(), nowId = nowPlaceId(), nx = S.ended ? null : nextStop();
    $('course').innerHTML = stops.map(function (p) {
      var state = p.id === nowId ? 'now' : passed[p.id] ? 'done' : nx && nx.id === p.id ? 'next' : '';
      var label = state === 'now' ? t('nowAt') : state === 'done' ? t('passed') : state === 'next' ? t('next') : '';
      return '<li class="' + state + '">' + thumb(p.id, p.kind) + '<span class="pname">' + esc(nm(p.name)) + '</span><span class="pstat">' + esc(label) + '</span></li>';
    }).join('');
  }

  // ---------------------------------------------------------------- 퀴즈와 별
  // 서버가 이 휴대폰이 한 답(mine)에만 고른 번호와 정답을 붙여 줌. 다른 사람 답은 맞혔는지만
  function myAnswer(id) { return S.answers.filter(function (a) { return a.id === id && a.mine; })[0] || null; }
  // 가장 최근 퀴즈 하나. 내가 답한 뒤 다른 명소 이야기가 시작되면 거둠
  function currentQuiz() {
    var q = S.quizzes.filter(function (q) { return mineCar(q); }).sort(function (a, b) { return b.at - a.at; })[0] || null;
    if (!q || S.ended) return null;
    var st = S.story;
    if (myAnswer(q.id) && st && st.at > q.at && st.placeId !== q.placeId) return null;
    return q;
  }
  function starCount() {
    var mine = {}, ok = {};
    S.quizzes.forEach(function (q) { if (mineCar(q)) mine[q.id] = 1; });
    S.answers.forEach(function (a) { if (a.correct && mine[a.id]) ok[a.id] = 1; });
    return Math.max(S.stars ? S.stars.stars : 0, Object.keys(ok).length);
  }
  var shownStars = null;
  function renderQuiz() {
    var q = currentQuiz(), card = $('quizCard');
    if (!q || nowMode() === 'qa') { card.classList.add('hidden'); return; }
    card.classList.remove('hidden');
    var n = starCount(), stars = '';
    for (var s = 0; s < n; s++) stars += icon('star', shownStars !== null && s >= shownStars ? 'pop' : '');
    $('quizStars').innerHTML = stars;
    $('quizStars').setAttribute('aria-label', n ? '★ ' + n : '');
    shownStars = n;
    $('quizQ').textContent = q.q;
    var mine = myAnswer(q.id);
    if (mine && S.quizPending && S.quizPending.id === q.id) S.quizPending = null;
    var waiting = !mine && S.quizPending && S.quizPending.id === q.id ? S.quizPending.i : null;
    // 다시 들어온 경우 정답 번호는 모를 수 있음 (맞혔으면 고른 것이 정답)
    var correctIdx = mine ? (typeof mine.answer === 'number' ? mine.answer : (mine.correct ? mine.choice : null)) : null;
    $('quizChoices').innerHTML = q.choices.map(function (c, i) {
      var cls = mine ? (i === correctIdx ? 'right' : i === mine.choice ? 'wrong' : 'off') : (i === waiting ? 'picked' : '');
      return '<button type="button" data-i="' + i + '" class="' + cls + '"' + (mine || waiting != null ? ' disabled' : '') + '><span>' + esc(c) + '</span>' + (cls === 'right' ? icon('check') : cls === 'wrong' ? icon('close') : '') + '</button>';
    }).join('');
    Array.prototype.forEach.call($('quizChoices').querySelectorAll('button'), function (b) {
      b.onclick = function () { answerQuiz(q, Number(b.dataset.i)); };
    });
    var others = S.answers.filter(function (a) { return a.id === q.id && a.correct && !a.mine; }).map(function (a) { return a.by; });
    var res = '';
    if (mine) {
      if (mine.correct) res = t('right');
      else if (correctIdx != null) res = f('wrong', { a: q.choices[correctIdx], josa: josa(q.choices[correctIdx], '이에요', '예요') });
      else res = t('wrongShort');
    }
    if (others.length) res += (res ? ' ' : '') + f('gotIt', { by: others.join(', ') });
    $('quizResult').textContent = res;
  }

  function answerQuiz(q, i) {
    if (myAnswer(q.id) || !S.ws || S.ws.readyState !== 1) return;
    S.ws.send(JSON.stringify({ type: 'quiz_answer', id: q.id, choice: i, by: me || t('guest') }));
    S.quizPending = { id: q.id, i: i };
    renderQuiz();
    // 답이 오지 않으면(그사이 연결이 끊김 등) 8초 뒤 다시 고를 수 있게
    setTimeout(function () { if (S.quizPending && S.quizPending.id === q.id && !myAnswer(q.id)) { S.quizPending = null; renderQuiz(); } }, 8000);
  }

  // ---------------------------------------------------------------- 장소: 골라서 차로 보내기
  var pickMap, pickLayer, pickFitFor = null;
  function tiles(map) {
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19, attribution: '<a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">© OpenStreetMap</a>' }).addTo(map);
    if (map.attributionControl) map.attributionControl.setPrefix('Leaflet');
  }
  // 보이는 점은 작아도 손가락으로 누르는 영역은 44px
  function marker(inner) { return L.divIcon({ className: 'mk-wrap', iconSize: [44, 44], iconAnchor: [22, 22], html: inner }); }
  function photoMarker(id, kind, sel) {
    var file = photosFor(id, false)[0];
    return marker('<span class="mk-pick' + (sel ? ' sel' : '') + '">' + (file ? '<img src="/photos/' + esc(file) + '" alt="">' : icon(KIND_IC[kind] || 'pin')) + '</span>');
  }
  function routeLine(layer, pts) {
    L.polyline(pts, { color: '#05070A', weight: 9, opacity: 0.6, interactive: false }).addTo(layer);
    L.polyline(pts, { color: '#F7F8FA', weight: 4, opacity: 0.95, interactive: false }).addTo(layer);
  }

  function drawPickMap() {
    var sc = S.scenario; if (!sc || !window.L || S.tab !== 'pick' || S.fatal) return;
    if (!pickMap) {
      pickMap = L.map('pickMap', { zoomControl: false, attributionControl: true });
      tiles(pickMap);
      pickLayer = L.layerGroup().addTo(pickMap);
      // 지도를 누르면 그곳을 고름. 보내기는 아래 단추로
      pickMap.on('click', function (e) {
        var ll = e.latlng.wrap();
        S.selected = { id: 'custom', name: t('custom'), lat: ll.lat, lon: ll.lng, kind: 'place' };
        clearPickMsg();
        renderPickList();
        drawPickMarkers();
      });
    }
    pickMap.invalidateSize();
    drawPickMarkers();
    if (pickFitFor !== sc.id) {
      pickFitFor = sc.id;
      var pts = sc.route.map(function (p) { return [p[0], p[1]]; }).concat((sc.pickables || []).map(function (p) { return [p.lat, p.lon]; }));
      pickMap.fitBounds(L.latLngBounds(pts), { padding: [30, 30] });
    }
  }
  function drawPickMarkers() {
    var sc = S.scenario; if (!pickMap || !sc) return;
    pickLayer.clearLayers();
    routeLine(pickLayer, sc.route.map(function (p) { return [p[0], p[1]]; }));
    var sel = S.selected && S.selected.id, nowId = nowPlaceId();
    courseStops().forEach(function (p) {
      L.marker([p.lat, p.lon], { icon: marker('<span class="mk-stop' + (p.id === nowId ? ' now' : '') + (sel === p.id ? ' sel' : '') + '"></span>'), title: nm(p.name), alt: nm(p.name), zIndexOffset: sel === p.id ? 500 : 0 })
        .addTo(pickLayer).on('click', function () { select(p); });
    });
    (sc.pickables || []).forEach(function (p) {
      L.marker([p.lat, p.lon], { icon: photoMarker(p.id, p.kind, sel === p.id), title: nm(p.name), alt: nm(p.name), zIndexOffset: sel === p.id ? 500 : 100 })
        .addTo(pickLayer).on('click', function () { select(p); });
    });
    if (sel === 'custom') L.marker([S.selected.lat, S.selected.lon], { icon: marker('<span class="mk-pick sel">' + icon('pin') + '</span>'), zIndexOffset: 600, interactive: false, keyboard: false }).addTo(pickLayer);
  }

  function select(p) {
    if (!p) return;
    S.selected = { id: p.id, name: nm(p.name), lat: p.lat, lon: p.lon, kind: p.kind };
    clearPickMsg();
    renderPickList();
    if (pickMap && S.tab === 'pick') { drawPickMarkers(); pickMap.panTo([p.lat, p.lon], { animate: !reduceMotion.matches }); }
  }

  // 목록에는 가 볼 만한 곳(pickables)만. 코스 명소는 지도의 점을 눌러 고를 수 있음
  function renderPickList() {
    var sc = S.scenario; if (!sc) return;
    var sel = S.selected && S.selected.id, kinds = t('kind');
    $('picklist').innerHTML = (sc.pickables || []).map(function (p) {
      var on = sel === p.id;
      return '<button type="button" class="pick-row' + (on ? ' sel' : '') + '" data-id="' + esc(p.id) + '" aria-pressed="' + on + '">' + thumb(p.id, p.kind) +
        '<span class="pname">' + esc(nm(p.name)) + (kinds[p.kind] ? '<small>' + esc(kinds[p.kind]) + '</small>' : '') + '</span>' + icon('check', 'tick') + '</button>';
    }).join('');
    Array.prototype.forEach.call($('picklist').querySelectorAll('button[data-id]'), function (b) { b.onclick = function () { select(placeById(b.dataset.id)); }; });
    renderPickButton();
  }

  // 보내기 단추 잠금: 서버 답을 기다리는 동안, 그룹에 운전자 확인을 기다리는 곳이 있을 때(90초까지), 보낸 뒤 20초
  function pickBusy() { return !!(S.pending && Date.now() - S.pending.at < 90000); }
  function pickLocked() {
    var now = Date.now();
    return (S.pickWait && now - S.pickWait.at < 8000) || pickBusy() || now < (S.pickSoonUntil || 0);
  }
  // 단추 위 한 줄: 운전자 답 > 다른 사람이 보낸 곳 확인 중 > 차 없음 > 평소 안내
  function setPickMsg(text, kind) { S.pickMsg = text ? { text: text, kind: kind || 'wait' } : null; renderPickStatus(); }
  function clearPickMsg() { if (S.pickMsg && S.pickMsg.kind !== 'wait') S.pickMsg = null; }
  function renderPickStatus() {
    var m = S.pickMsg, kind, text;
    // 기다리는 글(보냈어요, 확인 중)은 기다림이 끝나면 거둠
    if (m && m.kind === 'wait' && !pickBusy() && !(S.pickWait && Date.now() - S.pickWait.at < 8000)) m = S.pickMsg = null;
    if (m) { kind = m.kind; text = m.text; }
    else if (pickBusy() && !(S.myPicks && S.myPicks[S.pending.id])) { kind = 'wait'; text = f('pickBusy', { name: S.pending.name || '' }); }
    else if (S.group && S.online && !S.cars.length) { kind = 'warn'; text = t('pickNoCar'); }
    else { kind = 'idle'; text = t('pickIdle'); }
    $('pickStatus').className = 'pick-status ' + kind;
    $('pickStatus').textContent = text;
  }
  function renderPickButton() {
    $('sendPick').disabled = !S.selected || !S.online || !S.cars.length || pickLocked();
    // 보낸 뒤 20초 동안은 단추에 남은 초를 보여 줌
    var left = Math.ceil(((S.pickSoonUntil || 0) - Date.now()) / 1000);
    $('sendPick').textContent = left > 0 && !pickBusy() ? f('pickSoon', { s: left }) : t('send');
    renderPickStatus();
  }
  setInterval(function () { if (S.tab === 'pick') renderPickButton(); }, 1000);
  // 휴대폰은 장소 id 만 보냄(이름과 위치는 서버가 시나리오에서 채움). 지도에서 찍은 곳은 좌표만
  $('sendPick').onclick = function () {
    if (!S.selected || !S.ws || S.ws.readyState !== 1 || pickLocked()) return;
    var by = me || t('guest');
    S.ws.send(JSON.stringify(S.selected.id === 'custom' ? { type: 'pick', custom: true, lat: S.selected.lat, lon: S.selected.lon, by: by } : { type: 'pick', placeId: S.selected.id, by: by }));
    S.pickWait = { at: Date.now() };
    setPickMsg(t('sent'), 'wait');
    renderPickButton();
  };
  function pickName(id, fallback) {
    var p = S.picks.filter(function (x) { return x.id === id; })[0];
    var pl = p && placeById(p.placeId);
    return pl ? nm(pl.name) : (p && p.custom ? t('custom') : (fallback || (p && p.name) || ''));
  }

  // ---------------------------------------------------------------- 기록: 엽서 한 장
  function hasRecord() { return !!(S.scenario && S.record && full()); }
  function routePoints() {
    var r = S.record && S.record.route;
    if (r && r.length > 1) return r.map(function (p) { return [p[1], p[2]]; });
    return S.scenario ? S.scenario.route.map(function (p) { return [p[0], p[1]]; }) : [];
  }
  // 지나온 곳(차례대로), 날짜, 한 줄 사실
  function recordInfo() {
    var rec = S.record, seen = {}, places = [], stars = {};
    var add = function (id, name) {
      var p = placeById(id);
      if (!id || seen[id] || (p && p.kind === 'city')) return;
      seen[id] = 1; places.push({ id: id, kind: p && p.kind, name: p ? nm(p.name) : name, lat: p && p.lat, lon: p && p.lon });
    };
    (rec.events || []).forEach(function (e) { if (e.type === 'pass') add(e.placeId, e.title); else if (e.type === 'quiz_ok') stars[e.placeId || e.title] = 1; });
    if (!places.length) S.passes.forEach(function (p) { if (mineCar(p)) add(p.placeId, p.name); });
    var nStars = Object.keys(stars).length, km = (rec.distanceM || 0) / 1000, parts = [];
    if (km >= 0.1) parts.push(f('km', { n: km.toFixed(1) }));
    if (places.length) parts.push(count('places', 'place1', places.length));
    if (nStars) parts.push(count('stars', 'star1', nStars));
    var at = rec.startedAt || rec.endedAt || Date.now();
    return { places: places, parts: parts, fact: parts.join(' · '), at: at, date: new Date(at).toLocaleDateString(lang() === 'en' ? 'en-US' : 'ko-KR', { year: 'numeric', month: 'long', day: 'numeric' }) };
  }

  function renderRecord() {
    var has = hasRecord();
    renderShell();
    $('card').classList.toggle('hidden', !has);
    $('recEmpty').classList.toggle('hidden', has);
    if (!has) { $('recEmpty').textContent = t(!full() ? 'recBasic' : S.ended ? 'recWait' : 'recEmpty'); return; }
    var info = recordInfo(), file = coverPhotos()[0];
    $('card').classList.toggle('bare', !file);
    if (file && $('recCover').getAttribute('src') !== '/photos/' + file) $('recCover').src = '/photos/' + file;
    setCredit($('recCredit'), file);
    $('recTitle').textContent = nm(S.scenario.title);
    $('recDate').textContent = info.date;
    $('recFact').textContent = info.fact;
    $('recPlaces').innerHTML = info.places.map(function (p) { return '<li>' + thumb(p.id, p.kind) + '<span class="pname">' + esc(p.name) + '</span></li>'; }).join('');
    $('saveCard').textContent = t('save');
    var days = S.group && typeof S.group.keepDays === 'number' ? S.group.keepDays : 3;
    $('keepNote').textContent = days > 0 ? f('keep', { d: days }) : '';
    if (S.tab === 'record') drawRecMap();
    cardBlob().catch(function () {});
  }

  var recMap, recLayer, recFitFor = null;
  function drawRecMap() {
    if (!hasRecord() || !window.L || S.tab !== 'record' || S.fatal) return;
    // 보기만 하는 지도라 손가락으로 움직이지 않게 둠 (화면을 위아래로 밀 때 걸리지 않게)
    if (!recMap) { recMap = L.map('recMap', { zoomControl: false, dragging: false, touchZoom: false, scrollWheelZoom: false, doubleClickZoom: false, boxZoom: false, keyboard: false }); tiles(recMap); recLayer = L.layerGroup().addTo(recMap); }
    recLayer.clearLayers();
    var pts = routePoints();
    routeLine(recLayer, pts);
    recordInfo().places.forEach(function (p) {
      if (typeof p.lat === 'number') L.marker([p.lat, p.lon], { icon: photoMarker(p.id, p.kind, false), interactive: false, keyboard: false }).addTo(recLayer);
    });
    recMap.invalidateSize();
    var fitKey = S.scenario.id + ':' + pts.length;
    if (recFitFor !== fitKey && pts.length) { recFitFor = fitKey; recMap.fitBounds(L.latLngBounds(pts), { padding: [34, 34] }); }
  }

  // [엽서 저장]: 표지 사진, 이름, 날짜, 한 줄 사실, 길 모양을 PNG 한 장으로 그림. 서버 기록은 며칠 뒤 지워지므로 이것이 간직하는 방법
  var card = null;
  function cardBlob() {
    var keyNow = [lang(), S.record.tripId, S.record.endedAt, coverPhotos()[0], Object.keys(S.photos.credits).length].join(':');
    if (!card || card.key !== keyNow) card = { key: keyNow, blob: drawCard().then(function (c) { return new Promise(function (ok, no) { c.toBlob(function (b) { if (b) ok(b); else no(new Error('blob')); }, 'image/png'); }); }) };
    return card.blob;
  }
  function loadImage(url) {
    return new Promise(function (ok) {
      if (!url) return ok(null);
      var img = new Image();
      img.onload = function () { ok(img); };
      img.onerror = function () { ok(null); };
      img.src = url;
    });
  }
  function drawCard() {
    var sc = S.scenario, info = recordInfo(), W = 1080, H = 1350, PH = 870, M = 72;
    var file = coverPhotos()[0], credit = file ? creditLine(file) : '', title = nm(sc.title);
    var fam = '"Asta Sans", -apple-system, "Apple SD Gothic Neo", "Noto Sans KR", sans-serif';
    // 캔버스는 아직 받지 않은 글꼴 조각을 기다려 주지 않으므로 쓸 글자를 먼저 불러 둠
    var all = title + info.date + info.fact + t('appName') + credit;
    var fonts = document.fonts && document.fonts.load ? Promise.all(['700 68px', '600 36px', '400 30px'].map(function (w) { return document.fonts.load(w + ' "Asta Sans"', all); })).catch(function () {}) : Promise.resolve();
    return Promise.all([loadImage(file && '/photos/' + file), fonts]).then(function (r) {
      var img = r[0], c = document.createElement('canvas'), x = c.getContext('2d');
      c.width = W; c.height = H;
      x.fillStyle = '#0E1015'; x.fillRect(0, 0, W, H);
      if (img) {
        var k = Math.max(W / img.naturalWidth, PH / img.naturalHeight), sw = W / k, sh = PH / k;
        x.drawImage(img, (img.naturalWidth - sw) / 2, (img.naturalHeight - sh) / 2, sw, sh, 0, 0, W, PH);
      } else { x.fillStyle = '#002C5F'; x.fillRect(0, 0, W, PH); }
      if (credit) {
        var g = x.createLinearGradient(0, PH - 150, 0, PH);
        g.addColorStop(0, 'rgba(14,16,21,0)'); g.addColorStop(1, 'rgba(14,16,21,.6)');
        x.fillStyle = g; x.fillRect(0, PH - 150, W, 150);
        x.font = '400 24px ' + fam; x.textAlign = 'right'; x.fillStyle = 'rgba(255,255,255,.86)';
        x.fillText(fit(x, credit, W - 80), W - 40, PH - 30);
        x.textAlign = 'left';
      }
      // 길 모양 (오른쪽)과 지나온 곳
      cardRoute(x, routePoints(), info.places, W - M - 270, PH + 56, 270, 270);
      // 이름, 날짜, 한 줄 사실 (왼쪽)
      var maxW = W - M * 2 - 270 - 48, size = 68, lines;
      for (;;) { x.font = '700 ' + size + 'px ' + fam; lines = wrap(x, title, maxW, ' '); if (lines.length <= 2 || size <= 48) break; size -= 6; }
      var y = PH + 122;
      x.fillStyle = '#F7F8FA';
      lines.slice(0, 3).forEach(function (l) { x.fillText(l, M, y); y += Math.round(size * 1.22); });
      y += 62 - Math.round(size * 1.22) + 6;
      x.font = '400 30px ' + fam; x.fillStyle = '#9AA0AA';
      x.fillText(info.date, M, y);
      x.font = '600 36px ' + fam; x.fillStyle = '#F7F8FA';
      wrap(x, info.parts.join('\u0001'), maxW, '\u0001', ' · ').forEach(function (l) { y += 58; x.fillText(l, M, y); });
      // 왼쪽 아래에 마크와 앱 이름
      var my = H - M - 44;
      x.save();
      x.translate(M, my); x.scale(44 / 49, 44 / 49); x.translate(-33, -27);
      x.lineWidth = 4.6; x.lineCap = 'round'; x.lineJoin = 'round'; x.strokeStyle = '#fff';
      x.stroke(new Path2D('M47.52 64.8H58.68C62.28 64.8 64.44 62.28 64.44 59.22C64.44 55.98 62.1 53.64 59.04 53.64H48.96C45.9 53.64 43.56 51.3 43.56 48.24C43.56 45.18 45.72 43.2 48.96 43.2H60.48'));
      [[43.2, 64.8], [64.8, 43.2]].forEach(function (p) { x.beginPath(); x.arc(p[0], p[1], 4.32, 0, Math.PI * 2); x.stroke(); });
      x.beginPath(); x.arc(73.5, 34.5, 4.6, 0, Math.PI * 2); x.fillStyle = '#00AAD2'; x.fill();
      x.restore();
      x.font = '600 28px ' + fam; x.fillStyle = '#9AA0AA';
      x.fillText(t('appName'), M + 58, my + 32);
      return c;
    });
  }
  // 폭에 맞춰 줄을 나눔. sep 으로 자르고 glue 로 다시 이음
  function wrap(x, text, maxW, sep, glue) {
    var out = [], cur = '';
    glue = glue === undefined ? sep : glue;
    String(text).split(sep).forEach(function (w) {
      var tryLine = cur ? cur + glue + w : w;
      if (cur && x.measureText(tryLine).width > maxW) { out.push(cur); cur = w; } else cur = tryLine;
    });
    if (cur) out.push(cur);
    return out;
  }
  function fit(x, text, maxW) {
    if (x.measureText(text).width <= maxW) return text;
    while (text.length > 4 && x.measureText(text + '…').width > maxW) text = text.slice(0, -1);
    return text + '…';
  }
  function cardRoute(x, pts, places, bx, by, bw, bh) {
    if (pts.length < 2) return;
    var k = Math.cos(pts[0][0] * Math.PI / 180), minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
    pts.forEach(function (p) { minX = Math.min(minX, p[1] * k); maxX = Math.max(maxX, p[1] * k); minY = Math.min(minY, -p[0]); maxY = Math.max(maxY, -p[0]); });
    var pad = 16, s = Math.min((bw - pad * 2) / (maxX - minX || 1e-9), (bh - pad * 2) / (maxY - minY || 1e-9));
    var ox = bx + (bw - (maxX - minX) * s) / 2, oy = by + (bh - (maxY - minY) * s) / 2;
    var at = function (lat, lon) { return [ox + (lon * k - minX) * s, oy + (-lat - minY) * s]; };
    x.save();
    x.lineCap = 'round'; x.lineJoin = 'round'; x.strokeStyle = '#F7F8FA'; x.lineWidth = 7;
    x.beginPath();
    pts.forEach(function (p, i) { var q = at(p[0], p[1]); if (i) x.lineTo(q[0], q[1]); else x.moveTo(q[0], q[1]); });
    x.stroke();
    places.forEach(function (p) {
      if (typeof p.lat !== 'number') return;
      var q = at(p.lat, p.lon);
      x.beginPath(); x.arc(q[0], q[1], 11, 0, Math.PI * 2); x.fillStyle = '#0E1015'; x.fill(); x.lineWidth = 5; x.stroke();
    });
    x.restore();
  }
  function cardName() { var d = new Date(recordInfo().at); return 'rolling-guide-' + d.getFullYear() + '-' + ('0' + (d.getMonth() + 1)).slice(-2) + '-' + ('0' + d.getDate()).slice(-2) + '.png'; }
  function download(blob) {
    var a = document.createElement('a'), url = URL.createObjectURL(blob);
    a.href = url; a.download = cardName();
    document.body.appendChild(a); a.click(); a.remove();
    setTimeout(function () { URL.revokeObjectURL(url); }, 30000);
    toast(t('saved'));
  }
  // 휴대폰에서는 공유 창(사진 앱에 저장, 메신저로 보내기), 공유가 안 되는 브라우저에서는 내려받기
  $('saveCard').onclick = function () {
    if (!hasRecord()) return;
    var btn = $('saveCard');
    btn.disabled = true;
    cardBlob().then(function (blob) {
      var file = typeof File === 'function' ? new File([blob], cardName(), { type: 'image/png' }) : null;
      if (file && navigator.share && navigator.canShare && navigator.canShare({ files: [file] })) {
        return navigator.share({ files: [file], title: nm(S.scenario.title) }).catch(function (e) { if (!e || e.name !== 'AbortError') download(blob); });
      }
      download(blob);
    }).catch(function () { card = null; toast(t('saveFail')); }).then(function () { btn.disabled = false; });
  };

  // ---------------------------------------------------------------- 그룹 상태 받기
  function apply(summary) {
    S.group = summary;
    S.quizPending = null;   // 다시 붙으면 서버가 받은 답만 믿음 (못 받았으면 다시 고를 수 있음)
    if (summary.access) S.level = summary.access;
    S.cars = summary.cars || S.cars;
    S.passes = summary.passes || [];
    S.record = summary.record || null;
    S.ended = !!summary.ended;
    S.quizzes = summary.quizzes || [];
    S.answers = summary.answers || [];
    S.stars = summary.stars && mineCar(summary.stars) ? summary.stars : null;
    // 다시 연결하면 지금 하는 이야기를 다시 그림 (끊긴 사이에 바뀌었거나 끝났을 수 있음)
    var st = myStory(summary);
    if (st && (!S.story || S.story.placeId !== st.placeId || S.story.at !== st.at || S.story.kid !== st.kid)) showStory(st, true);
    if (st && S.story) {
      var ln = (summary.lines || []).concat(summary.line ? [summary.line] : []).filter(function (l) { return l && l.placeId === st.placeId && sameCar(l, st); })[0];
      S.storyDone = !!st.done;
      if (ln && !ln.kid && !st.done) { S.linePart = 'story'; S.lineIdx = ln.index; }
    }
  }
  // 이 휴대폰이 따르는 차의 마지막 이야기
  function myStory(summary) {
    var list = (summary.stories || []).filter(function (s) { return s && mineCar(s); });
    if (!list.length && summary.story && mineCar(summary.story)) list = [summary.story];
    return list.sort(function (a, b) { return (b.at || 0) - (a.at || 0); })[0] || null;
  }

  function loadScenario(id) {
    return fetch('/api/scenarios/' + id).then(function (r) { if (!r.ok) throw new Error('scenario'); return r.json(); }).then(function (sc) {
      S.scenario = sc;
      pickFitFor = null; recFitFor = null; S.selected = null;
      renderAll();
    }).catch(function () { setNet(t('reconnecting')); });
  }
  function loadPhotos() {
    return fetch('/api/photos').then(function (r) { return r.ok ? r.json() : null; }).then(function (p) {
      if (!p || !p.places) return;
      S.photos = { places: p.places, credits: p.credits || {} };
      stage.key = null;
      renderAll();
    }).catch(function () {});
  }

  function renderAll() {
    renderTop(); renderNow(); renderQuiz(); renderCourse(); renderPickList(); renderRecord(); renderBadge(); renderBanner();
    if (S.tab === 'pick') drawPickMap();
  }

  // ---------------------------------------------------------------- 연결 (끊기면 1초부터 15초까지 늘려 가며 다시 붙음)
  var ws = null, retryMs = 1000, retryTimer = null, retryIn = 0, stopped = false, hiddenAt = 0;
  function wsUrl() {
    return (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws?code=' + code + '&role=phone' +
      (key ? '&k=' + encodeURIComponent(key) : '') + (pid ? '&pid=' + encodeURIComponent(pid) : '');
  }
  // 맨 위 한 줄은 문제가 있을 때만: 연결이 끊겼을 때, 차가 그룹에서 빠졌을 때
  var netMsg = null, carTimer = null;
  function setNet(msg) { netMsg = msg || null; renderBanner(); }
  function renderBanner() {
    var msg = S.fatal ? '' : netMsg || (S.carGone ? t('noCar') : '');
    $('banner').classList.toggle('hidden', !msg);
    $('banner').innerHTML = msg ? (netMsg ? '<span class="spin" aria-hidden="true"></span>' : '') + '<span>' + esc(msg) + '</span>' : '';
  }
  // 차가 잠깐 다시 붙는 사이에는 띄우지 않게 5초를 기다림
  function watchCar() {
    var gone = S.online && S.group && !S.ended && !S.cars.length;
    if (!gone) { clearTimeout(carTimer); carTimer = null; if (S.carGone) { S.carGone = false; renderBanner(); } return; }
    if (!carTimer && !S.carGone) carTimer = setTimeout(function () { carTimer = null; S.carGone = true; renderBanner(); }, 5000);
  }
  function fatal(msgKey) {
    stopped = true;
    S.fatal = msgKey;
    netMsg = null;
    if (sheetMode) closeSheet();
    renderAll();
  }
  function lost() { S.online = false; watchCar(); renderPickButton(); }
  function connect() {
    clearTimeout(retryTimer);
    if (stopped || (ws && ws.readyState <= 1)) return;
    // 휴대폰이 오프라인이라고 알려 주면 헛되이 붙지 않고 'online' 을 기다림
    if (navigator.onLine === false) { setNet(t('reconnecting')); return; }
    var sock = ws = S.ws = new WebSocket(wsUrl());
    sock.onopen = function () { if (sock !== ws) return; S.online = true; retryMs = 1000; setNet(null); renderPickButton(); };
    sock.onclose = function (e) {
      if (sock !== ws) return;   // 일부러 바꾼 예전 연결
      lost();
      if (e.code === 4404) return fatal('noGroup');
      if (e.code === 4401) {
        // 이 휴대폰에 남은 예전 키(같은 번호의 지난 여행)면 버리고 번호로 다시 붙음. QR 주소의 키가 틀리면 멈춤
        if (!key || urlKey) return fatal('badKey');
        key = ''; store('gg_k_' + code, null); S.level = 'basic';
      }
      var wait = retryMs;
      if (e.code === 4429) { wait = Math.max(wait, (retryIn || 30) * 1000); setNet(f('tooMany', { s: Math.round(wait / 1000) })); }
      else setNet(t('reconnecting'));
      retryIn = 0;
      retryMs = Math.min(15000, retryMs * 2);
      retryTimer = setTimeout(connect, wait);
    };
    sock.onmessage = function (ev) {
      if (sock !== ws) return;
      var m; try { m = JSON.parse(ev.data); } catch (e) { return; }
      if (!m || typeof m.type !== 'string') return;
      switch (m.type) {
        case 'hello':
          var first = !S.group;
          if (m.you && m.you.pid) { pid = m.you.pid; store('gg_pid_' + code, pid); }
          if (m.you && m.you.level) S.level = m.you.level;
          apply(m);
          if (!S.scenario || S.scenario.id !== m.scenarioId) loadScenario(m.scenarioId); else renderAll();
          watchCar();
          if (first && !asked && !S.ended && startTab !== 'record') openSheet('first');
          break;
        case 'error': if (m.retryIn) retryIn = m.retryIn; break;
        case 'members': S.cars = m.cars || []; watchCar(); renderPickButton(); break;
        case 'trip': if (S.group) S.group.lang = m.lang; if (!S.scenario || S.scenario.id !== m.scenarioId) loadScenario(m.scenarioId); else renderAll(); break;
        // 차가 둘이면 QR 의 car=N 차 이야기만 따름
        case 'story': if (mineCar(m)) showStory(m); break;
        case 'line':
          if (!S.story || m.placeId !== S.story.placeId || !sameCar(m, S.story)) break;
          if (S.qa) endQa();   // 답을 다 말하고 하던 이야기로 돌아옴
          setLine(m.kid ? 'kid' : 'story', m.index);
          break;
        case 'story_end':
          if (S.story && m.placeId === S.story.placeId && sameCar(m, S.story)) { S.storyDone = true; S.story.done = true; renderNow(); renderCourse(); if (S.tab === 'pick') drawPickMarkers(); }
          break;
        case 'kid':
          if (!mineCar(m) || !S.story || m.placeId !== S.story.placeId) break;
          S.story.kid = m.text; S.kidLines = sentences(m.text);
          setLine('kid', 0);
          break;
        case 'pass':
          S.passes.push({ placeId: m.placeId, name: m.name, at: m.at, car: m.car, carNo: m.carNo });
          renderCourse();
          break;
        case 'pick':
          S.picks.push(m);
          // 그룹에 운전자 확인을 기다리는 곳이 생김: 답이 올 때까지(최대 90초) 모든 휴대폰의 보내기 단추를 잠금
          S.pending = { id: m.id, name: pickName(m.id, m.name), at: Date.now() };
          if (m.mine) { S.pickWait = null; S.pickSoonUntil = Date.now() + 20000; S.myPicks = (S.myPicks || {}); S.myPicks[m.id] = 1; setPickMsg(t('sent'), 'wait'); }
          else toast(f('pickOther', { by: m.by || '', name: pickName(m.id, m.name) }));
          renderPickButton();
          break;
        case 'pick_wait':
          // 서버가 보내지 않은 까닭: soon(20초 안에 또), busy(다른 곳 확인 중), nocar, bad
          S.pickWait = null;
          if (m.reason === 'soon') { S.pickSoonUntil = Date.now() + (m.retryIn || 20) * 1000; setPickMsg(null); }
          else if (m.reason === 'busy') { S.pending = { id: m.id || (S.pending && S.pending.id), name: m.name || '', at: Date.now() - Math.max(0, 90 - (m.retryIn || 90)) * 1000 }; setPickMsg(f('pickBusy', { name: m.name || '' }), 'wait'); }
          else setPickMsg(t(m.reason === 'nocar' ? 'pickNoCar' : 'pickBad'), 'warn');
          renderPickButton();
          break;
        case 'pick_ack':
          if (S.pending && S.pending.id === m.id) S.pending = null;
          var ackText = t(m.accepted === false ? 'pickLater' : 'acked');
          var minePick = !!(S.myPicks && S.myPicks[m.id]);
          if (minePick) setPickMsg(ackText, m.accepted === false ? 'later' : 'ok');
          else if (S.pickMsg && S.pickMsg.kind === 'wait') setPickMsg(null);
          // 장소 탭에서 내가 보낸 곳의 답은 단추 위에 이미 보이므로 알림을 겹쳐 띄우지 않음
          if (minePick && S.tab !== 'pick') toast(ackText);
          renderPickButton();
          break;
        case 'record': S.record = m.record; S.ended = true; card = null; renderNow(); renderQuiz(); renderCourse(); renderRecord(); break;
        case 'trip_end':
          S.ended = true; watchCar(); renderNow(); renderQuiz(); renderCourse(); renderRecord();
          // 지금 탭은 화면이 이미 같은 말을 하므로 장소 탭에 있을 때만 알림
          if (S.tab === 'pick') toast(t('ended'), function () { setTab('record'); }, t('seeRecord'));
          break;
        case 'quiz':
          S.quizzes = S.quizzes.filter(function (q) { return q.id !== m.id; }).concat([m]);
          if (!mineCar(m)) break;
          renderQuiz();
          // 지금 탭이면 퀴즈가 보이게 내려가고, 다른 탭이면 눌러서 갈 수 있는 알림만
          if (S.tab === 'now') setTimeout(function () { if (!$('quizCard').classList.contains('hidden') && !sheetMode) $('quizCard').scrollIntoView({ behavior: smooth(), block: 'center' }); }, 80);
          else { toast(t('quizNew'), function () { setTab('now'); setTimeout(function () { $('quizCard').scrollIntoView({ block: 'center' }); }, 80); }); nowNews(); }
          break;
        case 'quiz_answer': S.answers.push(m); renderQuiz(); break;
        case 'stars': if (mineCar(m)) { S.stars = m; renderQuiz(); } break;
        case 'qa': if (mineCar(m)) showQa(m); break;
      }
    };
  }

  // 화면을 다시 켜거나 와이파이가 돌아오면 기다리지 않고 바로 다시 붙음.
  // 20초 넘게 숨어 있었으면 연결이 살아 보여도 새로 붙어 그사이 바뀐 이야기를 다시 받음
  function resync() {
    if (stopped) return;
    var old = ws; ws = null;
    if (old) { try { old.close(); } catch (e) {} }
    S.online = false; retryMs = 1000; connect();
  }
  document.addEventListener('visibilitychange', function () {
    if (document.visibilityState === 'hidden') { hiddenAt = Date.now(); return; }
    var long = hiddenAt && Date.now() - hiddenAt > 20000;
    hiddenAt = 0;
    if (long || !ws || ws.readyState > 1) resync();
  });
  window.addEventListener('online', function () { if (!ws || ws.readyState > 1) resync(); });
  // 와이파이가 끊기면 소켓이 닫히기를 기다리지 않고 바로 알리고 다시 붙기 시작함
  window.addEventListener('offline', function () {
    if (stopped) return;
    var old = ws; ws = null;
    if (old) { try { old.close(); } catch (e) {} }
    lost();
    setNet(t('reconnecting'));
    clearTimeout(retryTimer);
    retryTimer = setTimeout(connect, retryMs);
  });

  if (!code) { location.href = '/'; return; }
  renderAll();
  setTab(startTab);
  loadPhotos();
  // 그룹 정보는 WebSocket 의 hello 로 받음 (번호 조회를 한 번만 셈)
  connect();
})();

