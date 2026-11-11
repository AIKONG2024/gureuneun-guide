/* 구르는 가이드 동승자 그룹 페이지 (휴대폰)
 * 앱 설치 없이 QR로 들어와, 차가 이야기를 시작하면 같은 순간 영상과 3D 모형을 보여 준다.
 * 탭은 셋.
 *  - 지금: 장면(영상·3D)과 차가 들려주는 이야기가 주인공. 보물 상자, 퀴즈, 차에 물어본 것은 생길 때만 뜨고
 *          맨 아래에 오늘 코스가 작게 붙음
 *  - 장소 고르기: 지도나 가 볼 만한 곳 목록에서 골라 그룹의 차 내비게이션으로 보냄 (운전자가 확인)
 *  - 기록: 지나온 길과 사진 (여행이 끝나면 달린 거리, 시간, 지난 명소 수도)
 * 색·글꼴·아이콘은 차 화면(Theme.kt, Components.kt, Icons.kt)과 같은 현대자동차 CI 색, Asta Sans, 선 아이콘을 쓴다.
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
  // QR 의 k(휴대폰 키)가 있으면 사진·기록까지 봄. 한 번 받은 키는 이 휴대폰에 두어 번호로 다시 들어와도 이어 씀
  var urlKey = qs.get('k') || '';
  var key = urlKey || store('gg_k_' + code) || '';
  if (urlKey && code) store('gg_k_' + code, urlKey);
  var S = {
    lang: store('gg_lang'), group: null, scenario: null, story: null, lines: [], lineIdx: -1, storyDone: false, passes: [], picks: [], acks: [],
    record: null, photos: [], cars: [], phones: 0, selected: null, online: false, tab: startTab, ended: false, fatal: null,
    quizzes: [], answers: [], scores: {}, qa: [], treasure: null, stars: null, kid: null, level: key ? 'full' : 'basic',
    pending: null, pickWait: null, pickMsg: null, quizPending: null, nowBadge: false
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
      appName: '구르는 가이드', now: '지금', pick: '장소 고르기', record: '기록',
      connecting: '연결하는 중', loading: '여행 그룹에 연결하고 있어요',
      nextStop: '다음 명소', waitText: '가까워지면 차가 이야기를 들려줘요. 그때 여기서 영상과 3D 모형을 같이 봐요.',
      allDone: '오늘 코스를 다 돌았어요', allDoneText: '여행이 끝나면 지나온 길과 사진을 기록에서 모아 봐요.',
      telling: '차가 들려주는 이야기', coming: '곧 보여요', told: '방금 들은 이야기',
      endedText: '지나온 길과 사진을 기록에서 모아 볼 수 있어요.', seeRecord: '기록 보기',
      video: '영상', orbit: '3D', tapPlay: '눌러서 영상 보기', orbitHint: '한 손가락으로 끌어 돌리고, 두 손가락으로 벌려 크게 봐요',
      name: '이름', meTitle: '내 이름', mePh: '예: 민지', meHint: '장소를 보내거나 퀴즈를 맞히면 차가 이 이름을 불러 줘요', meDone: '완료',
      introTitle: '반가워요!', introLead: '차가 들려주는 여행 이야기를 휴대폰으로 같이 봐요.', introOk: '알겠어요',
      intro1: '명소에 가까워지면 영상과 3D 모형이 여기 떠요', intro2: '퀴즈를 맞혀 별을 모아요', intro2t: '퀴즈를 맞혀 별을 다 모으면 보물 상자가 열려요', intro3: '가고 싶은 곳을 골라 차로 보낼 수 있어요',
      courseTitle: '오늘 코스', courseMeta: '{n}/{m}곳 지남', passed: '지남', nowAt: '지금', next: '다음',
      pickTitle: '다음에 갈 곳', pickHelp: '고른 곳은 운전자가 확인하면 내비게이션에 들어가요.', pickHint: '지도나 목록에서 골라 주세요',
      send1: '차 내비게이션으로 보내기', send2: '모든 차 내비게이션으로 보내기', custom: '지도에서 고른 곳',
      sent: '보냈어요. 운전자가 확인하고 있어요', acked: '내비게이션에 들어갔어요',
      pickLater: '운전자가 나중에 보기로 했어요. 차가 서면 다시 보여요', pickSoon: '{s}초 뒤에 다시 보낼 수 있어요',
      pickBusy: '운전자가 “{name}” 확인 중이에요. 답이 오면 보내 주세요', pickNoCar: '지금 연결된 차가 없어요', pickNoCarYet: '차가 연결되면 보낼 수 있어요', pickBad: '보낼 수 없는 곳이에요. 코스 근처를 골라 주세요',
      pickOther: '{by} 님이 고른 곳: {name}',
      recEyebrow: '오늘의 여행 기록', recPlanned: '오늘 갈 길', recEmpty: '여행이 끝나면 지나온 길과 사진이 여기 모여요.', recRange: '{a}부터 {b}까지',
      statKm: '달린 거리', statTime: '여행 시간', statPlaces: '지난 명소', unitPlaces: '곳', unitMin: '분', unitH: '시간',
      photoTitle: '사진', photoCount: '{n}장', photoHelp: '사진을 올리면 찍은 시각에 맞춰 지나온 길 위에 붙어요.', photoBtn: '사진 올리기', uploading: '올리는 중', photoFail: '사진을 올리지 못했어요',
      basicTitle: '사진과 기록은 QR로 들어오면 보여요', basicNote: '번호로 들어오면 이야기와 퀴즈, 장소 고르기만 쓸 수 있어요. 차 화면의 QR을 찍어 다시 들어와 주세요.',
      keep: '이 그룹 사람만 보고, 여행이 끝나고 {d}일 뒤 지워져요', keepOff: '이 그룹 사람만 봐요',
      tripEnd: '오늘 여행이 끝났어요', window: '창문이 조금 내려갔어요. 바깥 소리를 들어 보세요',
      kind: { cafe: '카페', parking: '주차장', landmark: '명소', sea: '바다', city: '도시', place: '장소' },
      quiz: '{name} 퀴즈', newQuiz: '새 퀴즈가 왔어요', right: '정답이에요! 별 하나 모았어요', wrong: '아쉬워요. 정답은 “{a}”{josa}', wrongShort: '아쉬워요. 다음 퀴즈에서 맞혀 봐요', gotIt: '{by} 님이 맞혔어요',
      treasureOpen: '보물 상자가 열렸어요!', treasureOpenText: '조수석 앞 글로브 박스를 열어 보세요', treasureHand: '글로브 박스를 직접 열어 보세요',
      treasureReady: '별을 다 모았어요!', treasureReadyText: '차를 세우면 보물 상자를 열어 줘요',
      qaTitle: '차에 물어본 것', asked: '차에 물어봤어요: {q}',
      kidTitle: '뒷자리 친구들에게',
      reconnecting: '연결이 끊겼어요. 다시 연결하는 중…', tooMany: '잠시 뒤에 다시 연결해요 ({s}초)',
      problemTitle: '여행 그룹을 열 수 없어요', noGroup: '끝난 지 오래된 여행이거나 번호가 달라요.', badKey: 'QR 주소가 맞지 않아요. 차 화면의 QR을 다시 찍어 주세요.', again: '번호 다시 넣기',
      see: '보기', car: '{n}호차', carCount: '차 {n}대 연결', carNone: '차 연결 전', tripEnded: '여행 끝', codeLabel: '그룹 {c}', offline: '연결 안 됨',
      langBtn: 'English', langShort: 'EN', langAria: 'Show in English'
    },
    en: {
      appName: 'Rolling Guide', now: 'Now', pick: 'Places', record: 'Record',
      connecting: 'Connecting', loading: 'Connecting to the trip group',
      nextStop: 'Next stop', waitText: "When we get close, the car tells the story. You'll see the video and 3D model here.",
      allDone: "We've passed every stop", allDoneText: 'When the trip ends, your route and photos are collected in Record.',
      telling: 'The car is telling', coming: 'Coming up', told: 'Story you just heard',
      endedText: 'See your route and photos in Record.', seeRecord: 'Open Record',
      video: 'Video', orbit: '3D', tapPlay: 'Tap to play', orbitHint: 'Drag with one finger to turn, pinch to zoom',
      name: 'Name', meTitle: 'Your name', mePh: 'e.g. Alex', meHint: 'The car calls you by this name when you send a place or get a quiz right', meDone: 'Done',
      introTitle: 'Welcome aboard', introLead: "Follow the car's travel stories on your phone.", introOk: 'Got it',
      intro1: 'Near a landmark, its video and 3D model show up here', intro2: 'Answer quizzes to collect stars', intro2t: 'Collect every quiz star to open the treasure box', intro3: 'Pick where to go next and send it to the car',
      courseTitle: "Today's course", courseMeta: '{n} of {m} passed', passed: 'Passed', nowAt: 'Now', next: 'Next',
      pickTitle: 'Where to next?', pickHelp: 'Your pick goes into the navigation once the driver confirms.', pickHint: 'Pick on the map or from the list',
      send1: 'Send to the car navigation', send2: 'Send to every car navigation', custom: 'Spot picked on the map',
      sent: 'Sent. The driver is checking it', acked: 'Added to the navigation',
      pickLater: 'The driver saved it for later. It shows up again when the car stops', pickSoon: 'You can send again in {s} s',
      pickBusy: 'The driver is checking “{name}”. Send yours after they answer', pickNoCar: 'No car is connected right now', pickNoCarYet: 'You can send once a car connects', pickBad: "Can't send that spot. Pick somewhere near the course",
      pickOther: '{by} picked: {name}',
      recEyebrow: "Today's trip record", recPlanned: "Today's route", recEmpty: 'Your route and photos are collected here when the trip ends.', recRange: '{a} to {b}',
      statKm: 'Distance', statTime: 'Time', statPlaces: 'Places', unitPlaces: '', unitMin: 'min', unitH: 'h',
      photoTitle: 'Photos', photoCount: '{n}', photoHelp: 'Photos are pinned on the route by the time they were taken.', photoBtn: 'Upload photos', uploading: 'Uploading', photoFail: "Couldn't upload the photo",
      basicTitle: 'Scan the QR to see photos and the record', basicNote: 'With the code you can follow stories, quizzes and pick places. Scan the QR on the car screen to join again.',
      keep: "Only people in this group can see these. They're deleted {d} days after the trip ends.", keepOff: 'Only people in this group can see these.',
      tripEnd: "Today's trip is over", window: 'The windows are down a little. Listen to the outside',
      kind: { cafe: 'Cafe', parking: 'Parking', landmark: 'Landmark', sea: 'Sea', city: 'City', place: 'Place' },
      quiz: '{name} quiz', newQuiz: 'A new quiz arrived', right: 'Correct! You earned a star', wrong: 'Not quite. The answer is “{a}”', wrongShort: 'Not quite. Try the next one', gotIt: '{by} answered correctly',
      treasureOpen: 'The treasure box is open!', treasureOpenText: 'Open the glove box in front of the passenger seat', treasureHand: 'Open the glove box by hand',
      treasureReady: 'All stars collected!', treasureReadyText: 'Park the car and the treasure box opens',
      qaTitle: 'Asked in the car', asked: 'Asked the car: {q}',
      kidTitle: 'For the back seat',
      reconnecting: 'Connection lost. Reconnecting…', tooMany: 'Reconnecting in {s} s',
      problemTitle: "Can't open the trip group", noGroup: 'The trip may have ended a while ago, or the code is different.', badKey: "This QR link doesn't match. Scan the QR on the car screen again.", again: 'Enter the code again',
      see: 'View', car: 'Car {n}', carCount: '{n} car', carCountMany: '{n} cars', carNone: 'No car yet', tripEnded: 'Trip ended', codeLabel: 'Group {c}', offline: 'Not connected',
      langBtn: '한국어', langShort: '한국어', langAria: '한국어로 보기'
    }
  };
  // 고른 언어 > 그룹(차)의 안내 언어 > 그룹을 아직 모르면(연결 전, 열 수 없는 그룹) 휴대폰 언어
  function lang() { return S.lang || (S.group && S.group.lang) || (/^ko\b/i.test(navigator.language || 'ko') ? 'ko' : 'en'); }
  function t(k) { var d = TXT[lang()] || TXT.ko; return d[k] !== undefined ? d[k] : (TXT.ko[k] !== undefined ? TXT.ko[k] : k); }
  function f(k, o) { var s = t(k); Object.keys(o || {}).forEach(function (x) { s = s.split('{' + x + '}').join(o[x]); }); return s; }
  function nm(l10n) { if (!l10n) return ''; return typeof l10n === 'string' ? l10n : (l10n[lang()] || l10n.ko || l10n.en || ''); }
  function sentences(text) { return String(text || '').replace(/([.!?])\s+/g, '$1\u0001').split('\u0001').map(function (s) { return s.trim(); }).filter(Boolean); }
  function hhmm(ms) { var d = new Date(ms); return ('0' + d.getHours()).slice(-2) + ':' + ('0' + d.getMinutes()).slice(-2); }
  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }
  // 받침이 있으면 a(이에요), 없으면 b(예요). 한글이 아니면 b
  function josa(word, a, b) {
    var c = String(word || '').trim().slice(-1).charCodeAt(0);
    return c >= 0xAC00 && c <= 0xD7A3 && (c - 0xAC00) % 28 ? a : b;
  }
  // 차 이름은 서버가 '1호차'처럼 붙여 줌. 영어 화면에서는 'Car 1'
  function carLabel(no, name) { return no ? f('car', { n: no }) : (lang() === 'en' ? '' : (name || '')); }
  // 이 휴대폰이 따르는 차의 소식인지 (car=N 으로 들어왔으면 그 차만, 번호로 들어왔으면 모두)
  function mineCar(m) { return !myCar || !m || !m.carNo || m.carNo === myCar; }
  function withKey(url) { return key ? url + (url.indexOf('?') < 0 ? '?' : '&') + 'k=' + encodeURIComponent(key) : url; }
  function keyHeaders(h) { h = h || {}; if (key) h['X-Group-Key'] = key; return h; }
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
    starOff: '<path d="' + STAR + '"/>',
    user: '<circle cx="12" cy="8.2" r="3.8"/><path d="M4.6 20.2C4.6 16.2 7.9 13.6 12 13.6C16.1 13.6 19.4 16.2 19.4 20.2"/>',
    qr: '<rect x="3.6" y="3.6" width="6.6" height="6.6" rx="1.4"/><rect x="13.8" y="3.6" width="6.6" height="6.6" rx="1.4"/><rect x="3.6" y="13.8" width="6.6" height="6.6" rx="1.4"/><path class="d" d="M6 6H7.8V7.8H6ZM16.2 6H18V7.8H16.2ZM6 16.2H7.8V18H6ZM13.8 13.8H16V16H13.8ZM18.2 13.8H20.4V16H18.2ZM13.8 18.2H16V20.4H13.8ZM18.2 18.2H20.4V20.4H18.2Z"/>',
    check: '<path d="M5 12.6L9.8 17.2L19 7.2"/>',
    close: '<path d="M6.2 6.2L17.8 17.8M17.8 6.2L6.2 17.8"/>',
    chevron: '<path d="M9 5.5L15.5 12L9 18.5"/>',
    clock: '<circle cx="12" cy="12" r="8.8"/><path d="M12 12V7.2M12 12L15.6 14.2"/>',
    kid: '<circle cx="12" cy="12" r="8.8"/><circle class="d" cx="9" cy="10.4" r="1.25"/><circle class="d" cx="15" cy="10.4" r="1.25"/><path d="M15.63 14.29A4 4 0 0 1 8.37 14.29"/>',
    flag: '<path d="M6 21V3.6"/><path d="M6 4.4H18.6L15.6 8.5L18.6 12.6H6"/>',
    gift: '<rect x="4.2" y="10.6" width="15.6" height="10" rx="1.6"/><rect x="3" y="7.2" width="18" height="3.4" rx="1.2"/><path d="M12 7.2V20.6"/><path d="M12 7.2C10.4 3.6 6.6 3.8 7.1 6C7.4 7.2 9.8 7.2 12 7.2ZM12 7.2C13.6 3.6 17.4 3.8 16.9 6C16.6 7.2 14.2 7.2 12 7.2Z"/>',
    cup: '<path d="M4.6 9.4H15.6V15C15.6 17.8 13.4 20 10.6 20H9.6C6.8 20 4.6 17.8 4.6 15Z"/><path d="M15.6 11.2H17.2C18.7 11.2 19.8 12.3 19.8 13.7C19.8 15.1 18.7 16.2 17.2 16.2H15.6"/><path d="M8.2 3.6V6.2M12 3.6V6.2"/>',
    parking: '<rect x="4" y="3.5" width="16" height="17" rx="4"/><path d="M9.6 16.6V7.4H13.2C15 7.4 16.2 8.7 16.2 10.4C16.2 12.1 15 13.4 13.2 13.4H9.6"/>',
    send: '<path d="M3.4 11.2L20.6 3.8L14.6 20.4L11.4 12.8Z"/><path d="M11.4 12.8L20.4 4"/>',
    info: '<circle cx="12" cy="12" r="9"/><path d="M12 11V16.6"/><circle class="d" cx="12" cy="7.6" r="1.3"/>',
    mic: '<rect class="f" x="9" y="2.8" width="6" height="11.4" rx="3"/><path d="M18.2 11A6.2 6.2 0 0 1 5.8 11M12 17.2V20.6M8.6 20.6H15.4"/>',
    play: '<path class="f" d="M8 5L19 12L8 19Z"/>',
    cube: '<path d="M12 3L19.8 7.4V16.6L12 21L4.2 16.6V7.4Z"/><path d="M4.2 7.4L12 11.8L19.8 7.4M12 11.8V21"/>',
    camera: '<rect x="2.8" y="7" width="18.4" height="12.8" rx="2.8"/><path d="M8.2 7L9.6 4.4H14.4L15.8 7"/><circle cx="12" cy="13.4" r="3.6"/>',
    image: '<rect x="3.2" y="4.2" width="17.6" height="15.6" rx="2.8"/><circle cx="8.8" cy="9.4" r="1.8"/><path d="M3.8 17.2L9 12.4L12.6 15.6L15.4 13L20.2 17.4"/>',
    lock: '<rect x="5" y="10.4" width="14" height="10.2" rx="2.6"/><path d="M8.4 10.4V7.8A3.6 3.6 0 0 1 15.6 7.8V10.4"/>',
    alert: '<path d="M12 3.8L21.2 19.8H2.8Z"/><path d="M12 9.8V14.2"/><circle class="d" cx="12" cy="17" r="1.25"/>',
    globe: '<circle cx="12" cy="12" r="9"/><ellipse cx="12" cy="12" rx="4" ry="9"/><path d="M3.2 12H20.8"/>',
    car: '<path d="M5 13.2L6.6 8.4C7 7.3 8 6.6 9.2 6.6H14.8C16 6.6 17 7.3 17.4 8.4L19 13.2"/><rect x="3.5" y="12.6" width="17" height="5.6" rx="2.2"/><path d="M6.2 18.6V20.4M17.8 18.6V20.4"/><circle class="d" cx="7.4" cy="15.4" r="1.2"/><circle class="d" cx="16.6" cy="15.4" r="1.2"/>'
  };
  function icon(name, cls) { return '<svg class="ic' + (cls ? ' ' + cls : '') + '" viewBox="0 0 24 24" aria-hidden="true" focusable="false">' + (ICON[name] || '') + '</svg>'; }
  Array.prototype.forEach.call(document.querySelectorAll('i[data-ic]'), function (el) { el.outerHTML = icon(el.getAttribute('data-ic')); });
  var KIND_IC = { cafe: 'cup', parking: 'parking', landmark: 'pin', sea: 'pin', city: 'pin', place: 'pin' };

  // 알림. onTap 을 주면 눌러서 그쪽으로 갈 수 있음 (퀴즈가 와도 탭을 억지로 바꾸지 않음)
  var toastTimer, toastTap = null;
  function toast(msg, onTap, act) {
    var el = $('toast');
    $('toastText').textContent = msg;
    $('toastAct').textContent = onTap ? (act || t('see')) : '';
    // 장소 고르기에서는 보내기 줄을 가리지 않게 그 위에 띄움
    el.style.setProperty('--lift', S.tab === 'pick' ? ($('sendbar').offsetHeight + 10) + 'px' : '0px');
    el.classList.add('show'); el.classList.toggle('tap', !!onTap); toastTap = onTap || null;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { el.classList.remove('show'); el.classList.remove('tap'); toastTap = null; }, onTap ? 6000 : 3500);
  }
  $('toast').onclick = function () { var fn = toastTap; if (!fn) return; toastTap = null; $('toast').classList.remove('show'); fn(); };
  // 지금 탭의 카드로 데려감 (다른 탭이면 지금 탭으로 바꾸고)
  function focusCard(id) {
    setTab('now');
    setTimeout(function () { var el = $(id); if (el && !el.classList.contains('hidden')) el.scrollIntoView({ behavior: smooth(), block: 'center' }); }, 60);
  }

  // ---------------------------------------------------------------- 장면: 미리 만든 영상 클립과 3D 모형
  // '영상'은 미리 만든 짧은 클립(/clips/모형.mp4), '3D'는 손으로 돌려 보는 실시간 3D 모형
  GG.mount($('stage'), { model: 'cheomseongdae' });
  GG.setPaused(true);
  var heroMode = 'loading', scene = { model: null };
  var clipModel = null, clipNight = false, clipOk = false;
  var clipEl = $('clip');
  clipEl.onerror = function () { clipOk = false; showVideoMode(false); GG.setMode('cinematic'); };
  clipEl.onloadeddata = function () { clipOk = true; };
  function setClip(model, night) {
    if (!model) { clipOk = false; showVideoMode(false); return; }
    if (model === clipModel && night === clipNight) return;
    clipModel = model; clipNight = night; clipOk = true;
    var tryNight = night ? '/clips/' + model + '_night.mp4' : null;
    clipEl.onerror = function () {
      if (tryNight && clipEl.getAttribute('src') === tryNight) { clipEl.poster = '/posters/' + model + '_0.jpg'; clipEl.src = '/clips/' + model + '.mp4'; playClip(); return; }
      clipOk = false; showVideoMode(false); GG.setMode('cinematic');
    };
    // 자동 재생이 막히면(절전 모드 등) 포스터 사진을 보여 주고 재생 단추를 띄움
    clipEl.poster = '/posters/' + model + (night ? '_night' : '') + '_0.jpg';
    clipEl.src = tryNight || ('/clips/' + model + '.mp4');
    playClip();
  }
  // 움직임 줄이기를 켠 휴대폰은 영상을 저절로 틀지 않고 포스터와 재생 단추를 보여 줌 (한 번 누르면 그다음부터는 틂)
  var userPlayed = false;
  function playClip() {
    if (S.tab !== 'now' || clipEl.classList.contains('hidden')) return;
    if (reduceMotion.matches && !userPlayed) { $('playBtn').classList.remove('hidden'); return; }
    var p = clipEl.play && clipEl.play();
    if (p && p.then) p.then(function () { $('playBtn').classList.add('hidden'); }, function () { if (!clipEl.classList.contains('hidden') && S.tab === 'now') $('playBtn').classList.remove('hidden'); });
  }
  $('playBtn').onclick = function () { userPlayed = true; clipEl.muted = true; playClip(); };
  clipEl.onplaying = function () { $('playBtn').classList.add('hidden'); };
  function showVideoMode(on) {
    clipEl.classList.toggle('hidden', !on);
    if (!on) { $('playBtn').classList.add('hidden'); try { clipEl.pause(); } catch (e) {} }
    GG.setPaused(on || S.tab !== 'now' || heroMode === 'loading');
    if (on) playClip();
    renderModeButtons();
  }
  function renderModeButtons() {
    var orbit = clipEl.classList.contains('hidden') && GG.mode() === 'orbit';
    $('mVideo').classList.toggle('on', !orbit); $('mVideo').setAttribute('aria-pressed', String(!orbit));
    $('mOrbit').classList.toggle('on', orbit); $('mOrbit').setAttribute('aria-pressed', String(orbit));
  }
  GG.onMode = function (m) { if (m === 'orbit') showVideoMode(false); else renderModeButtons(); };
  // 3D 를 돌려 본 뒤 '영상'으로 돌아오면 화면을 다시 위아래로 밀 수 있게 cinematic 으로 되돌림
  $('mVideo').onclick = function () { GG.setMode('cinematic'); if (clipOk && clipModel) showVideoMode(true); };
  $('mOrbit').onclick = function () { showVideoMode(false); GG.setMode('orbit'); };
  // 같은 장면이면 다시 부르지 않음 (손으로 돌려 보던 3D 가 다른 알림 때문에 되돌아가지 않게)
  function setScene(model, night, rain, force) {
    model = model || 'generic';
    if (!force && scene.model === model && scene.night === !!night && scene.rain === !!rain) return;
    scene = { model: model, night: !!night, rain: !!rain };
    GG.show({ model: model, rain: !!rain, night: !!night, mode: 'cinematic' });
    setClip(model, !!night);
    showVideoMode(clipOk);
  }
  // 다른 탭에 있는 동안은 영상과 3D 를 멈춰 배터리를 아낌
  function pauseStage() { try { clipEl.pause(); } catch (e) {} GG.setPaused(true); }
  function resumeStage() {
    if (heroMode === 'loading' || heroMode === 'fatal') return;
    if (!clipEl.classList.contains('hidden')) { GG.setPaused(true); playClip(); } else GG.setPaused(false);
  }

  // ---------------------------------------------------------------- 탭과 언어
  var TABS = ['now', 'pick', 'record'];
  function setTab(tab) {
    if (TABS.indexOf(tab) < 0) tab = 'now';
    var was = S.tab;
    S.tab = tab;
    TABS.forEach(function (k) { $('tab-' + k).classList.toggle('hidden', k !== tab); });
    Array.prototype.forEach.call(document.querySelectorAll('.tabs button'), function (b) { var on = b.dataset.tab === tab; b.classList.toggle('on', on); b.setAttribute('aria-selected', on ? 'true' : 'false'); });
    if (tab === 'now') { S.nowBadge = false; resumeStage(); } else pauseStage();
    renderBadges();
    if (was !== tab) window.scrollTo(0, 0);
    if (tab === 'pick') setTimeout(drawPickMap, 50);
    if (tab === 'record') setTimeout(drawRecord, 50);
  }
  Array.prototype.forEach.call(document.querySelectorAll('.tabs button'), function (b) { b.onclick = function () { setTab(b.dataset.tab); }; });
  // 다른 탭에 있는 동안 지금 탭에 새 소식(이야기, 퀴즈, 보물 상자)이 오면 작은 점
  function renderBadges() { $('tNow').querySelector('.badge').classList.toggle('hidden', !(S.nowBadge && S.tab !== 'now')); }
  function nowNews() { if (S.tab !== 'now') { S.nowBadge = true; renderBadges(); } }
  function toggleLang() { S.lang = lang() === 'en' ? 'ko' : 'en'; store('gg_lang', S.lang); renderAll(); }
  $('langBtn').onclick = toggleLang;
  $('sheetLang').onclick = toggleLang;

  // ---------------------------------------------------------------- 처음 안내와 이름
  // 처음 들어온 휴대폰: 할 수 있는 것과 이름 칸을 아래에서 올라오는 창으로. 나중에는 머리 줄 이름 단추로 이름만 바꿈
  // (이름을 이미 적어 둔 휴대폰은 처음이 아니라고 봄. 기록 QR 로 들어왔거나 끝난 여행이면 안내 없이 바로)
  var introSeen = store('gg_intro') === '1' || !!me;
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
    if (sheetMode === 'intro') { introSeen = true; store('gg_intro', '1'); }
    sheetMode = null;
    var w = $('sheet');
    w.classList.remove('open');
    try { $('me').blur(); } catch (e) {}
    sheetTimer = setTimeout(function () { w.classList.add('hidden'); }, 260);
    renderTop();
  }
  function renderSheet() {
    if (!sheetMode) return;
    var intro = sheetMode === 'intro', sc = S.scenario;
    $('introPart').classList.toggle('hidden', !intro);
    $('sheetTitle').textContent = intro ? t('introTitle') : t('meTitle');
    $('introLead').textContent = t('introLead');
    var items = [['play', t('intro1')], ['star', sc && sc.treasure ? t('intro2t') : t('intro2')], ['send', t('intro3')]];
    $('introList').innerHTML = items.map(function (x) { return '<li><span class="ii">' + icon(x[0]) + '</span><span>' + esc(x[1]) + '</span></li>'; }).join('');
    $('meLabel').textContent = t('meTitle');
    // 이름만 바꿀 때는 창 제목이 같은 말이라 칸 이름은 화면 읽기용으로만 둠
    $('meLabel').classList.toggle('sr-only', !intro);
    $('introOk').textContent = intro ? t('introOk') : t('meDone');
  }
  $('introOk').onclick = closeSheet;
  $('sheet').onclick = function (e) { if (e.target === $('sheet')) closeSheet(); };
  document.addEventListener('keydown', function (e) { if (e.key === 'Escape') closeSheet(); });
  $('meBtn').onclick = function () { openSheet('edit'); };
  function maybeIntro() {
    if (introSeen || stopped || S.ended || startTab === 'record' || sheetMode) return false;
    openSheet('intro');
    return true;
  }

  function staticTexts() {
    document.documentElement.lang = lang();
    $('tNowL').textContent = t('now'); $('tPickL').textContent = t('pick'); $('tRecordL').textContent = t('record');
    $('mVideoL').textContent = t('video'); $('mOrbitL').textContent = t('orbit'); $('playBtnL').textContent = t('tapPlay');
    $('toRecordL').textContent = t('seeRecord');
    $('kidTitle').textContent = t('kidTitle'); $('qaTitle').textContent = t('qaTitle'); $('courseTitle').textContent = t('courseTitle');
    $('pickTitle').textContent = t('pickTitle'); $('pickHelp').textContent = t('pickHelp');
    $('photoTitle').textContent = t('photoTitle'); $('photoHelp').textContent = t('photoHelp'); $('photoBtnL').textContent = t('photoBtn');
    $('basicTitle').textContent = t('basicTitle'); $('basicNote').textContent = t('basicNote');
    var days = S.group && typeof S.group.keepDays === 'number' ? S.group.keepDays : 3;
    $('keepNoteL').textContent = days > 0 ? f('keep', { d: days }) : t('keepOff');
    // 번호로만 들어온 휴대폰(basic)에는 사진 올리기 대신 QR 안내
    $('photoCard').classList.toggle('hidden', !full());
    $('keepNote').classList.toggle('hidden', !full()); $('basicCard').classList.toggle('hidden', full());
    $('me').placeholder = t('mePh'); $('meHint').textContent = t('meHint');
    $('sheetLangL').textContent = t('langBtn'); $('langBtnL').textContent = t('langShort');
    [$('sheetLang'), $('langBtn')].forEach(function (b) { b.setAttribute('aria-label', t('langAria')); b.lang = lang() === 'en' ? 'ko' : 'en'; });
    GG.setHint(t('orbitHint'));
    renderSheet();
  }

  function renderTop() {
    var sc = S.scenario, n = S.cars.length || 0;
    $('title').textContent = sc ? nm(sc.title) : t('appName');
    document.title = sc ? nm(sc.title) + ' · ' + t('appName') : t('appName');
    // 몇 대가 붙어 있는지 말로 보여 줌. 끝난 여행은 '여행 끝', 아직 차가 안 붙었으면 '차 연결 전'
    // 연결이 끊긴 동안에는 차 대수 대신 '연결 안 됨' (맨 위 띠와 같은 말)
    var st = S.fatal || (S.group && !S.online) ? t('offline') : !S.group ? t('connecting') : S.ended && n === 0 ? t('tripEnded') : n === 0 ? t('carNone') : f(n > 1 && lang() === 'en' ? 'carCountMany' : 'carCount', { n: n });
    if (myCar && n > 1 && S.online) st = carLabel(myCar) + ' · ' + st;
    $('cars').textContent = st;
    $('who').textContent = code && S.group && !S.fatal ? ' · ' + f('codeLabel', { c: code }) : '';
    $('liveDot').classList.toggle('on', S.online && n > 0);
    $('meName').textContent = me || t('name');
    $('meBtn').classList.toggle('empty', !me);
    $('meBtn').setAttribute('aria-label', t('meTitle') + (me ? ': ' + me : ''));
  }

  // ---------------------------------------------------------------- 지금: 장면과 이야기
  function placeById(id) {
    var sc = S.scenario; if (!sc) return null;
    return sc.places.concat(sc.pickables || []).filter(function (p) { return p.id === id; })[0] || null;
  }
  function courseStops() { return S.scenario ? S.scenario.places.filter(function (p) { return p.kind !== 'city'; }) : []; }
  function passedMap() { var m = {}; S.passes.forEach(function (p) { if (p && mineCar(p) && !m[p.placeId]) m[p.placeId] = p.at || 1; }); return m; }
  // 다음 명소: '곧 보여요'로 알린 곳이 있으면 그곳. 아니면 지금 이야기하는 곳과 지난 곳 가운데 가장 뒤의 다음, 없으면 처음부터 아직 안 지난 곳
  function nextStop() {
    var passed = passedMap(), stops = courseStops(), st = S.story && !S.storyDone && !S.ended ? S.story : null;
    if (st && st.kind === 'announce') { var a = stops.filter(function (p) { return p.id === st.placeId && !passed[p.id]; })[0]; if (a) return a; }
    var cur = st && st.kind !== 'announce' ? st.placeId : null, at = -1;
    stops.forEach(function (p, i) { if (p.id === cur || passed[p.id]) at = i; });
    var left = function (from) { return stops.filter(function (p, i) { return i > from && !passed[p.id] && p.id !== cur; })[0] || null; };
    return left(at) || left(-1);
  }
  function lastModel() {
    var passed = passedMap(), stops = courseStops(), last = null;
    stops.forEach(function (p) { if (passed[p.id] && p.model) last = p; });
    return (last || stops.filter(function (p) { return p.model; })[0] || {}).model || null;
  }

  // 장면 카드의 상태: loading(연결 중) / waiting(다음 명소 미리 보기) / announce(곧 보여요) / story(듣는 중) / done(방금 들음) / ended(여행 끝) / fatal
  function renderNow() {
    var st = S.story, sc = S.scenario;
    var mode = S.fatal ? 'fatal' : (!S.group || !sc) ? 'loading' : S.ended && (!st || S.storyDone) ? 'ended' : !st ? 'waiting' : st.kind === 'announce' ? 'announce' : S.storyDone ? 'done' : 'story';
    var was = heroMode;
    heroMode = mode;
    $('hero').dataset.mode = mode;
    $('hero').classList.toggle('hidden', mode === 'fatal');
    $('problemCard').classList.toggle('hidden', mode !== 'fatal');
    document.body.classList.toggle('is-fatal', mode === 'fatal');
    if (mode === 'fatal') { renderProblem(); pauseStage(); return; }
    if (was === 'loading' && mode !== 'loading' && S.tab === 'now') resumeStage();
    var ic = 'speaker', eyebrow = '', name = '', body = '';
    if (mode === 'loading') body = t('loading');
    else if (mode === 'waiting') {
      var nx = nextStop();
      ic = 'pin';
      if (nx) { eyebrow = t('nextStop'); name = nm(nx.name); body = t('waitText'); setScene(nx.model, false, false); }
      else { ic = 'flag'; eyebrow = t('courseTitle'); name = t('allDone'); body = t('allDoneText'); setScene(lastModel(), false, false); }
    } else if (mode === 'ended') {
      ic = 'flag'; eyebrow = t('tripEnded'); name = nm(sc.title); body = t('endedText');
      if (!st) setScene(lastModel(), false, false);
    } else {
      var place = placeById(st.placeId);
      ic = mode === 'done' ? 'check' : 'speaker';
      eyebrow = (mode === 'announce' ? t('coming') : mode === 'done' ? t('told') : t('telling')) + (S.cars.length > 1 && st.carNo ? ' · ' + carLabel(st.carNo, st.car) : '');
      name = place ? nm(place.name) : st.name;
    }
    $('nowLabel').innerHTML = eyebrow ? icon(ic) + '<span>' + esc(eyebrow) + '</span>' : '';
    $('nowName').textContent = name;
    if (mode === 'story' || mode === 'done' || mode === 'announce') renderLines();
    else { $('segs').innerHTML = ''; $('lines').innerHTML = body ? '<span class="soft">' + esc(body) + '</span>' : ''; }
    $('toRecord').classList.toggle('hidden', mode !== 'ended');
    renderKid();
  }

  function renderLines() {
    if (!S.story) return;
    var n = S.lines.length, idx = S.storyDone ? n - 1 : Math.min(n - 1, Math.max(0, S.lineIdx)), segs = '';
    if (n > 1) for (var i = 0; i < n; i++) segs += '<i class="' + (S.storyDone || i <= idx ? 'on' : '') + '"></i>';
    $('segs').innerHTML = segs;
    $('lines').innerHTML = S.lines.map(function (s, i) {
      var cls = S.storyDone ? '' : i < idx ? 'done' : i === idx ? 'cur' : '';
      return '<span class="' + cls + '">' + esc(s) + ' </span>';
    }).join('');
  }

  // 아이 눈높이 이야기: 지금 이야기와 같은 명소일 때만 장면 카드 안에
  function renderKid() {
    var k = S.kid, st = S.story;
    var show = !!(k && st && k.placeId === st.placeId && (heroMode === 'story' || heroMode === 'done'));
    $('kidCard').classList.toggle('hidden', !show);
    if (show) $('kidText').textContent = k.text;
  }

  function renderProblem() {
    $('problemTitle').textContent = t('problemTitle');
    $('problemText').textContent = t(S.fatal || 'noGroup');
    $('problemBtn').textContent = t('again');
  }

  // quiet: 다시 연결했을 때 다시 그리기만 (탭을 바꾸거나 진동하지 않음)
  function showStory(m, quiet) {
    var prev = S.story;
    S.story = m; S.lines = sentences(m.text); S.lineIdx = 0; S.storyDone = !!m.done;
    var place = placeById(m.placeId);
    var model = m.model || (place && place.model) || 'generic';
    // 새 명소면 영상부터 다시. 같은 명소의 '곧 보여요' 다음 이야기는 돌려 보던 3D 를 그대로 둠
    setScene(model, !!m.night, !!m.rain, !prev || prev.placeId !== m.placeId);
    renderNow(); renderCourse();
    if (quiet) return;
    if (S.tab !== 'now') { toast(t('telling') + ': ' + (place ? nm(place.name) : m.name), function () { setTab('now'); }); nowNews(); }
    if (navigator.vibrate) navigator.vibrate(40);
  }

  $('toRecord').onclick = function () { setTab('record'); };

  // 오늘 코스: 지난 곳(파랑), 지금(파랑과 빛), 다음(흰 테두리), 남은 곳(회색)
  function renderCourse() {
    var stops = courseStops();
    $('courseCard').classList.toggle('hidden', !stops.length || heroMode === 'loading' || heroMode === 'fatal');
    if (!stops.length) return;
    var passed = passedMap(), nowId = S.story && !S.storyDone && S.story.kind !== 'announce' && !S.ended ? S.story.placeId : null, nx = S.ended ? null : nextStop(), done = 0, prevOn = false;
    $('course').innerHTML = stops.map(function (p, i) {
      var state = p.id === nowId ? 'now' : passed[p.id] ? 'done' : nx && nx.id === p.id ? 'next' : 'todo';
      var on = state === 'now' || state === 'done';
      if (passed[p.id]) done++;
      var label = state === 'now' ? t('nowAt') : state === 'done' ? t('passed') + ' ' + hhmm(passed[p.id]) : state === 'next' ? t('next') : '';
      var li = '<li class="' + state + (on && prevOn ? ' trail' : '') + '"><span class="cdot">' + (i + 1) + '</span><span class="cname">' + esc(nm(p.name)) + '</span><span class="cstat">' + esc(label) + '</span></li>';
      prevOn = on;
      return li;
    }).join('');
    $('courseMeta').textContent = f('courseMeta', { n: done, m: stops.length });
  }

  // ---------------------------------------------------------------- 퀴즈와 보물 상자
  function starsHtml(have, need) {
    var n = Math.max(need || 0, have || 0), s = '';
    for (var i = 0; i < n; i++) s += i < have ? icon('star') : icon('starOff', 'off');
    return s;
  }
  // 서버가 이 휴대폰이 한 답(mine)에만 고른 번호와 정답을 붙여 줌. 다른 사람 답은 맞혔는지만
  function myAnswer(id) { return S.answers.filter(function (a) { return a.id === id && a.mine; })[0] || null; }
  function currentQuiz() { return S.quizzes.filter(function (q) { return mineCar(q); }).sort(function (a, b) { return b.at - a.at; })[0] || null; }
  function starCount() {
    if (S.stars) return S.stars.stars;
    var ok = {}; S.answers.forEach(function (a) { if (a.correct) ok[a.id] = 1; });
    return Object.keys(ok).length;
  }
  function starNeed() { return (S.stars && S.stars.need) || (S.quizzes[0] && S.quizzes[0].need) || 0; }

  function renderQuiz() {
    var q = currentQuiz(), card = $('quizCard');
    if (!q) { card.classList.add('hidden'); return; }
    card.classList.remove('hidden');
    $('quizLabel').textContent = f('quiz', { name: q.name || '' });
    $('quizStars').innerHTML = starNeed() ? starsHtml(starCount(), starNeed()) : '';
    $('quizQ').textContent = q.q;
    var mine = myAnswer(q.id);
    if (mine && S.quizPending && S.quizPending.id === q.id) S.quizPending = null;
    var waiting = !mine && S.quizPending && S.quizPending.id === q.id ? S.quizPending.i : null;
    // 다시 들어온 경우 정답 번호는 모를 수 있음 (맞혔으면 고른 것이 정답)
    var correctIdx = mine ? (typeof mine.answer === 'number' ? mine.answer : (mine.correct ? mine.choice : null)) : null;
    // 보기 글자는 "1. 별" (자동 시험과 녹화 도구가 이 글자로 찾음)
    $('quizChoices').innerHTML = q.choices.map(function (c, i) {
      var cls = mine ? (i === correctIdx ? 'right' : i === mine.choice ? 'wrong' : '') : (i === waiting ? 'picked' : '');
      return '<button type="button" data-i="' + i + '" class="' + cls + '"' + (mine || waiting != null ? ' disabled' : '') + '><span class="qn">' + (i + 1) + '.</span> ' + esc(c) + icon('check', 'qi ok') + icon('close', 'qi no') + '</button>';
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
    $('quizResult').className = 'result' + (mine && mine.correct ? ' ok' : '');
    $('quizResult').innerHTML = res ? (mine && mine.correct ? icon('check') : '') + '<span>' + esc(res) + '</span>' : '';
  }

  function answerQuiz(q, i) {
    if (myAnswer(q.id) || !S.ws || S.ws.readyState !== 1) return;
    S.ws.send(JSON.stringify({ type: 'quiz_answer', id: q.id, choice: i, by: me || (lang() === 'en' ? 'Passenger' : '동승자') }));
    S.quizPending = { id: q.id, i: i };
    renderQuiz();
    // 답이 오지 않으면(그사이 연결이 끊김 등) 8초 뒤 다시 고를 수 있게
    setTimeout(function () { if (S.quizPending && S.quizPending.id === q.id && !myAnswer(q.id)) { S.quizPending = null; renderQuiz(); } }, 8000);
  }

  function renderTreasure() {
    var card = $('treasureCard'), tr = S.treasure, st = S.stars;
    if (tr) {
      card.classList.remove('hidden'); card.classList.add('open');
      $('treasureStars').innerHTML = starsHtml(tr.stars, tr.stars);
      $('treasureTitle').textContent = t('treasureOpen');
      $('treasureText').textContent = tr.unlocked ? t('treasureOpenText') : t('treasureHand');
    } else if (st && st.ready) {
      card.classList.remove('hidden'); card.classList.remove('open');
      $('treasureStars').innerHTML = starsHtml(st.stars, st.need);
      $('treasureTitle').textContent = t('treasureReady');
      $('treasureText').textContent = t('treasureReadyText');
    } else card.classList.add('hidden');
  }

  // ---------------------------------------------------------------- 차에 물어본 것
  function renderQa() {
    var card = $('qaCard'), list = S.qa.filter(function (x) { return mineCar(x); });
    if (!list.length) { card.classList.add('hidden'); return; }
    card.classList.remove('hidden');
    $('qaList').innerHTML = list.slice(-3).reverse().map(function (x) {
      return '<li><div class="t">' + hhmm(x.at) + (x.title ? ' · ' + esc(x.title) : '') + '</div>' + (x.q ? '<div class="q">“' + esc(x.q) + '”</div>' : '') + '<div class="a">' + esc(x.a) + '</div></li>';
    }).join('');
  }

  // ---------------------------------------------------------------- 장소 고르기
  var ACCENT = '#00AAD2';
  var pickMap, pickLayer, pickFitFor = null;
  function tiles(map) {
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19, attribution: '<a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">© OpenStreetMap</a>' }).addTo(map);
    if (map.attributionControl) map.attributionControl.setPrefix('Leaflet');
  }
  // 보이는 점은 26~32px, 손가락으로 누르는 영역은 44px. 이름표는 붙이지 않음 (가까운 명소끼리 가려짐. 고른 곳 이름은 아래 보내기 줄에)
  function markIcon(inner) { return L.divIcon({ className: 'mk-wrap', iconSize: [44, 44], iconAnchor: [22, 22], html: '<div class="mk">' + inner + '</div>' }); }
  function stopIcon(no, state) { return markIcon('<span class="mk-stop ' + state + '">' + no + '</span>'); }
  function pickIcon(kind, sel) { return markIcon('<span class="mk-pick' + (sel ? ' sel' : '') + '">' + icon(KIND_IC[kind] || 'pin') + '</span>'); }
  // 지나온 길: 마지막으로 지난 코스 명소와 가장 가까운 경로 점까지
  function traveledIndex(route) {
    var passed = passedMap(), best = -1;
    courseStops().forEach(function (p) {
      if (!passed[p.id]) return;
      var bi = 0, bd = Infinity;
      route.forEach(function (r, i) { var d = (r[0] - p.lat) * (r[0] - p.lat) + (r[1] - p.lon) * (r[1] - p.lon); if (d < bd) { bd = d; bi = i; } });
      if (bi > best) best = bi;
    });
    return best;
  }
  // 길: 어두운 테두리 위에 지난 데(cut 번째 점)까지는 파랑, 남은 길은 회색
  function routeLines(layer, pts, cut) {
    L.polyline(pts, { color: '#05070A', weight: 10, opacity: 0.55, interactive: false }).addTo(layer);
    if (cut < pts.length - 1) L.polyline(cut > 0 ? pts.slice(cut) : pts, { color: '#9AA0AA', weight: 5, opacity: 0.9, interactive: false }).addTo(layer);
    if (cut > 0) L.polyline(pts.slice(0, cut + 1), { color: ACCENT, weight: 6, opacity: 1, interactive: false }).addTo(layer);
  }

  function drawPickMap() {
    var sc = S.scenario; if (!sc || !window.L || S.tab !== 'pick') return;
    if (!pickMap) {
      pickMap = L.map('pickMap', { zoomControl: false, attributionControl: true });
      tiles(pickMap);
      pickLayer = L.layerGroup().addTo(pickMap);
      // 지도를 누르면 그곳을 고름. 바로 보내지 않고, 아래 보내기 단추를 눌러야 감
      pickMap.on('click', function (e) {
        var ll = e.latlng.wrap();   // 지도를 옆으로 여러 바퀴 돌려도 경도가 -180~180 안에 들게
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
      pickMap.fitBounds(L.latLngBounds(pts), { padding: [28, 28] });
    }
  }
  function drawPickMarkers() {
    var sc = S.scenario; if (!pickMap || !sc) return;
    pickLayer.clearLayers();
    var pts = sc.route.map(function (p) { return [p[0], p[1]]; });
    routeLines(pickLayer, pts, traveledIndex(pts));
    var passed = passedMap(), nx = nextStop(), sel = S.selected && S.selected.id, nowId = S.story && !S.storyDone && S.story.kind !== 'announce' && !S.ended ? S.story.placeId : null;
    courseStops().forEach(function (p, i) {
      var state = p.id === nowId ? 'now' : passed[p.id] ? 'done' : nx && nx.id === p.id ? 'next' : 'todo';
      L.marker([p.lat, p.lon], { icon: stopIcon(i + 1, state + (sel === p.id ? ' sel' : '')), title: nm(p.name), alt: nm(p.name), zIndexOffset: sel === p.id ? 500 : 0 })
        .addTo(pickLayer).on('click', function () { select(p); });
    });
    (sc.pickables || []).forEach(function (p) {
      L.marker([p.lat, p.lon], { icon: pickIcon(p.kind, sel === p.id), title: nm(p.name), alt: nm(p.name), zIndexOffset: sel === p.id ? 500 : 100 })
        .addTo(pickLayer).on('click', function () { select(p); });
    });
    if (sel === 'custom') L.marker([S.selected.lat, S.selected.lon], { icon: pickIcon('place', true), title: t('custom'), alt: t('custom'), zIndexOffset: 600, interactive: false, keyboard: false }).addTo(pickLayer);
  }

  function select(p) {
    if (!p) return;
    S.selected = { id: p.id, name: nm(p.name), lat: p.lat, lon: p.lon, kind: p.kind };
    clearPickMsg();
    renderPickList();
    if (pickMap && S.tab === 'pick') { drawPickMarkers(); pickMap.panTo([p.lat, p.lon], { animate: true }); }
  }

  // 목록에는 가 볼 만한 곳(pickables)만 둠. 코스 명소는 이미 내비게이션에 들어 있고, 지도의 번호 점을 눌러 고를 수는 있음
  function renderPickList() {
    var sc = S.scenario; if (!sc) return;
    var sel = S.selected && S.selected.id, kinds = t('kind');
    $('picklist').innerHTML = (sc.pickables || []).map(function (p) {
      var on = sel === p.id;
      return '<button type="button" class="pick-row' + (on ? ' sel' : '') + '" data-id="' + esc(p.id) + '" aria-pressed="' + on + '"><span class="pick-ic">' + icon(KIND_IC[p.kind] || 'pin') + '</span>' +
        '<span class="pick-txt"><b>' + esc(nm(p.name)) + '</b>' + (kinds[p.kind] ? '<small>' + esc(kinds[p.kind]) + '</small>' : '') + '</span><span class="pick-check">' + icon('check') + '</span></button>';
    }).join('');
    Array.prototype.forEach.call($('picklist').querySelectorAll('button[data-id]'), function (b) { b.onclick = function () { select(placeById(b.dataset.id)); }; });
    renderPickButton();
  }

  // 보내기 단추 잠금: 서버 답을 기다리는 동안, 그룹에 운전자 확인을 기다리는 곳이 있을 때(90초까지), 보낸 뒤 20초
  function pickLocked() {
    var now = Date.now();
    return (S.pickWait && now - S.pickWait.at < 8000) || (S.pending && now - S.pending.at < 90000) || now < (S.pickSoonUntil || 0);
  }
  // 보내기 줄 위의 한 줄: 운전자 답(보냈어요, 들어갔어요, 못 보냄) > 다른 사람이 보낸 곳 확인 중 > 고른 곳 > 고르라는 안내
  function setPickMsg(text, kind) { S.pickMsg = text ? { text: text, kind: kind || 'wait' } : null; renderPickInfo(); }
  function clearPickMsg() { if (S.pickMsg && S.pickMsg.kind !== 'wait') S.pickMsg = null; }
  function renderPickInfo() {
    var el = $('pickStatus'), m = S.pickMsg, kind, text, ic;
    var busy = S.pending && Date.now() - S.pending.at < 90000;
    // 기다리는 글(보냈어요, 확인 중)은 기다림이 끝나면 거둠: 운전자 답 없이 90초, 서버 답 없이 8초가 지났을 때
    if (m && m.kind === 'wait' && !busy && !(S.pickWait && Date.now() - S.pickWait.at < 8000)) m = S.pickMsg = null;
    if (m) { kind = m.kind; text = m.text; ic = { ok: 'check', warn: 'alert', info: 'info' }[kind] || 'clock'; }
    else if (busy && !(S.myPicks && S.myPicks[S.pending.id])) { kind = 'wait'; text = f('pickBusy', { name: S.pending.name || '' }); ic = 'clock'; }
    else if (S.group && !S.cars.length) { kind = 'hint'; text = t('pickNoCarYet'); ic = 'car'; }
    else if (S.selected) { kind = 'sel'; text = S.selected.name + (S.selected.id !== 'custom' && t('kind')[S.selected.kind] ? ' · ' + t('kind')[S.selected.kind] : ''); ic = S.selected.id === 'custom' ? 'pin' : (KIND_IC[S.selected.kind] || 'pin'); }
    else { kind = 'hint'; text = t('pickHint'); ic = 'info'; }
    el.className = 'pick-info ' + kind;
    el.innerHTML = icon(ic) + '<span>' + esc(text) + '</span>';
    // 고른 곳이나 알릴 답이 있을 때만 보내기 줄을 탭 바로 위에 띄워 둠. 아무것도 안 골랐으면 목록 끝에 그대로
    $('sendbar').classList.toggle('armed', kind !== 'hint' || !!S.selected);
  }
  function renderPickButton() {
    var btn = $('sendPick');
    btn.disabled = !S.selected || !S.online || !S.cars.length || pickLocked();
    // 보낸 뒤 20초 동안은 단추에 남은 초를 보여 줌 (위 줄에는 운전자 답을 그대로 둠)
    var left = Math.ceil(((S.pickSoonUntil || 0) - Date.now()) / 1000);
    $('sendPickL').textContent = left > 0 && !(S.pending && Date.now() - S.pending.at < 90000) ? f('pickSoon', { s: left }) : (S.cars.length > 1 ? t('send2') : t('send1'));
    renderPickInfo();
  }
  setInterval(function () { if (S.tab === 'pick') renderPickButton(); }, 1000);
  // 휴대폰은 장소 id 만 보냄(이름과 위치는 서버가 시나리오에서 채움). 지도에서 찍은 곳은 좌표만
  function sendPick() {
    if (!S.selected || !S.ws || S.ws.readyState !== 1 || pickLocked()) return;
    var by = me || (lang() === 'en' ? 'Passenger' : '동승자');
    S.ws.send(JSON.stringify(S.selected.id === 'custom' ? { type: 'pick', custom: true, lat: S.selected.lat, lon: S.selected.lon, by: by } : { type: 'pick', placeId: S.selected.id, by: by }));
    S.pickWait = { at: Date.now() };
    setPickMsg(t('sent'), 'wait');
    renderPickButton();
  }
  $('sendPick').onclick = sendPick;
  function pickName(id, fallback) {
    var p = S.picks.filter(function (x) { return x.id === id; })[0];
    var pl = p && p.placeId && placeById(p.placeId);
    return pl ? nm(pl.name) : (p && p.custom ? t('custom') : (fallback || (p && p.name) || ''));
  }

  // ---------------------------------------------------------------- 기록
  var recMap, recLayer, recFitFor = null;
  function routeFromRecord() {
    if (S.record && S.record.route && S.record.route.length > 1) return S.record.route.map(function (r) { return { t: r[0], lat: r[1], lon: r[2] }; });
    return null;
  }

  function positionAt(time) {
    var r = routeFromRecord();
    if (r) {
      var best = r[0];
      r.forEach(function (p) { if (Math.abs(p.t - time) < Math.abs(best.t - time)) best = p; });
      return [best.lat, best.lon];
    }
    var pass = null;
    S.passes.forEach(function (p) { if (!pass || Math.abs(p.at - time) < Math.abs(pass.at - time)) pass = p; });
    var pl = pass && placeById(pass.placeId);
    return pl ? [pl.lat, pl.lon] : null;
  }

  function stat(ic, val, label) { return '<div class="stat">' + icon(ic) + '<b>' + val + '</b><span>' + esc(label) + '</span></div>'; }
  function unit(n, u) { return n + (u ? '<small>' + esc(u) + '</small>' : ''); }
  function renderRecHero() {
    var sc = S.scenario, rec = S.record; if (!sc) return;
    var passed = passedMap();
    $('recEyebrow').textContent = rec ? t('recEyebrow') : t('recPlanned');
    $('recTitle').textContent = nm(sc.title);
    if (rec) {
      var mins = rec.startedAt && rec.endedAt > rec.startedAt ? Math.max(1, Math.round((rec.endedAt - rec.startedAt) / 60000)) : 0, h = Math.floor(mins / 60), m = mins % 60;
      var time = !mins ? '–' : h ? unit(h, t('unitH')) + (m ? ' ' + unit(m, t('unitMin')) : '') : unit(m, t('unitMin'));
      var n = courseStops().filter(function (p) { return passed[p.id]; }).length;
      $('recSummary').textContent = rec.startedAt ? f('recRange', { a: hhmm(rec.startedAt), b: hhmm(rec.endedAt) }) : '';
      $('recStats').innerHTML = stat('route', unit((rec.distanceM / 1000).toFixed(1), 'km'), t('statKm')) + stat('clock', time, t('statTime')) + stat('pin', unit(n, t('unitPlaces')), t('statPlaces'));
      $('recStats').classList.remove('hidden');
    } else {
      $('recSummary').textContent = full() ? t('recEmpty') : '';
      $('recStats').classList.add('hidden');
    }
  }

  function drawRecord() {
    var sc = S.scenario; if (!sc) return;
    renderRecHero(); renderPhotos();
    if (!window.L || S.tab !== 'record') return;
    if (!recMap) { recMap = L.map('recMap', { zoomControl: false }); tiles(recMap); recLayer = L.layerGroup().addTo(recMap); }
    recLayer.clearLayers();
    var r = routeFromRecord();
    var pts = r ? r.map(function (p) { return [p.lat, p.lon]; }) : sc.route.map(function (p) { return [p[0], p[1]]; });
    // 끝난 여행은 지나온 길 전체를 파랑으로, 끝나기 전에는 오늘 갈 길(지난 데까지 파랑, 남은 길은 회색)
    routeLines(recLayer, pts, r ? pts.length - 1 : traveledIndex(pts));
    var passed = passedMap();
    courseStops().forEach(function (p, i) {
      L.marker([p.lat, p.lon], { icon: stopIcon(i + 1, passed[p.id] ? 'done' : 'todo'), interactive: false, keyboard: false }).addTo(recLayer);
    });
    S.photos.forEach(function (ph) {
      var pos = positionAt(ph.takenAt); if (!pos) return;
      L.marker(pos, { icon: L.divIcon({ className: 'mk-wrap', iconSize: [44, 44], iconAnchor: [22, 22], html: '<img class="mk-photo" src="' + esc(withKey(ph.url)) + '" alt="">' }), interactive: false, keyboard: false, zIndexOffset: 400 }).addTo(recLayer);
    });
    recMap.invalidateSize();
    var fitKey = sc.id + (r ? ':' + r.length : '');
    // 길 전체가 보이게 맞춤 (여행 중에 열어도 지나온 길과 지난 명소가 화면 안에 들어오게)
    if (recFitFor !== fitKey) { recFitFor = fitKey; recMap.fitBounds(L.latLngBounds(pts), { padding: [24, 24] }); }
  }

  function nearestPlaceName(time) {
    var pass = null;
    S.passes.forEach(function (p) { if (!pass || Math.abs(p.at - time) < Math.abs(pass.at - time)) pass = p; });
    var pl = pass && placeById(pass.placeId);
    return pl ? nm(pl.name) : '';
  }

  function renderPhotos() {
    $('photoCount').textContent = S.photos.length ? f('photoCount', { n: S.photos.length }) : '';
    $('photos').innerHTML = S.photos.map(function (p) {
      return '<figure><img src="' + esc(withKey(p.url)) + '" loading="lazy" alt=""><figcaption>' + hhmm(p.takenAt) + ' ' + esc(nearestPlaceName(p.takenAt)) + '</figcaption></figure>';
    }).join('');
  }

  // EXIF 촬영 시각(DateTimeOriginal) 읽기. 없으면 파일 수정 시각
  function exifTime(file) {
    return new Promise(function (resolve) {
      if (!/jpe?g$/i.test(file.type.split('/')[1] || '')) return resolve(null);
      var fr = new FileReader();
      fr.onload = function () {
        try {
          var v = new DataView(fr.result);
          if (v.getUint16(0) !== 0xFFD8) return resolve(null);
          var off = 2;
          while (off < v.byteLength) {
            var marker = v.getUint16(off);
            if (marker === 0xFFE1) {
              var start = off + 10, le = v.getUint16(start) === 0x4949;
              var u16 = function (o) { return v.getUint16(start + o, le); }, u32 = function (o) { return v.getUint32(start + o, le); };
              var ifd = u32(4), exifPtr = 0;
              for (var i = 0, n = u16(ifd); i < n; i++) { var e = ifd + 2 + i * 12; if (u16(e) === 0x8769) exifPtr = u32(e + 8); }
              if (!exifPtr) return resolve(null);
              for (var j = 0, m = u16(exifPtr); j < m; j++) {
                var e2 = exifPtr + 2 + j * 12;
                if (u16(e2) === 0x9003) {
                  var p = u32(e2 + 8), s = '';
                  for (var k = 0; k < 19; k++) s += String.fromCharCode(v.getUint8(start + p + k));
                  var mm = /(\d{4}):(\d{2}):(\d{2}) (\d{2}):(\d{2}):(\d{2})/.exec(s);
                  return resolve(mm ? new Date(+mm[1], +mm[2] - 1, +mm[3], +mm[4], +mm[5], +mm[6]).getTime() : null);
                }
              }
              return resolve(null);
            }
            off += 2 + v.getUint16(off + 2);
          }
          resolve(null);
        } catch (err) { resolve(null); }
      };
      fr.onerror = function () { resolve(null); };
      fr.readAsArrayBuffer(file.slice(0, 256 * 1024));
    });
  }

  function shrink(file) {
    return new Promise(function (resolve) {
      var img = new Image(), url = URL.createObjectURL(file);
      img.onload = function () {
        var max = 1600, w = img.naturalWidth, h = img.naturalHeight, k = Math.min(1, max / Math.max(w, h));
        var c = document.createElement('canvas'); c.width = Math.round(w * k); c.height = Math.round(h * k);
        c.getContext('2d').drawImage(img, 0, 0, c.width, c.height);
        URL.revokeObjectURL(url); resolve(c.toDataURL('image/jpeg', 0.85));
      };
      img.onerror = function () { resolve(null); };
      img.src = url;
    });
  }

  // 단추처럼 보이는 label 은 키보드(Enter, Space)로도 누를 수 있게
  $('photoBtn').onkeydown = function (e) { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); $('photoBtn').click(); } };

  // 캔버스로 다시 그려 올리므로 사진의 EXIF(GPS 위치, 기기 정보)는 빠짐. 촬영 시각만 따로 읽어 보냄
  $('photoInput').onchange = function () {
    var files = Array.prototype.slice.call(this.files || []);
    this.value = '';
    var fails = 0, st = $('photoStatus');
    st.classList.remove('warn');
    files.reduce(function (p, file, i) {
      return p.then(function () {
        st.textContent = t('uploading') + ' ' + (i + 1) + '/' + files.length;
        return Promise.all([exifTime(file), shrink(file)]).then(function (r) {
          if (!r[1]) { fails++; return; }
          return fetch('/api/groups/' + code + '/photos', { method: 'POST', headers: keyHeaders({ 'Content-Type': 'application/json' }), body: JSON.stringify({ dataUrl: r[1], takenAt: r[0] || file.lastModified, by: me }) })
            .then(function (res) { if (!res.ok) fails++; }, function () { fails++; });
        });
      });
    }, Promise.resolve()).then(function () {
      st.textContent = fails ? t('photoFail') + ' (' + fails + ')' : '';
      st.classList.toggle('warn', fails > 0);
    });
  };

  // ---------------------------------------------------------------- 연결
  function apply(summary) {
    S.group = summary;
    S.quizPending = null;   // 다시 붙으면 서버가 받은 답만 믿음 (못 받았으면 다시 고를 수 있음)
    if (summary.access) S.level = summary.access;
    S.cars = summary.cars || S.cars;
    S.passes = summary.passes || [];
    S.picks = summary.picks || S.picks;
    S.acks = summary.acks || S.acks;
    S.record = summary.record || null;
    S.ended = !!summary.ended;
    S.photos = summary.photos || [];
    S.quizzes = summary.quizzes || [];
    S.answers = summary.answers || [];
    S.scores = summary.scores || {};
    S.qa = summary.qa || [];
    S.treasure = summary.treasure && mineCar(summary.treasure) ? summary.treasure : null;
    S.stars = summary.stars && mineCar(summary.stars) ? summary.stars : null;
    // 다시 연결하면 지금 하는 이야기를 다시 그림 (끊긴 사이에 바뀌었거나 끝났을 수 있음)
    var st = myStory(summary);
    if (st && (!S.story || S.story.placeId !== st.placeId || S.story.at !== st.at)) showStory(st, true);
    if (st && S.story) {
      var ln = (summary.lines || []).concat(summary.line ? [summary.line] : []).filter(function (l) { return l && l.placeId === st.placeId && sameCar(l, st); })[0];
      if (ln && !st.done) S.lineIdx = ln.index;
      S.storyDone = !!st.done;
    }
  }
  function sameCar(a, b) { return !a.carNo || !b.carNo || a.carNo === b.carNo; }
  // 이 휴대폰이 따르는 차의 마지막 이야기
  function myStory(summary) {
    var list = (summary.stories || []).filter(function (s) { return s && mineCar(s); });
    if (!list.length && summary.story && mineCar(summary.story)) list = [summary.story];
    return list.sort(function (a, b) { return (b.at || 0) - (a.at || 0); })[0] || null;
  }

  function loadScenario(id) {
    return fetch('/api/scenarios/' + id).then(function (r) { if (!r.ok) throw new Error('scenario'); return r.json(); }).then(function (sc) {
      S.scenario = sc;
      pickFitFor = null; recFitFor = null;
      renderAll();
    }).catch(function () { setBanner(t('reconnecting')); });
  }

  function renderAll() {
    staticTexts(); renderTop(); renderNow(); renderQuiz(); renderTreasure(); renderQa(); renderCourse(); renderPickList(); renderBadges();
    if (S.tab === 'pick') drawPickMap();
    if (S.tab === 'record') drawRecord();
  }

  // ---------------------------------------------------------------- 연결 (끊기면 1초부터 15초까지 늘려 가며 다시 붙음)
  var ws = null, retryMs = 1000, retryTimer = null, retryIn = 0, stopped = false, hiddenAt = 0;
  function wsUrl() {
    return (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws?code=' + code + '&role=phone' +
      (key ? '&k=' + encodeURIComponent(key) : '') + (pid ? '&pid=' + encodeURIComponent(pid) : '');
  }
  function setBanner(msg) {
    var b = $('banner');
    b.classList.toggle('hidden', !msg);
    b.innerHTML = msg ? '<span class="spin" aria-hidden="true"></span><span>' + esc(msg) + '</span>' : '';
  }
  function fatal(msgKey) {
    stopped = true;
    S.fatal = msgKey;
    setBanner(null);
    if (sheetMode) closeSheet();
    renderTop(); renderNow(); renderCourse();
  }
  function lost() {
    S.online = false; renderTop(); renderPickButton();
  }
  function connect() {
    clearTimeout(retryTimer);
    if (stopped || (ws && ws.readyState <= 1)) return;
    // 휴대폰이 오프라인이라고 알려 주면 헛되이 붙지 않고 'online' 을 기다림 (아래 online 처리에서 바로 다시 붙음)
    if (navigator.onLine === false) { setBanner(t('reconnecting')); return; }
    var sock = ws = S.ws = new WebSocket(wsUrl());
    sock.onopen = function () { if (sock !== ws) return; S.online = true; retryMs = 1000; setBanner(null); renderTop(); renderPickButton(); };
    sock.onclose = function (e) {
      if (sock !== ws) return;   // 일부러 바꾼 예전 연결
      lost();
      if (e.code === 4404) return fatal('noGroup');
      if (e.code === 4401) {
        // 이 휴대폰에 남은 예전 키(같은 번호의 지난 여행)면 버리고 번호로 다시 붙음. QR 주소의 키가 틀리면 멈춤
        if (!key || urlKey) return fatal('badKey');
        key = ''; store('gg_k_' + code, null); S.level = 'basic'; staticTexts();
      }
      var wait = retryMs;
      if (e.code === 4429) { wait = Math.max(wait, (retryIn || 30) * 1000); setBanner(f('tooMany', { s: Math.round(wait / 1000) })); }
      else setBanner(t('reconnecting'));
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
          if (first) maybeIntro();
          break;
        case 'error': if (m.retryIn) retryIn = m.retryIn; break;
        case 'members': S.cars = m.cars || []; S.phones = m.phones; renderTop(); renderNow(); renderPickButton(); break;
        case 'trip': if (S.group) { S.group.lang = m.lang; } if (!S.scenario || S.scenario.id !== m.scenarioId) loadScenario(m.scenarioId); else renderAll(); break;
        // 차가 둘이면 QR 의 car=N 차 이야기만 따름
        case 'story': if (mineCar(m)) showStory(m); break;
        case 'line': if (S.story && m.placeId === S.story.placeId && sameCar(m, S.story)) { S.lineIdx = m.index; renderLines(); } break;
        case 'story_end': if (S.story && m.placeId === S.story.placeId && sameCar(m, S.story)) { S.storyDone = true; S.story.done = true; renderNow(); renderCourse(); } break;
        case 'pass':
          S.passes.push({ placeId: m.placeId, name: m.name, at: m.at, car: m.car, carNo: m.carNo });
          renderCourse(); renderNow();
          if (S.tab === 'pick') drawPickMarkers();
          if (S.tab === 'record') drawRecord(); else if (S.scenario) renderRecHero();
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
          S.acks.push(m);
          if (S.pending && S.pending.id === m.id) S.pending = null;
          var ackText = m.accepted === false ? t('pickLater') : t('acked') + ': ' + pickName(m.id, m.name);
          var minePick = !!(S.myPicks && S.myPicks[m.id]);
          if (minePick) setPickMsg(ackText, m.accepted === false ? 'info' : 'ok');
          else if (S.pickMsg && S.pickMsg.kind === 'wait') setPickMsg(null);
          // 장소 고르기 화면에서 내가 보낸 곳의 답은 보내기 줄에 이미 보이므로 알림을 겹쳐 띄우지 않음
          if (!(minePick && S.tab === 'pick')) toast((S.cars.length > 1 ? carLabel(m.carNo, m.car) + ' ' : '') + ackText);
          renderPickButton();
          break;
        case 'window': if (mineCar(m)) toast(t('window')); break;
        case 'record': S.record = m.record; if (S.tab === 'record') drawRecord(); else renderRecHero(); break;
        case 'photo': S.photos.push(m.photo); S.photos.sort(function (a, b) { return a.takenAt - b.takenAt; }); if (S.tab === 'record') drawRecord(); else renderPhotos(); break;
        case 'trip_end':
          S.ended = true; renderTop(); renderNow(); renderCourse();
          toast(t('tripEnd'), S.tab === 'record' ? null : function () { setTab('record'); }, t('seeRecord'));
          break;
        case 'quiz':
          S.quizzes = S.quizzes.filter(function (q) { return q.id !== m.id; }).concat([m]);
          if (!mineCar(m)) break;
          if (m.need) S.stars = Object.assign({ stars: 0, ready: false }, S.stars || {}, { need: m.need }, typeof m.stars === 'number' && (!S.stars || m.stars > S.stars.stars) ? { stars: m.stars } : {});
          // 탭을 억지로 바꾸지 않음. 눌러서 퀴즈로 갈 수 있는 알림만
          renderQuiz();
          toast(t('newQuiz'), function () { focusCard('quizCard'); });
          nowNews();
          if (navigator.vibrate) navigator.vibrate([60, 60, 60]);
          break;
        case 'quiz_answer':
          S.answers.push(m); S.scores = m.scores || S.scores; renderQuiz();
          break;
        case 'stars': if (mineCar(m)) { S.stars = m; renderQuiz(); renderTreasure(); } break;
        case 'treasure':
          if (!mineCar(m)) break;
          S.treasure = m; renderTreasure();
          toast(t('treasureOpen'), function () { focusCard('treasureCard'); });
          nowNews();
          if (navigator.vibrate) navigator.vibrate([120, 80, 120, 80, 300]);
          break;
        case 'qa': S.qa.push(m); renderQa(); if (m.q && mineCar(m)) toast(f('asked', { q: m.q })); break;
        case 'kid': if (mineCar(m)) { S.kid = m; renderKid(); } break;
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
    if (S.tab === 'now') playClip();
  });
  window.addEventListener('online', function () { if (!ws || ws.readyState > 1) resync(); });
  // 와이파이가 끊기면 소켓이 닫히기를 기다리지 않고 바로 알림 띠를 띄우고 다시 붙기 시작함
  window.addEventListener('offline', function () {
    if (stopped) return;
    var old = ws; ws = null;
    if (old) { try { old.close(); } catch (e) {} }
    lost();
    setBanner(t('reconnecting'));
    clearTimeout(retryTimer);
    retryTimer = setTimeout(connect, retryMs);
  });

  staticTexts();
  renderTop();
  renderNow();
  setTab(startTab);
  if (!code) { location.href = '/'; return; }
  // 그룹 정보는 WebSocket 의 hello 로 받음 (번호 조회를 한 번만 셈)
  connect();
})();
