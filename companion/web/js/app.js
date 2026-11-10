/* 구르는 가이드 동승자 그룹 페이지
 * 앱 설치 없이 QR로 들어와, 차가 이야기를 시작하면 같은 순간 영상과 3D 모형을 보여 준다.
 * 지도에서 고른 장소는 그룹의 모든 차로 보내고, 여행이 끝나면 지나온 길과 사진, 하이라이트 영상을 모은다.
 * 차가 내는 퀴즈를 풀어 별을 모으고(별을 다 모으면 차가 글로브 박스 보물 상자를 열어 줌),
 * 차에 말로 물어본 것과 답을 같이 보고, 차를 세우면 하던 이야기를 휴대폰에서 이어 듣는다.
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
  // QR 의 k(휴대폰 키)가 있으면 사진·기록·하이라이트까지 봄. 한 번 받은 키는 이 휴대폰에 두어 번호로 다시 들어와도 이어 씀
  var urlKey = qs.get('k') || '';
  var key = urlKey || store('gg_k_' + code) || '';
  if (urlKey && code) store('gg_k_' + code, urlKey);
  var S = { lang: store('gg_lang'), group: null, scenario: null, story: null, lines: [], lineIdx: -1, storyDone: false, passes: [], picks: [], acks: [], record: null, photos: [], highlight: null, cars: [], phones: 0, selected: null, online: false, tab: startTab,
    quizzes: [], answers: [], scores: {}, qa: [], handoff: null, treasure: null, stars: null, kid: null, level: key ? 'full' : 'basic', pending: null, pickWait: null };
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
      now: '지금', course: '코스', pick: '장소 고르기', record: '기록',
      telling: '차가 들려주는 이야기', coming: '곧 보여요', waiting: '명소에 가까워지면 여기에 영상과 3D 모형이 떠요',
      video: '영상', orbit: '3D 돌려 보기', report: '틀린 내용 신고', me: '내 이름 (장소를 고르면 차 화면에 같이 떠요)', mePh: '예: 민지',
      courseTitle: '오늘 코스', passed: '지남', nowAt: '지금', next: '다음',
      pickTitle: '다음에 갈 곳 고르기', pickHelp: '목록에서 고르거나 지도를 눌러 원하는 곳을 찍으세요. 차가 운전자에게 물어보고, 운전자가 "응" 하거나 확인을 누르면 내비게이션에 들어가요.',
      send1: '차 내비게이션으로 보내기', send2: '그룹 차 모두에 보내기', custom: '지도에서 고른 곳', sent: '보냈어요. 운전자 확인을 기다리는 중', acked: '내비게이션에 들어갔어요',
      recTitle: '지나온 길', photoTitle: '사진 붙이기', photoHelp: '사진을 올리면 찍은 시각에 맞춰 지나온 길 위에 붙어요.', photoBtn: '사진 올리기',
      camTitle: '빌트인 캠 하이라이트', camHelp: '차의 빌트인 캠(순정 블랙박스) 영상을 휴대폰으로 받아 올리면, 명소를 지난 장면만 골라 짧은 여행 영상으로 만들어요.', camBtn: '영상 올리기', making: '영상을 만드는 중이에요', made: '여행 영상이 나왔어요', down: '영상 내려받기', camFail: '영상을 만들지 못했어요',
      byOrder: '촬영 시각이 기록과 맞지 않아 지나온 순서대로 나눴어요', uploading: '올리는 중', joined: '여행 그룹에 들어왔어요', tripEnd: '오늘 여행이 끝났어요. 기록 탭에서 모아 볼 수 있어요',
      window: '창문이 조금 내려갔어요. 바깥 소리를 들어 보세요', reportAsk: '어떤 점이 틀렸나요?', reported: '신고했어요. 고맙습니다',
      sentFrom: '에서 보낸 장소', summary: '{km}km를 달려 명소 {n}곳을 지났어요', noRecord: '여행이 끝나면 지나온 길이 여기에 그려져요',
      kind: { cafe: '카페', parking: '주차장', landmark: '명소', sea: '바다', city: '도시', forest: '숲', place: '장소' }, cars: '대', phones: '명',
      quiz: '{name} 퀴즈', newQuiz: '새 퀴즈가 왔어요', right: '정답이에요! 별 하나 모았어요', wrong: '아쉬워요. 정답은 "{a}"', gotIt: '{by} 님이 맞혔어요',
      treasureOpen: '보물 상자가 열렸어요!', treasureOpenText: '조수석 앞 글로브 박스를 열어 보세요', treasureHand: '글로브 박스를 직접 열어 보세요',
      treasureReady: '별을 다 모았어요!', treasureReadyText: '차를 세우면 보물 상자를 열어 줘요',
      qaTitle: '차에 물어본 것', asked: '차에 물어봤어요: ', handoffTitle: '{name} 이야기, 휴대폰에서 이어 듣기', handoffPlay: '이어 듣기', handoffStop: '그만 듣기', handoffNew: '차에서 듣던 이야기를 휴대폰에서 이어 들을 수 있어요',
      kidTitle: '뒷자리 친구들에게',
      introTitle: '여기서 할 수 있는 것', introOk: '알겠어요', introName: '아래에 이름을 적어 두면 장소를 고를 때 차 화면에 같이 떠요',
      intro: ['차가 명소 이야기를 시작하면 여기에 영상과 3D 모형이 같이 떠요', '퀴즈가 오면 맞혀서 별을 모아요. 다 모으면 보물 상자가 열려요', '장소 고르기 탭에서 다음에 갈 곳을 차로 보내요', '여행이 끝나면 기록 탭에서 지나온 길과 사진을 모아 봐요'],
      basicNote: '번호로 들어와서 이야기·퀴즈·장소 고르기만 쓸 수 있어요. 사진과 기록은 차 화면의 QR을 찍고 들어오면 보여요.',
      keep: '이 그룹 사람만 보고, 여행이 끝나고 {d}일 뒤 지워져요', keepOff: '이 그룹 사람만 봐요',
      reconnecting: '연결이 끊겼어요. 다시 연결하는 중…', noGroup: '그룹을 찾지 못했어요. 끝난 지 오래된 여행이거나 번호가 달라요.', badKey: 'QR 주소가 맞지 않아요. 차 화면의 QR을 다시 찍어 주세요.', again: '번호 다시 넣기', tooMany: '잠시 뒤에 다시 연결해요 ({s}초)',
      pickLater: '운전자가 나중에 보기로 했어요. 차가 서면 다시 보여요', pickSoon: '{s}초 뒤에 다시 보낼 수 있어요', pickBusy: '운전자가 "{name}" 확인 중이에요. 답이 오면 보내 주세요', pickNoCar: '지금 연결된 차가 없어요', pickBad: '보낼 수 없는 곳이에요. 코스 근처를 골라 주세요', pickOther: '{by} 님이 고른 곳: {name}', confirmCustom: '여기로 보낼까요?', sendHere: '여기로 보내기',
      camErr: { 403: 'QR로 들어온 휴대폰만 올릴 수 있어요', 409: '다른 영상을 만드는 중이에요. 끝나면 다시 올려 주세요', 413: '영상이 너무 커요. {mb}MB보다 작은 영상을 올려 주세요', 415: 'MP4나 MOV 영상만 올릴 수 있어요', net: '올리지 못했어요. 와이파이를 확인하고 다시 올려 주세요' },
      retry: '다시 올리기', photoFail: '사진을 올리지 못했어요', reportFail: '신고를 보내지 못했어요. 잠시 뒤에 다시 해 주세요',
      orbitHint: '한 손가락으로 끌어 돌리고, 두 손가락으로 벌려 크게 봐요', tapPlay: '▶ 눌러서 영상 보기',
      handoffNote: '화면을 켠 채로 들어 주세요. 화면이 꺼지면 멈출 수 있어요', quizTap: '새 퀴즈가 왔어요. 눌러서 보기', car: '{n}호차', carCount: '차 {n}대 연결', carNone: '차 연결 전', tripEnded: '여행 끝'
    },
    en: {
      now: 'Now', course: 'Course', pick: 'Places', record: 'Record',
      telling: 'The car is telling', coming: 'Coming up', waiting: 'When the car nears a landmark, the video and 3D model appear here',
      video: 'Video', orbit: 'Rotate 3D', report: 'Report an error', me: 'Your name (shown in the car when you pick a place)', mePh: 'e.g. Alex',
      courseTitle: "Today's course", passed: 'Passed', nowAt: 'Now', next: 'Next',
      pickTitle: 'Pick where to go next', pickHelp: 'Choose from the list or tap the map. The car asks the driver, and once they say yes or tap OK, it goes into the navigation.',
      send1: 'Send to the car navigation', send2: 'Send to every car in the group', custom: 'Spot picked on the map', sent: 'Sent. Waiting for the driver to confirm', acked: 'Added to the navigation',
      recTitle: 'Our route', photoTitle: 'Add photos', photoHelp: 'Photos are pinned on the route by the time they were taken.', photoBtn: 'Upload photos',
      camTitle: 'Built-in cam highlight', camHelp: "Upload the car's built-in cam video from your phone. We'll pick the moments you passed each landmark and make a short trip video.", camBtn: 'Upload video', making: 'Making your video', made: 'Your trip video is ready', down: 'Download video', camFail: 'Could not make the video',
      byOrder: "The video time didn't match the record, so it was split in route order", uploading: 'Uploading', joined: 'You joined the trip group', tripEnd: "Today's trip is over. See everything in the Record tab",
      window: 'The windows are down a little. Listen to the outside', reportAsk: 'What was wrong?', reported: 'Reported. Thank you',
      sentFrom: ' sent a place', summary: 'We drove {km} km and passed {n} places', noRecord: 'Your route appears here when the trip ends',
      kind: { cafe: 'Cafe', parking: 'Parking', landmark: 'Place', sea: 'Sea', city: 'City', forest: 'Forest', place: 'Place' }, cars: ' cars', phones: ' phones',
      quiz: '{name} quiz', newQuiz: 'A new quiz arrived', right: 'Correct! You earned a star', wrong: 'Not quite. The answer is "{a}"', gotIt: '{by} got it',
      treasureOpen: 'The treasure box is open!', treasureOpenText: 'Open the glove box in front of the passenger seat', treasureHand: 'Open the glove box by hand',
      treasureReady: 'All stars collected!', treasureReadyText: 'Park the car and the treasure box opens',
      qaTitle: 'Asked in the car', asked: 'Asked the car: ', handoffTitle: 'Keep listening to {name} on your phone', handoffPlay: 'Keep listening', handoffStop: 'Stop', handoffNew: 'You can keep listening to the story on your phone',
      kidTitle: 'For the back seat',
      introTitle: 'What you can do here', introOk: 'Got it', introName: 'Add your name below. It shows on the car screen when you pick a place',
      intro: ['When the car starts a landmark story, the video and 3D model show up here', 'Answer quizzes to collect stars. Collect them all and the treasure box opens', 'Use the Places tab to send the next stop to the car', 'When the trip ends, see your route and photos in the Record tab'],
      basicNote: 'You joined with the code, so you can follow stories, quizzes and pick places. Scan the QR on the car screen to see photos and the record.',
      keep: "Only people in this group can see these. They're deleted {d} days after the trip ends.", keepOff: 'Only people in this group can see these.',
      reconnecting: 'Connection lost. Reconnecting…', noGroup: 'Group not found. The trip may have ended a while ago, or the code is different.', badKey: "This QR link doesn't match. Scan the QR on the car screen again.", again: 'Enter the code again', tooMany: 'Reconnecting in {s} s',
      pickLater: 'The driver saved it for later. It shows up again when the car stops', pickSoon: 'You can send again in {s} s', pickBusy: 'The driver is still checking "{name}". Send yours after they answer', pickNoCar: 'No car is connected right now', pickBad: "Can't send that spot. Pick somewhere near the course", pickOther: '{by} picked: {name}', confirmCustom: 'Send this spot?', sendHere: 'Send here',
      camErr: { 403: 'Only phones that joined with the QR can upload', 409: 'Another video is being made. Upload again when it finishes', 413: 'The video is too large. Upload one under {mb} MB', 415: 'Only MP4 or MOV videos can be uploaded', net: "Couldn't upload. Check the Wi-Fi and try again" },
      retry: 'Upload again', photoFail: "Couldn't upload the photo", reportFail: "Couldn't send the report. Try again in a moment",
      orbitHint: 'Drag with one finger to turn, pinch to zoom', tapPlay: '▶ Tap to play',
      handoffNote: 'Keep the screen on while listening. It may stop if the screen turns off', quizTap: 'A new quiz arrived. Tap to see it', car: 'Car {n}', carCount: '{n} car', carCountMany: '{n} cars', carNone: 'No car yet', tripEnded: 'Trip ended'
    }
  };
  function lang() { return S.lang || (S.group && S.group.lang) || 'ko'; }
  function t(k) { var d = TXT[lang()] || TXT.ko; return d[k] !== undefined ? d[k] : (TXT.ko[k] || k); }
  function nm(l10n) { if (!l10n) return ''; return l10n[lang()] || l10n.ko || l10n.en || ''; }
  function sentences(text) { return String(text || '').replace(/([.!?])\s+/g, '$1\u0001').split('\u0001').map(function (s) { return s.trim(); }).filter(Boolean); }
  function hhmm(ms) { var d = new Date(ms); return ('0' + d.getHours()).slice(-2) + ':' + ('0' + d.getMinutes()).slice(-2); }
  // 차 이름은 서버가 '1호차'처럼 붙여 줌. 영어 화면에서는 'Car 1'
  function carLabel(no, name) { return no ? t('car').replace('{n}', no) : (lang() === 'en' ? '' : (name || '')); }
  // 이 휴대폰이 따르는 차의 소식인지 (car=N 으로 들어왔으면 그 차만, 번호로 들어왔으면 모두)
  function mineCar(m) { return !myCar || !m || !m.carNo || m.carNo === myCar; }
  function withKey(url) { return key ? url + (url.indexOf('?') < 0 ? '?' : '&') + 'k=' + encodeURIComponent(key) : url; }
  function keyHeaders(h) { h = h || {}; if (key) h['X-Group-Key'] = key; return h; }
  function full() { return S.level === 'full'; }

  // 알림. onTap 을 주면 눌러서 그쪽으로 갈 수 있음 (퀴즈가 와도 탭을 억지로 바꾸지 않음)
  var toastTimer, toastTap = null;
  function toast(msg, onTap) {
    var el = $('toast'); el.textContent = msg; el.classList.add('show'); el.classList.toggle('tap', !!onTap); toastTap = onTap || null;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { el.classList.remove('show'); el.classList.remove('tap'); toastTap = null; }, onTap ? 6000 : 3500);
  }
  $('toast').onclick = function () { var f = toastTap; if (!f) return; toastTap = null; $('toast').classList.remove('show'); f(); };

  // ---------------------------------------------------------------- 3D
  GG.mount($('stage'), { model: 'cheomseongdae' });
  // '영상'은 미리 만든 짧은 클립(/clips/모형.mp4)을, '3D 돌려 보기'는 실시간 3D 모형을 보여 줌
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
  function playClip() {
    var p = clipEl.play && clipEl.play();
    if (p && p.then) p.then(function () { $('playBtn').classList.add('hidden'); }, function () { if (!clipEl.classList.contains('hidden')) $('playBtn').classList.remove('hidden'); });
  }
  $('playBtn').onclick = function () { clipEl.muted = true; playClip(); };
  clipEl.onplaying = function () { $('playBtn').classList.add('hidden'); };
  function showVideoMode(on) {
    clipEl.classList.toggle('hidden', !on);
    if (!on) $('playBtn').classList.add('hidden');
    GG.setPaused(on);
    $('mVideo').classList.toggle('on', on || GG.mode() === 'cinematic');
    $('mOrbit').classList.toggle('on', !on && GG.mode() === 'orbit');
  }
  GG.onMode = function (m) { if (m === 'orbit') showVideoMode(false); $('mVideo').classList.toggle('on', m === 'cinematic'); $('mOrbit').classList.toggle('on', m === 'orbit'); };
  // 3D 를 돌려 본 뒤 '영상'으로 돌아오면 화면을 다시 위아래로 밀 수 있게 cinematic 으로 되돌림
  $('mVideo').onclick = function () { GG.setMode('cinematic'); if (clipOk && clipModel) showVideoMode(true); };
  $('mOrbit').onclick = function () { showVideoMode(false); GG.setMode('orbit'); };

  // ---------------------------------------------------------------- 탭과 언어
  function setTab(tab) {
    S.tab = tab;
    ['now', 'course', 'pick', 'record'].forEach(function (k) { $('tab-' + k).classList.toggle('hidden', k !== tab); });
    Array.prototype.forEach.call(document.querySelectorAll('.tabs button'), function (b) { b.classList.toggle('on', b.dataset.tab === tab); });
    if (tab === 'pick') setTimeout(drawPickMap, 50);
    if (tab === 'record') setTimeout(drawRecord, 50);
  }
  Array.prototype.forEach.call(document.querySelectorAll('.tabs button'), function (b) { b.onclick = function () { setTab(b.dataset.tab); }; });
  Array.prototype.forEach.call(document.querySelectorAll('.lang button'), function (b) {
    b.onclick = function () { S.lang = b.dataset.lang; localStorage.setItem('gg_lang', S.lang); renderAll(); };
  });
  $('me').value = me;
  $('me').oninput = function () { me = $('me').value.trim(); localStorage.setItem('gg_me', me); };

  function staticTexts() {
    $('tNow').textContent = t('now'); $('tCourse').textContent = t('course'); $('tPick').textContent = t('pick'); $('tRecord').textContent = t('record');
    $('mVideo').textContent = t('video'); $('mOrbit').textContent = t('orbit'); $('report').textContent = t('report');
    $('meTitle').textContent = t('me'); $('me').placeholder = t('mePh');
    $('courseTitle').textContent = t('courseTitle'); $('pickTitle').textContent = t('pickTitle'); $('pickHelp').textContent = t('pickHelp');
    $('recTitle').textContent = t('recTitle'); $('photoTitle').textContent = t('photoTitle'); $('photoHelp').textContent = t('photoHelp'); $('photoBtn').textContent = t('photoBtn');
    $('camTitle').textContent = t('camTitle'); $('camHelp').textContent = t('camHelp'); $('camBtn').textContent = t('camBtn'); $('hlDown').textContent = t('down');
    $('qaTitle').textContent = t('qaTitle'); $('kidTitle').textContent = t('kidTitle');
    $('introTitle').textContent = t('introTitle'); $('introOk').textContent = t('introOk');
    $('introList').innerHTML = t('intro').concat([t('introName')]).map(function (s) { return '<li>' + escapeHtml(s) + '</li>'; }).join('');
    $('basicNote').textContent = t('basicNote');
    var days = S.group && typeof S.group.keepDays === 'number' ? S.group.keepDays : 3;
    ['keepRec', 'keepPhoto', 'keepCam'].forEach(function (id) { $(id).textContent = days > 0 ? t('keep').replace('{d}', days) : t('keepOff'); });
    $('handoffNote').textContent = t('handoffNote'); $('playBtn').textContent = t('tapPlay'); $('camRetry').textContent = t('retry');
    // 번호로만 들어온 휴대폰(basic)에는 사진·영상 올리기 대신 QR 안내
    $('photoCard').classList.toggle('hidden', !full()); $('camCard').classList.toggle('hidden', !full()); $('basicCard').classList.toggle('hidden', full());
    GG.setHint(t('orbitHint'));
    Array.prototype.forEach.call(document.querySelectorAll('.lang button'), function (b) { b.classList.toggle('on', b.dataset.lang === lang()); });
    document.documentElement.lang = lang();
  }

  // 처음 들어온 휴대폰: '여기서 할 수 있는 것'과 이름 칸을 맨 위에. '알겠어요'를 누르면 이름 칸은 아래로
  // (이름을 이미 적어 둔 휴대폰은 처음이 아니라고 봄)
  var introSeen = store('gg_intro') === '1' || !!me;
  function placeIntro() {
    $('introCard').classList.toggle('hidden', introSeen);
    if (!introSeen) $('tab-now').insertBefore($('meCard'), $('introCard').nextSibling);
    else $('tab-now').appendChild($('meCard'));
  }
  $('introOk').onclick = function () { introSeen = true; store('gg_intro', '1'); placeIntro(); };
  placeIntro();

  function renderTop() {
    var sc = S.scenario;
    $('title').textContent = sc ? nm(sc.title) : t('now');
    $('who').textContent = sc ? nm(sc.who) + (code ? '  #' + code : '') + (myCar && S.cars.length > 1 ? ' · ' + carLabel(myCar) : '') : '';
    var n = S.cars.length || 0;
    // 몇 대가 붙어 있는지 말로 보여 줌. 끝난 여행은 '여행 끝', 아직 차가 안 붙었으면 '차 연결 전'
    $('cars').textContent = S.ended && n === 0 ? t('tripEnded') : n === 0 ? t('carNone') : (n > 1 && lang() === 'en' ? t('carCountMany') : t('carCount')).replace('{n}', n);
    $('liveDot').classList.toggle('on', S.online && S.cars.length > 0);
  }

  // ---------------------------------------------------------------- 지금
  function placeById(id) {
    var sc = S.scenario; if (!sc) return null;
    return sc.places.concat(sc.pickables || []).filter(function (p) { return p.id === id; })[0] || null;
  }

  function renderNow() {
    var st = S.story;
    $('report').disabled = !st;
    if (!st) {
      $('nowLabel').textContent = '';
      $('nowName').textContent = '';
      $('lines').innerHTML = '<span class="small">' + t('waiting') + '</span>';
      $('nowSmall').textContent = '';
      return;
    }
    var place = placeById(st.placeId);
    $('nowLabel').textContent = (st.kind === 'announce' ? t('coming') : t('telling')) + (S.cars.length > 1 && st.carNo ? '  ' + carLabel(st.carNo, st.car) : '');
    $('nowName').textContent = place ? nm(place.name) : st.name;
    renderLines();
  }

  function renderLines() {
    if (!S.story) return;
    var html = S.lines.map(function (s, i) {
      var cls = S.storyDone || i < S.lineIdx ? 'done' : i === S.lineIdx ? 'cur' : '';
      return '<span class="' + cls + '">' + escapeHtml(s) + ' </span>';
    }).join('');
    $('lines').innerHTML = html;
    var cur = S.lines[Math.max(0, S.lineIdx)] || '';
    GG.caption(S.storyDone ? '' : cur);
  }

  function escapeHtml(s) { return String(s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }

  // quiet: 다시 연결했을 때 다시 그리기만 (탭을 바꾸거나 진동하지 않음)
  function showStory(m, quiet) {
    S.story = m; S.lines = sentences(m.text); S.lineIdx = 0; S.storyDone = !!m.done;
    var place = placeById(m.placeId);
    var model = m.model || (place && place.model) || 'generic';
    GG.show({ model: model, name: place ? nm(place.name) : m.name, rain: !!m.rain, night: !!m.night, mode: 'cinematic', caption: S.lines[0] || '' });
    setClip(model, !!m.night);
    showVideoMode(true);
    renderNow();
    if (quiet) return;
    if (S.tab === 'course') setTab('now');
    else if (S.tab !== 'now') toast(t('telling') + ': ' + (place ? nm(place.name) : m.name), function () { setTab('now'); });
    if (navigator.vibrate) navigator.vibrate(40);
  }

  $('report').onclick = function () {
    if (!S.story) return;
    var note = prompt(t('reportAsk'));
    if (!note || !note.trim()) return;
    fetch('/api/groups/' + code + '/report', { method: 'POST', headers: keyHeaders({ 'Content-Type': 'application/json' }), body: JSON.stringify({ placeId: S.story.placeId, name: S.story.name, note: note.slice(0, 500), by: me }) })
      .then(function (r) { toast(r.ok ? t('reported') : t('reportFail')); }, function () { toast(t('reportFail')); });
  };

  // ---------------------------------------------------------------- 코스
  function renderCourse() {
    var sc = S.scenario; if (!sc) return;
    var passed = {}; S.passes.forEach(function (p) { if (!passed[p.placeId]) passed[p.placeId] = p.at; });
    var nowId = S.story && !S.storyDone ? S.story.placeId : null;
    var nextFound = false;
    var html = sc.places.filter(function (p) { return p.kind !== 'city'; }).map(function (p) {
      var done = !!passed[p.id], now = p.id === nowId;
      var label = now ? t('nowAt') : done ? t('passed') + ' ' + hhmm(passed[p.id]) : (!nextFound ? (nextFound = true, t('next')) : '');
      return '<li data-id="' + p.id + '"><span class="dot ' + (now ? 'now' : done ? 'done' : '') + '"></span><b>' + escapeHtml(nm(p.name)) + '</b><span class="t">' + label + '</span></li>';
    }).join('');
    $('course').innerHTML = html;
    Array.prototype.forEach.call($('course').querySelectorAll('li'), function (li) {
      li.onclick = function () { var p = placeById(li.dataset.id); if (p && p.model) { GG.show({ model: p.model, name: nm(p.name), mode: 'orbit', caption: '' }); setTab('now'); } };
    });
  }

  // ---------------------------------------------------------------- 장소 고르기
  var pickMap, pickLayer, customMarker;
  function tiles(map) {
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19, attribution: '&copy; OpenStreetMap' }).addTo(map);
  }
  function pin(color, label) {
    // 보이는 점은 18px, 손가락으로 누르는 영역은 44px
    return L.divIcon({ className: '', iconSize: [44, 44], iconAnchor: [22, 22], html: '<div class="pin-hit"><div class="pin" style="background:' + color + '"></div></div>' + (label ? '<div class="pin-label" style="position:absolute;left:36px;top:10px">' + escapeHtml(label) + '</div>' : '') });
  }
  var KIND_COLOR = { cafe: '#ffb86b', parking: '#7fe3ff', landmark: '#a78bfa', sea: '#00e5ff', city: '#ffd166', forest: '#4ade80', place: '#f472b6' };

  function drawPickMap() {
    var sc = S.scenario; if (!sc || !window.L) return;
    if (!pickMap) {
      pickMap = L.map('pickMap', { zoomControl: false, attributionControl: true });
      tiles(pickMap);
      pickLayer = L.layerGroup().addTo(pickMap);
      pickMap.on('click', function (e) {
        var ll = e.latlng.wrap();   // 지도를 옆으로 여러 바퀴 돌려도 경도가 -180~180 안에 들게
        S.selected = { id: 'custom', name: t('custom'), lat: ll.lat, lon: ll.lng, kind: 'place' };
        if (customMarker) customMarker.remove();
        customMarker = L.marker(ll, { icon: pin(KIND_COLOR.place, t('custom')) }).addTo(pickMap);
        // 지도를 잘못 눌러도 바로 가지 않게 '여기로 보낼까요?'를 한 번 더 물음
        var box = document.createElement('div'); box.className = 'pick-pop';
        box.appendChild(document.createTextNode(t('confirmCustom')));
        var yes = document.createElement('button'); yes.type = 'button'; yes.textContent = t('sendHere');
        yes.onclick = function () { pickMap.closePopup(); sendPick(); };
        box.appendChild(yes);
        customMarker.bindPopup(box, { closeButton: true }).openPopup();
        renderPickList();
      });
    }
    pickLayer.clearLayers();
    var line = L.polyline(sc.route.map(function (p) { return [p[0], p[1]]; }), { color: '#7fe3ff', weight: 5, opacity: 0.8 }).addTo(pickLayer);
    sc.places.filter(function (p) { return p.kind !== 'city'; }).forEach(function (p) {
      L.marker([p.lat, p.lon], { icon: pin('#ffffff', nm(p.name)) }).addTo(pickLayer).on('click', function () { select(p); });
    });
    (sc.pickables || []).forEach(function (p) {
      L.marker([p.lat, p.lon], { icon: pin(KIND_COLOR[p.kind] || '#fff', nm(p.name)) }).addTo(pickLayer).on('click', function () { select(p); });
    });
    pickMap.invalidateSize();
    pickMap.fitBounds(line.getBounds(), { padding: [20, 20] });
    renderPickList();
  }

  function select(p) {
    S.selected = { id: p.id, name: nm(p.name), lat: p.lat, lon: p.lon, kind: p.kind };
    renderPickList();
  }

  function renderPickList() {
    var sc = S.scenario; if (!sc) return;
    var items = (sc.pickables || []).concat(sc.places.filter(function (p) { return p.kind !== 'city'; }));
    $('picklist').innerHTML = items.map(function (p) {
      var sel = S.selected && S.selected.id === p.id;
      return '<button data-id="' + p.id + '" class="' + (sel ? 'sel' : '') + '"><span class="kind">' + (t('kind')[p.kind] || '') + '</span>' + escapeHtml(nm(p.name)) + '</button>';
    }).join('') + (S.selected && S.selected.id === 'custom' ? '<button class="sel"><span class="kind">' + t('kind').place + '</span>' + t('custom') + '</button>' : '');
    Array.prototype.forEach.call($('picklist').querySelectorAll('button[data-id]'), function (b) { b.onclick = function () { select(placeById(b.dataset.id)); }; });
    renderPickButton();
  }

  // 보내기 단추 잠금: 서버 답을 기다리는 동안, 그룹에 운전자 확인을 기다리는 곳이 있을 때(90초까지), 보낸 뒤 20초
  function pickLocked() {
    var now = Date.now();
    return (S.pickWait && now - S.pickWait.at < 8000) || (S.pending && now - S.pending.at < 90000) || now < (S.pickSoonUntil || 0);
  }
  function setPickStatus(msg, warn) { var el = $('pickStatus'); el.textContent = msg || ''; el.classList.toggle('status-warn', !!warn); }
  function renderPickButton() {
    var btn = $('sendPick');
    btn.disabled = !S.selected || !S.online || pickLocked();
    // 보낸 뒤 20초 동안은 단추에 남은 초를 보여 줌 (아래 줄에는 운전자 답을 그대로 둠)
    var left = Math.ceil(((S.pickSoonUntil || 0) - Date.now()) / 1000);
    if (left > 0 && !(S.pending && Date.now() - S.pending.at < 90000)) btn.textContent = t('pickSoon').replace('{s}', left);
    else btn.textContent = (S.cars.length > 1 ? t('send2') : t('send1')) + (S.selected ? ': ' + S.selected.name : '');
  }
  setInterval(function () { if (S.tab === 'pick') renderPickButton(); }, 1000);
  // 휴대폰은 장소 id 만 보냄(이름과 위치는 서버가 시나리오에서 채움). 지도에서 찍은 곳은 좌표만
  function sendPick() {
    if (!S.selected || !S.ws || S.ws.readyState !== 1 || pickLocked()) return;
    var by = me || (lang() === 'en' ? 'Passenger' : '동승자');
    S.ws.send(JSON.stringify(S.selected.id === 'custom' ? { type: 'pick', custom: true, lat: S.selected.lat, lon: S.selected.lon, by: by } : { type: 'pick', placeId: S.selected.id, by: by }));
    S.pickWait = { at: Date.now() };
    setPickStatus(t('sent'));
    renderPickButton();
  }
  $('sendPick').onclick = sendPick;
  function pickName(id, fallback) {
    var p = S.picks.filter(function (x) { return x.id === id; })[0];
    var pl = p && p.placeId && placeById(p.placeId);
    return pl ? nm(pl.name) : (p && p.custom ? t('custom') : (fallback || (p && p.name) || ''));
  }

  // ---------------------------------------------------------------- 기록
  var recMap, recLayer;
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

  function drawRecord() {
    var sc = S.scenario; if (!sc || !window.L) return;
    if (!recMap) { recMap = L.map('recMap', { zoomControl: false }); tiles(recMap); recLayer = L.layerGroup().addTo(recMap); }
    recLayer.clearLayers();
    var r = routeFromRecord();
    var pts = r ? r.map(function (p) { return [p.lat, p.lon]; }) : sc.route.map(function (p) { return [p[0], p[1]]; });
    var line = L.polyline(pts, { color: r ? '#ffd166' : '#55606e', weight: 6, opacity: 0.9, dashArray: r ? null : '6 8' }).addTo(recLayer);
    var passed = {}; S.passes.forEach(function (p) { passed[p.placeId] = p.at; });
    sc.places.filter(function (p) { return p.kind !== 'city'; }).forEach(function (p) {
      L.marker([p.lat, p.lon], { icon: pin(passed[p.id] ? '#4ade80' : '#55606e', nm(p.name) + (passed[p.id] ? ' ' + hhmm(passed[p.id]) : '')) }).addTo(recLayer);
    });
    S.photos.forEach(function (ph) {
      var pos = positionAt(ph.takenAt); if (!pos) return;
      L.marker(pos, { icon: L.divIcon({ className: '', iconSize: [44, 44], iconAnchor: [22, 22], html: '<img src="' + escapeHtml(withKey(ph.url)) + '" alt="" style="width:44px;height:44px;object-fit:cover;border-radius:10px;border:2px solid #fff;box-shadow:0 2px 8px rgba(0,0,0,.5)">' }) }).addTo(recLayer);
    });
    recMap.invalidateSize();
    recMap.fitBounds(line.getBounds(), { padding: [24, 24] });
    if (S.record) {
      var n = sc.places.filter(function (p) { return p.kind !== 'city' && passed[p.id]; }).length;
      $('recSummary').textContent = t('summary').replace('{km}', (S.record.distanceM / 1000).toFixed(1)).replace('{n}', n);
    } else $('recSummary').textContent = t('noRecord');
    renderPhotos();
    renderHighlight();
  }

  function nearestPlaceName(time) {
    var pass = null;
    S.passes.forEach(function (p) { if (!pass || Math.abs(p.at - time) < Math.abs(pass.at - time)) pass = p; });
    var pl = pass && placeById(pass.placeId);
    return pl ? nm(pl.name) : '';
  }

  function renderPhotos() {
    $('photos').innerHTML = S.photos.map(function (p) {
      return '<figure><img src="' + escapeHtml(withKey(p.url)) + '" loading="lazy" alt=""><figcaption>' + hhmm(p.takenAt) + ' ' + escapeHtml(nearestPlaceName(p.takenAt)) + '</figcaption></figure>';
    }).join('');
  }

  function renderHighlight() {
    var h = S.highlight;
    var st = $('camStatus'), v = $('hl'), a = $('hlDown');
    if (!h) { st.textContent = ''; v.classList.add('hidden'); a.classList.add('hidden'); return; }
    st.classList.remove('status-warn'); $('camRetry').classList.add('hidden');
    if (h.status === 'processing') { st.textContent = t('making'); v.classList.add('hidden'); a.classList.add('hidden'); }
    else if (h.status === 'done') {
      st.textContent = t('made') + (h.mode === 'order' ? '. ' + t('byOrder') : '');
      if (v.getAttribute('src') !== withKey(h.url)) v.src = withKey(h.url);
      v.classList.remove('hidden'); a.href = withKey(h.url); a.classList.remove('hidden');
    } else { st.textContent = t('camFail'); st.classList.add('status-warn'); $('camRetry').classList.toggle('hidden', !lastCam); }
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

  // 캔버스로 다시 그려 올리므로 사진의 EXIF(GPS 위치, 기기 정보)는 빠짐. 촬영 시각만 따로 읽어 보냄
  $('photoInput').onchange = function () {
    var files = Array.prototype.slice.call(this.files || []);
    this.value = '';
    var fails = 0, st = $('photoStatus');
    st.classList.remove('status-warn');
    files.reduce(function (p, f) {
      return p.then(function () {
        st.textContent = t('uploading') + ' ' + f.name;
        return Promise.all([exifTime(f), shrink(f)]).then(function (r) {
          if (!r[1]) { fails++; return; }
          return fetch('/api/groups/' + code + '/photos', { method: 'POST', headers: keyHeaders({ 'Content-Type': 'application/json' }), body: JSON.stringify({ dataUrl: r[1], takenAt: r[0] || f.lastModified, by: me }) })
            .then(function (res) { if (!res.ok) fails++; }, function () { fails++; });
        });
      });
    }, Promise.resolve()).then(function () {
      st.textContent = fails ? t('photoFail') + ' (' + fails + ')' : '';
      st.classList.toggle('status-warn', fails > 0);
    });
  };

  // 빌트인 캠 영상: 실패하면 까닭을 알려 주고 '다시 올리기' 단추를 띄움 (같은 파일을 다시 고르지 않아도 됨)
  var lastCam = null;
  function camFailed(msg) {
    var st = $('camStatus');
    st.textContent = msg; st.classList.add('status-warn');
    $('camRetry').classList.toggle('hidden', !lastCam);
  }
  function uploadCam(f) {
    var mb = (S.group && S.group.maxUploadMB) || 400;
    var err = t('camErr');
    lastCam = f;
    $('camRetry').classList.add('hidden');
    if (f.size > mb * 1024 * 1024) { lastCam = null; return camFailed(err[413].replace('{mb}', mb)); }
    var st = $('camStatus');
    st.classList.remove('status-warn');
    st.textContent = t('uploading') + ' 0%';
    var xhr = new XMLHttpRequest();
    xhr.open('POST', '/api/groups/' + code + '/dashcam?name=' + encodeURIComponent(f.name) + '&lastModified=' + f.lastModified);
    if (key) xhr.setRequestHeader('X-Group-Key', key);
    xhr.upload.onprogress = function (e) { if (e.lengthComputable) st.textContent = t('uploading') + ' ' + Math.round(e.loaded / e.total * 100) + '%'; };
    xhr.onload = function () {
      if (xhr.status === 200) { st.textContent = t('making'); return; }
      if (xhr.status === 413 || xhr.status === 415 || xhr.status === 403) lastCam = null;   // 같은 파일로 다시 해도 안 됨
      camFailed((err[xhr.status] || err.net).replace('{mb}', mb));
    };
    xhr.onerror = xhr.ontimeout = function () { camFailed(err.net); };
    xhr.send(f);
  }
  $('camInput').onchange = function () { var f = (this.files || [])[0]; this.value = ''; if (f) uploadCam(f); };
  $('camRetry').onclick = function () { if (lastCam) uploadCam(lastCam); };

  // ---------------------------------------------------------------- 퀴즈와 보물 상자
  function starsHtml(have, need) {
    var n = Math.max(need || 0, have || 0), s = '';
    for (var i = 0; i < n; i++) s += i < have ? '★' : '<span class="off">☆</span>';
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
    $('quizLabel').textContent = t('quiz').replace('{name}', q.name || '');
    $('quizStars').innerHTML = starNeed() ? starsHtml(starCount(), starNeed()) : '';
    $('quizQ').textContent = q.q;
    var mine = myAnswer(q.id);
    // 다시 들어온 경우 정답 번호는 모를 수 있음 (맞혔으면 고른 것이 정답)
    var correctIdx = mine ? (typeof mine.answer === 'number' ? mine.answer : (mine.correct ? mine.choice : null)) : null;
    $('quizChoices').innerHTML = q.choices.map(function (c, i) {
      var cls = '';
      if (mine) cls = i === correctIdx ? 'right' : (i === mine.choice ? 'wrong' : '');
      return '<button data-i="' + i + '" class="' + cls + '"' + (mine ? ' disabled' : '') + '>' + (i + 1) + '. ' + escapeHtml(c) + '</button>';
    }).join('');
    Array.prototype.forEach.call($('quizChoices').querySelectorAll('button'), function (b) {
      b.onclick = function () { answerQuiz(q, Number(b.dataset.i)); };
    });
    var others = S.answers.filter(function (a) { return a.id === q.id && a.correct && !a.mine; }).map(function (a) { return a.by; });
    var res = '';
    if (mine) res = mine.correct ? t('right') : (correctIdx != null ? t('wrong').replace('{a}', q.choices[correctIdx]) : t('wrong').split('.')[0] + '.');
    if (others.length) res += (res ? '  ' : '') + t('gotIt').replace('{by}', others.join(', '));
    $('quizResult').textContent = res;
    $('quizResult').style.color = mine && mine.correct ? 'var(--good)' : '';
  }

  function answerQuiz(q, i) {
    if (myAnswer(q.id) || !S.ws || S.ws.readyState !== 1) return;
    S.ws.send(JSON.stringify({ type: 'quiz_answer', id: q.id, choice: i, by: me || (lang() === 'en' ? 'Passenger' : '동승자') }));
    Array.prototype.forEach.call($('quizChoices').querySelectorAll('button'), function (b) { b.disabled = true; b.classList.toggle('picked', Number(b.dataset.i) === i); });
  }

  function renderTreasure() {
    var card = $('treasureCard');
    var tr = S.treasure, st = S.stars;
    if (tr) {
      card.classList.remove('hidden');
      $('treasureStars').innerHTML = starsHtml(tr.stars, tr.stars);
      $('treasureTitle').textContent = t('treasureOpen');
      $('treasureText').textContent = tr.unlocked ? t('treasureOpenText') : t('treasureHand');
    } else if (st && st.ready) {
      card.classList.remove('hidden');
      $('treasureStars').innerHTML = starsHtml(st.stars, st.need);
      $('treasureTitle').textContent = t('treasureReady');
      $('treasureText').textContent = t('treasureReadyText');
    } else card.classList.add('hidden');
  }

  // ---------------------------------------------------------------- 차에 물어본 것, 아이 이야기, 이어 듣기
  function renderQa() {
    var card = $('qaCard');
    if (!S.qa.length) { card.classList.add('hidden'); return; }
    card.classList.remove('hidden');
    $('qaList').innerHTML = S.qa.slice(-5).reverse().map(function (x) {
      return '<li><span class="t">' + hhmm(x.at) + (x.title ? '  ' + escapeHtml(x.title) : '') + '</span><div class="q">' + (x.q ? '“' + escapeHtml(x.q) + '”' : '') + '</div><div class="a">' + escapeHtml(x.a) + '</div></li>';
    }).join('');
  }

  function renderKid() {
    var card = $('kidCard');
    if (!S.kid) { card.classList.add('hidden'); return; }
    card.classList.remove('hidden');
    $('kidText').textContent = S.kid.text;
  }

  var speaking = false;
  function renderHandoff() {
    var card = $('handoffCard'), h = S.handoff;
    if (!h) { card.classList.add('hidden'); return; }
    card.classList.remove('hidden');
    $('handoffTitle').textContent = t('handoffTitle').replace('{name}', h.name || '');
    $('handoffText').textContent = h.text;
    $('handoffBtn').textContent = speaking ? t('handoffStop') : t('handoffPlay');
  }
  $('handoffBtn').onclick = function () {
    var h = S.handoff;
    if (!h || !window.speechSynthesis) return;
    if (speaking) { speechSynthesis.cancel(); speaking = false; renderHandoff(); return; }
    var u = new SpeechSynthesisUtterance(h.text);
    u.lang = h.lang === 'en' ? 'en-US' : 'ko-KR';
    u.rate = 1.0;
    u.onend = u.onerror = function () { speaking = false; renderHandoff(); };
    speechSynthesis.cancel();
    speechSynthesis.speak(u);
    speaking = true;
    renderHandoff();
  };

  // ---------------------------------------------------------------- 연결
  function apply(summary) {
    S.group = summary;
    if (summary.access) S.level = summary.access;
    S.cars = summary.cars || S.cars;
    S.passes = summary.passes || [];
    S.picks = summary.picks || S.picks;
    S.acks = summary.acks || S.acks;
    S.record = summary.record || null;
    S.ended = !!summary.ended;
    S.photos = summary.photos || [];
    S.highlight = summary.highlight || null;
    S.quizzes = summary.quizzes || [];
    S.answers = summary.answers || [];
    S.scores = summary.scores || {};
    S.qa = summary.qa || [];
    S.handoff = summary.handoff && mineCar(summary.handoff) ? summary.handoff : null;
    S.treasure = summary.treasure && mineCar(summary.treasure) ? summary.treasure : null;
    S.stars = summary.stars && mineCar(summary.stars) ? summary.stars : null;
    // 다시 연결하면 지금 하는 이야기를 다시 그림 (끊긴 사이에 바뀌었거나 끝났을 수 있음)
    var st = myStory(summary);
    if (st && (!S.story || S.story.placeId !== st.placeId || S.story.at !== st.at)) showStory(st, true);
    if (st && S.story) {
      var ln = (summary.lines || []).concat(summary.line ? [summary.line] : []).filter(function (l) { return l && l.placeId === st.placeId && sameCar(l, st); })[0];
      if (ln && !st.done) S.lineIdx = ln.index;
      S.storyDone = !!st.done;
      renderLines();
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
      if (!S.story) {
        var first = sc.places.filter(function (p) { return p.model; })[0];
        if (first) GG.show({ model: first.model, name: '', caption: '', mode: 'cinematic' });
      }
      renderAll();
    }).catch(function () { setBanner(escapeHtml(t('reconnecting'))); });
  }

  function renderAll() {
    staticTexts(); renderTop(); renderNow(); renderCourse(); renderPickList();
    renderQuiz(); renderTreasure(); renderQa(); renderKid(); renderHandoff();
    if (S.tab === 'pick') drawPickMap();
    if (S.tab === 'record') drawRecord();
  }

  // ---------------------------------------------------------------- 연결 (끊기면 1초부터 15초까지 늘려 가며 다시 붙음)
  var ws = null, retryMs = 1000, retryTimer = null, retryIn = 0, stopped = false, hiddenAt = 0;
  function wsUrl() {
    return (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws?code=' + code + '&role=phone' +
      (key ? '&k=' + encodeURIComponent(key) : '') + (pid ? '&pid=' + encodeURIComponent(pid) : '');
  }
  function setBanner(html) { var b = $('banner'); b.classList.toggle('hidden', !html); b.innerHTML = html || ''; }
  function fatal(msgKey) {
    stopped = true;
    setBanner(escapeHtml(t(msgKey)) + ' <a href="/">' + escapeHtml(t('again')) + '</a>');
    if (!S.story) $('lines').textContent = t(msgKey);
  }
  function connect() {
    clearTimeout(retryTimer);
    if (stopped || (ws && ws.readyState <= 1)) return;
    var sock = ws = S.ws = new WebSocket(wsUrl());
    sock.onopen = function () { S.online = true; retryMs = 1000; setBanner(null); renderTop(); renderPickButton(); };
    sock.onclose = function (e) {
      if (sock !== ws) return;   // 일부러 바꾼 예전 연결
      S.online = false; renderTop(); renderPickButton();
      if (e.code === 4404) return fatal('noGroup');
      if (e.code === 4401) {
        // 이 휴대폰에 남은 예전 키(같은 번호의 지난 여행)면 버리고 번호로 다시 붙음. QR 주소의 키가 틀리면 멈춤
        if (!key || urlKey) return fatal('badKey');
        key = ''; store('gg_k_' + code, null); S.level = 'basic'; staticTexts();
      }
      var wait = retryMs;
      if (e.code === 4429) { wait = Math.max(wait, (retryIn || 30) * 1000); setBanner(escapeHtml(t('tooMany').replace('{s}', Math.round(wait / 1000)))); }
      else setBanner(escapeHtml(t('reconnecting')));
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
          if (first) toast(t('joined'));
          break;
        case 'error': if (m.retryIn) retryIn = m.retryIn; break;
        case 'members': S.cars = m.cars || []; S.phones = m.phones; renderTop(); renderPickList(); break;
        case 'trip': if (S.group) { S.group.lang = m.lang; } if (!S.scenario || S.scenario.id !== m.scenarioId) loadScenario(m.scenarioId); else renderAll(); break;
        // 차가 둘이면 QR 의 car=N 차 이야기만 따름
        case 'story': if (mineCar(m)) showStory(m); break;
        case 'line': if (S.story && m.placeId === S.story.placeId && sameCar(m, S.story)) { S.lineIdx = m.index; renderLines(); } break;
        case 'story_end': if (S.story && m.placeId === S.story.placeId && sameCar(m, S.story)) { S.storyDone = true; S.story.done = true; renderLines(); renderCourse(); } break;
        case 'pass': S.passes.push({ placeId: m.placeId, name: m.name, at: m.at, car: m.car }); renderCourse(); break;
        case 'pick':
          S.picks.push(m);
          if (m.fromCar) { toast(carLabel(m.carNo, m.car) + t('sentFrom') + ': ' + m.name); break; }
          // 그룹에 운전자 확인을 기다리는 곳이 생김: 답이 올 때까지(최대 90초) 모든 휴대폰의 보내기 단추를 잠금
          S.pending = { id: m.id, name: pickName(m.id, m.name), at: Date.now() };
          if (m.mine) { S.pickWait = null; S.pickSoonUntil = Date.now() + 20000; S.myPicks = (S.myPicks || {}); S.myPicks[m.id] = 1; setPickStatus(t('sent')); }
          else toast(t('pickOther').replace('{by}', m.by || '').replace('{name}', pickName(m.id, m.name)));
          renderPickButton();
          break;
        case 'pick_wait':
          // 서버가 보내지 않은 까닭: soon(20초 안에 또), busy(다른 곳 확인 중), nocar, bad
          S.pickWait = null;
          if (m.reason === 'soon') S.pickSoonUntil = Date.now() + (m.retryIn || 20) * 1000;
          else if (m.reason === 'busy') { S.pending = { id: S.pending && S.pending.id, name: m.name || '', at: Date.now() - Math.max(0, 90 - (m.retryIn || 90)) * 1000 }; setPickStatus(t('pickBusy').replace('{name}', m.name || ''), true); }
          else setPickStatus(t(m.reason === 'nocar' ? 'pickNoCar' : 'pickBad'), true);
          renderPickButton();
          break;
        case 'pick_ack':
          S.acks.push(m);
          if (S.pending && S.pending.id === m.id) S.pending = null;
          var ackText = m.accepted === false ? t('pickLater') : t('acked') + ': ' + pickName(m.id, m.name);
          if (S.myPicks && S.myPicks[m.id]) setPickStatus(ackText, m.accepted === false);
          toast((S.cars.length > 1 ? carLabel(m.carNo, m.car) + ' ' : '') + ackText);
          renderPickButton();
          break;
        case 'window': if (mineCar(m)) toast(t('window')); break;
        case 'record': S.record = m.record; if (S.tab === 'record') drawRecord(); break;
        case 'photo': S.photos.push(m.photo); S.photos.sort(function (a, b) { return a.takenAt - b.takenAt; }); if (S.tab === 'record') drawRecord(); break;
        case 'highlight': S.highlight = m.highlight; renderHighlight(); break;
        case 'trip_end': S.ended = true; renderTop(); toast(t('tripEnd')); break;
        case 'quiz':
          S.quizzes = S.quizzes.filter(function (q) { return q.id !== m.id; }).concat([m]);
          if (!mineCar(m)) break;
          if (m.need) S.stars = Object.assign({ stars: 0, ready: false }, S.stars || {}, { need: m.need });
          // 탭을 억지로 바꾸지 않음. 다른 탭이면 눌러서 갈 수 있는 알림만
          renderQuiz();
          if (S.tab === 'now') toast(t('newQuiz')); else toast(t('quizTap'), function () { setTab('now'); });
          if (navigator.vibrate) navigator.vibrate([60, 60, 60]);
          break;
        case 'quiz_answer':
          S.answers.push(m); S.scores = m.scores || S.scores; renderQuiz();
          break;
        case 'stars': if (mineCar(m)) { S.stars = m; renderQuiz(); renderTreasure(); } break;
        case 'treasure':
          if (!mineCar(m)) break;
          S.treasure = m; renderTreasure();
          if (S.tab !== 'now') toast(t('treasureOpen'), function () { setTab('now'); });
          if (navigator.vibrate) navigator.vibrate([120, 80, 120, 80, 300]);
          break;
        case 'qa': S.qa.push(m); renderQa(); if (m.q) toast(t('asked') + m.q); break;
        case 'kid': if (mineCar(m)) { S.kid = m; renderKid(); } break;
        case 'handoff': if (mineCar(m)) { S.handoff = m; renderHandoff(); toast(t('handoffNew')); } break;
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
    if (!clipEl.classList.contains('hidden')) playClip();
  });
  window.addEventListener('online', function () { if (!ws || ws.readyState > 1) resync(); });

  staticTexts();
  setTab(startTab);
  if (!code) { location.href = '/'; return; }
  // 그룹 정보는 WebSocket 의 hello 로 받음 (번호 조회를 한 번만 셈)
  connect();
})();
