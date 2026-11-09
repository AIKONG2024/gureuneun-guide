/* 구르는 가이드 동승자 그룹 페이지
 * 앱 설치 없이 QR로 들어와, 차가 이야기를 시작하면 같은 순간 영상과 3D 모형을 보여 준다.
 * 지도에서 고른 장소는 그룹의 모든 차로 보내고, 여행이 끝나면 지나온 길과 사진, 하이라이트 영상을 모은다.
 */
(function () {
  'use strict';
  var code = (location.pathname.match(/\/g\/(\d{4})/) || [])[1];
  var startTab = /\/record\/?$/.test(location.pathname) ? 'record' : 'now';
  var S = { lang: localStorage.getItem('gg_lang'), group: null, scenario: null, story: null, lines: [], lineIdx: -1, storyDone: false, passes: [], picks: [], acks: [], record: null, photos: [], highlight: null, cars: [], phones: 0, selected: null, online: false, tab: startTab };
  var me = localStorage.getItem('gg_me') || '';
  function $(id) { return document.getElementById(id); }

  var TXT = {
    ko: {
      now: '지금', course: '코스', pick: '장소 고르기', record: '기록',
      telling: '차가 들려주는 이야기', coming: '곧 보여요', waiting: '명소에 가까워지면 여기에 영상과 3D 모형이 떠요',
      video: '영상', orbit: '3D 돌려 보기', report: '틀린 내용 신고', me: '내 이름 (장소를 고르면 차 화면에 같이 떠요)', mePh: '예: 민지',
      courseTitle: '오늘 코스', passed: '지남', nowAt: '지금', next: '다음',
      pickTitle: '다음에 갈 곳 고르기', pickHelp: '목록에서 고르거나 지도를 눌러 원하는 곳을 찍으세요. 운전자가 확인을 누르면 내비게이션에 바로 들어가요.',
      send1: '차 내비게이션으로 보내기', send2: '그룹 차 모두에 보내기', custom: '지도에서 고른 곳', sent: '보냈어요. 운전자 확인을 기다리는 중', acked: '내비게이션에 들어갔어요',
      recTitle: '지나온 길', photoTitle: '사진 붙이기', photoHelp: '사진을 올리면 찍은 시각에 맞춰 지나온 길 위에 붙어요.', photoBtn: '사진 올리기',
      camTitle: '빌트인 캠 하이라이트', camHelp: '차의 빌트인 캠(순정 블랙박스) 영상을 휴대폰으로 받아 올리면, 명소를 지난 장면만 골라 짧은 여행 영상으로 만들어요.', camBtn: '영상 올리기', making: '영상을 만드는 중이에요', made: '여행 영상이 나왔어요', down: '영상 내려받기', camFail: '영상을 만들지 못했어요',
      byOrder: '촬영 시각이 기록과 맞지 않아 지나온 순서대로 나눴어요', uploading: '올리는 중', joined: '여행 그룹에 들어왔어요', tripEnd: '오늘 여행이 끝났어요. 기록 탭에서 모아 볼 수 있어요',
      window: '창문이 조금 내려갔어요. 바깥 소리를 들어 보세요', reportAsk: '어떤 점이 틀렸나요?', reported: '신고했어요. 고맙습니다',
      sentFrom: '에서 보낸 장소', summary: '{km}km를 달려 명소 {n}곳을 지났어요', noRecord: '여행이 끝나면 지나온 길이 여기에 그려져요',
      kind: { cafe: '카페', parking: '주차장', landmark: '명소', sea: '바다', city: '도시', forest: '숲', place: '장소' }, cars: '대', phones: '명'
    },
    en: {
      now: 'Now', course: 'Course', pick: 'Pick a place', record: 'Record',
      telling: 'The car is telling', coming: 'Coming up', waiting: 'When the car nears a landmark, the video and 3D model appear here',
      video: 'Video', orbit: 'Rotate 3D', report: 'Report an error', me: 'Your name (shown in the car when you pick a place)', mePh: 'e.g. Alex',
      courseTitle: "Today's course", passed: 'Passed', nowAt: 'Now', next: 'Next',
      pickTitle: 'Pick where to go next', pickHelp: 'Choose from the list or tap the map. Once the driver confirms, it goes straight into the navigation.',
      send1: 'Send to the car navigation', send2: 'Send to every car in the group', custom: 'Spot picked on the map', sent: 'Sent. Waiting for the driver to confirm', acked: 'Added to the navigation',
      recTitle: 'Our route', photoTitle: 'Add photos', photoHelp: 'Photos are pinned on the route by the time they were taken.', photoBtn: 'Upload photos',
      camTitle: 'Built-in cam highlight', camHelp: "Upload the car's built-in cam video from your phone. We'll pick the moments you passed each landmark and make a short trip video.", camBtn: 'Upload video', making: 'Making your video', made: 'Your trip video is ready', down: 'Download video', camFail: 'Could not make the video',
      byOrder: "The video time didn't match the record, so it was split in route order", uploading: 'Uploading', joined: 'You joined the trip group', tripEnd: "Today's trip is over. See everything in the Record tab",
      window: 'The windows are down a little. Listen to the outside', reportAsk: 'What was wrong?', reported: 'Reported. Thank you',
      sentFrom: ' sent a place', summary: 'We drove {km} km and passed {n} places', noRecord: 'Your route appears here when the trip ends',
      kind: { cafe: 'Cafe', parking: 'Parking', landmark: 'Place', sea: 'Sea', city: 'City', forest: 'Forest', place: 'Place' }, cars: ' cars', phones: ' phones'
    }
  };
  function lang() { return S.lang || (S.group && S.group.lang) || 'ko'; }
  function t(k) { var d = TXT[lang()] || TXT.ko; return d[k] !== undefined ? d[k] : (TXT.ko[k] || k); }
  function nm(l10n) { if (!l10n) return ''; return l10n[lang()] || l10n.ko || l10n.en || ''; }
  function sentences(text) { return String(text || '').replace(/([.!?])\s+/g, '$1\u0001').split('\u0001').map(function (s) { return s.trim(); }).filter(Boolean); }
  function hhmm(ms) { var d = new Date(ms); return ('0' + d.getHours()).slice(-2) + ':' + ('0' + d.getMinutes()).slice(-2); }

  var toastTimer;
  function toast(msg) { var el = $('toast'); el.textContent = msg; el.classList.add('show'); clearTimeout(toastTimer); toastTimer = setTimeout(function () { el.classList.remove('show'); }, 3500); }

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
      if (tryNight && clipEl.getAttribute('src') === tryNight) { clipEl.src = '/clips/' + model + '.mp4'; return; }
      clipOk = false; showVideoMode(false); GG.setMode('cinematic');
    };
    clipEl.src = tryNight || ('/clips/' + model + '.mp4');
    clipEl.play && clipEl.play().catch(function () {});
  }
  function showVideoMode(on) {
    clipEl.classList.toggle('hidden', !on);
    GG.setPaused(on);
    $('mVideo').classList.toggle('on', on || GG.mode() === 'cinematic');
    $('mOrbit').classList.toggle('on', !on && GG.mode() === 'orbit');
  }
  GG.onMode = function (m) { if (m === 'orbit') showVideoMode(false); $('mVideo').classList.toggle('on', m === 'cinematic'); $('mOrbit').classList.toggle('on', m === 'orbit'); };
  $('mVideo').onclick = function () { if (clipOk && clipModel) showVideoMode(true); else GG.setMode('cinematic'); };
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
    Array.prototype.forEach.call(document.querySelectorAll('.lang button'), function (b) { b.classList.toggle('on', b.dataset.lang === lang()); });
    document.documentElement.lang = lang();
  }

  function renderTop() {
    var sc = S.scenario;
    $('title').textContent = sc ? nm(sc.title) : t('now');
    $('who').textContent = sc ? nm(sc.who) + (code ? '  #' + code : '') : '';
    $('cars').textContent = (S.cars.length || 0) + t('cars');
    $('liveDot').classList.toggle('on', S.online && S.cars.length > 0);
  }

  // ---------------------------------------------------------------- 지금
  function placeById(id) {
    var sc = S.scenario; if (!sc) return null;
    return sc.places.concat(sc.pickables || []).filter(function (p) { return p.id === id; })[0] || null;
  }

  function renderNow() {
    var st = S.story;
    if (!st) {
      $('nowLabel').textContent = '';
      $('nowName').textContent = '';
      $('lines').innerHTML = '<span class="small">' + t('waiting') + '</span>';
      $('nowSmall').textContent = '';
      return;
    }
    var place = placeById(st.placeId);
    $('nowLabel').textContent = (st.kind === 'announce' ? t('coming') : t('telling')) + (st.car ? '  ' + st.car : '');
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

  function showStory(m) {
    S.story = m; S.lines = sentences(m.text); S.lineIdx = 0; S.storyDone = false;
    var place = placeById(m.placeId);
    var model = m.model || (place && place.model) || 'generic';
    GG.show({ model: model, name: place ? nm(place.name) : m.name, rain: !!m.rain, night: !!m.night, mode: 'cinematic', caption: S.lines[0] || '' });
    setClip(model, !!m.night);
    showVideoMode(true);
    renderNow();
    if (S.tab !== 'pick') setTab('now');
    if (navigator.vibrate) navigator.vibrate(40);
  }

  $('report').onclick = function () {
    if (!S.story) return;
    var note = prompt(t('reportAsk'));
    if (!note) return;
    fetch('/api/groups/' + code + '/report', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ placeId: S.story.placeId, name: S.story.name, note: note, by: me }) })
      .then(function () { toast(t('reported')); });
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
    return L.divIcon({ className: '', iconSize: [18, 18], iconAnchor: [9, 9], html: '<div class="pin" style="background:' + color + '"></div>' + (label ? '<div class="pin-label" style="position:absolute;left:22px;top:-3px">' + escapeHtml(label) + '</div>' : '') });
  }
  var KIND_COLOR = { cafe: '#ffb86b', parking: '#7fe3ff', landmark: '#a78bfa', sea: '#00e5ff', city: '#ffd166', forest: '#4ade80', place: '#f472b6' };

  function drawPickMap() {
    var sc = S.scenario; if (!sc || !window.L) return;
    if (!pickMap) {
      pickMap = L.map('pickMap', { zoomControl: false, attributionControl: true });
      tiles(pickMap);
      pickLayer = L.layerGroup().addTo(pickMap);
      pickMap.on('click', function (e) {
        S.selected = { id: 'custom', name: t('custom'), lat: e.latlng.lat, lon: e.latlng.lng, kind: 'place' };
        if (customMarker) customMarker.remove();
        customMarker = L.marker(e.latlng, { icon: pin(KIND_COLOR.place, t('custom')) }).addTo(pickMap);
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
    var btn = $('sendPick');
    btn.disabled = !S.selected;
    btn.textContent = (S.cars.length > 1 ? t('send2') : t('send1')) + (S.selected ? ': ' + S.selected.name : '');
  }

  $('sendPick').onclick = function () {
    if (!S.selected || !S.ws || S.ws.readyState !== 1) return;
    S.ws.send(JSON.stringify({ type: 'pick', name: S.selected.name, lat: S.selected.lat, lon: S.selected.lon, kind: S.selected.kind, by: me || (lang() === 'en' ? 'Passenger' : '동승자') }));
    $('pickStatus').textContent = t('sent');
  };

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
      L.marker(pos, { icon: L.divIcon({ className: '', iconSize: [44, 44], iconAnchor: [22, 22], html: '<img src="' + ph.url + '" style="width:44px;height:44px;object-fit:cover;border-radius:10px;border:2px solid #fff;box-shadow:0 2px 8px rgba(0,0,0,.5)">' }) }).addTo(recLayer);
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
      return '<figure><img src="' + p.url + '" loading="lazy"><figcaption>' + hhmm(p.takenAt) + ' ' + escapeHtml(nearestPlaceName(p.takenAt)) + '</figcaption></figure>';
    }).join('');
  }

  function renderHighlight() {
    var h = S.highlight;
    var st = $('camStatus'), v = $('hl'), a = $('hlDown');
    if (!h) { st.textContent = ''; v.classList.add('hidden'); a.classList.add('hidden'); return; }
    if (h.status === 'processing') { st.textContent = t('making'); v.classList.add('hidden'); a.classList.add('hidden'); }
    else if (h.status === 'done') {
      st.textContent = t('made') + (h.mode === 'order' ? '. ' + t('byOrder') : '');
      if (v.getAttribute('src') !== h.url) v.src = h.url;
      v.classList.remove('hidden'); a.href = h.url; a.classList.remove('hidden');
    } else { st.textContent = t('camFail'); }
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

  $('photoInput').onchange = function () {
    var files = Array.prototype.slice.call(this.files || []);
    this.value = '';
    files.reduce(function (p, f) {
      return p.then(function () {
        toast(t('uploading') + ' ' + f.name);
        return Promise.all([exifTime(f), shrink(f)]).then(function (r) {
          if (!r[1]) return;
          return fetch('/api/groups/' + code + '/photos', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ dataUrl: r[1], takenAt: r[0] || f.lastModified, by: me, name: f.name }) });
        });
      });
    }, Promise.resolve());
  };

  $('camInput').onchange = function () {
    var f = (this.files || [])[0]; this.value = '';
    if (!f) return;
    var xhr = new XMLHttpRequest();
    xhr.open('POST', '/api/groups/' + code + '/dashcam?name=' + encodeURIComponent(f.name) + '&lastModified=' + f.lastModified);
    xhr.upload.onprogress = function (e) { if (e.lengthComputable) $('camStatus').textContent = t('uploading') + ' ' + Math.round(e.loaded / e.total * 100) + '%'; };
    xhr.onload = function () { $('camStatus').textContent = t('making'); };
    xhr.send(f);
  };

  // ---------------------------------------------------------------- 연결
  function apply(summary) {
    S.group = summary;
    S.cars = summary.cars || S.cars;
    S.passes = summary.passes || [];
    S.picks = summary.picks || [];
    S.acks = summary.acks || [];
    S.record = summary.record || null;
    S.photos = summary.photos || [];
    S.highlight = summary.highlight || null;
    if (summary.story && !S.story) {
      showStory(summary.story);
      if (summary.line) { S.lineIdx = summary.line.index; renderLines(); }
    }
  }

  function loadScenario(id) {
    return fetch('/api/scenarios/' + id).then(function (r) { return r.json(); }).then(function (sc) {
      S.scenario = sc;
      if (!S.story) {
        var first = sc.places.filter(function (p) { return p.model; })[0];
        if (first) GG.show({ model: first.model, name: '', caption: '', mode: 'cinematic' });
      }
      renderAll();
    });
  }

  function renderAll() {
    staticTexts(); renderTop(); renderNow(); renderCourse(); renderPickList();
    if (S.tab === 'pick') drawPickMap();
    if (S.tab === 'record') drawRecord();
  }

  var ws;
  function connect() {
    ws = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws?code=' + code + '&role=phone&name=' + encodeURIComponent(me));
    S.ws = ws;
    ws.onopen = function () { S.online = true; renderTop(); };
    ws.onclose = function () { S.online = false; renderTop(); setTimeout(connect, 2500); };
    ws.onmessage = function (ev) {
      var m; try { m = JSON.parse(ev.data); } catch (e) { return; }
      switch (m.type) {
        case 'hello':
          var first = !S.group;
          apply(m);
          if (!S.scenario || S.scenario.id !== m.scenarioId) loadScenario(m.scenarioId); else renderAll();
          if (first) toast(t('joined'));
          break;
        case 'members': S.cars = m.cars || []; S.phones = m.phones; renderTop(); renderPickList(); break;
        case 'trip': if (S.group) { S.group.lang = m.lang; } if (!S.scenario || S.scenario.id !== m.scenarioId) loadScenario(m.scenarioId); else renderAll(); break;
        case 'story': showStory(m); break;
        case 'line': if (S.story && m.placeId === S.story.placeId) { S.lineIdx = m.index; renderLines(); } break;
        case 'story_end': if (S.story && m.placeId === S.story.placeId) { S.storyDone = true; renderLines(); renderCourse(); } break;
        case 'pass': S.passes.push({ placeId: m.placeId, name: m.name, at: m.at, car: m.car }); renderCourse(); break;
        case 'pick': S.picks.push(m); if (m.fromCar) toast(m.car + t('sentFrom') + ': ' + m.name); break;
        case 'pick_ack': S.acks.push(m); toast((m.car || '') + ' ' + t('acked') + ': ' + m.name); $('pickStatus').textContent = t('acked') + ': ' + m.name; break;
        case 'window': toast(t('window')); break;
        case 'record': S.record = m.record; if (S.tab === 'record') drawRecord(); break;
        case 'photo': S.photos.push(m.photo); S.photos.sort(function (a, b) { return a.takenAt - b.takenAt; }); if (S.tab === 'record') drawRecord(); break;
        case 'highlight': S.highlight = m.highlight; renderHighlight(); break;
        case 'trip_end': toast(t('tripEnd')); break;
      }
    };
  }

  staticTexts();
  setTab(startTab);
  if (!code) { location.href = '/'; return; }
  fetch('/api/groups/' + code).then(function (r) { if (!r.ok) throw new Error('no group'); return r.json(); })
    .then(function (g) { apply(g); return loadScenario(g.scenarioId); })
    .then(connect)
    .catch(function () { $('lines').textContent = lang() === 'en' ? 'Group not found. Check the code on the car screen.' : '그룹을 찾지 못했어요. 차 화면의 번호를 확인해 주세요.'; });
})();
