/*
 * 여행 영상. 여행 기록(record)과 코스(scenario), 올린 사진으로 1분 안쪽 영상을 캔버스에 그린다.
 * 차 화면(WebView)과 휴대폰 웹 페이지가 이 파일을 같이 쓴다. 휴대폰에서는 MediaRecorder 로 영상 파일로 저장한다.
 * 음악은 파일 없이 WebAudio 로 그 자리에서 만든다.
 */
(function (root) {
  'use strict';

  var INK = '#12233F', PAPER = '#F8F4E8', SAND = '#EEE6D2', SKY = '#3F9BDD', GOLD = '#F2B632', SUB = '#5B6577', WHITE = '#FFFFFF';
  var SANS = '"Asta Sans","Apple SD Gothic Neo","Noto Sans KR","Malgun Gothic",sans-serif';
  var PEN = '"RG Pen","Nanum Pen Script","Apple SD Gothic Neo",sans-serif';

  var TEXT = {
    ko: {
      today: '오늘의 여행', first: '차가 건넨 첫마디', found: '찾았다!', ours: '우리가 찍은 사진', go: '출발!', by: function (n) { return n + ' 찍음'; },
      km: '달린 길', stops: '들른 곳', stickers: '모은 스티커', photos: '찍은 사진', unitStop: '곳', unitSheet: '장',
      captain: function (n) { return '오늘의 탐험대장 ' + n; }, brand: '구르는 가이드',
      date: function (d) { return d.getFullYear() + '년 ' + (d.getMonth() + 1) + '월 ' + d.getDate() + '일 ' + '일월화수목금토'.charAt(d.getDay()) + '요일'; }
    },
    en: {
      today: "Today's trip", first: 'The first thing the car said', found: 'Found it!', ours: 'Photos we took', go: 'Off we go!', by: function (n) { return 'by ' + n; },
      km: 'driven', stops: 'stops', stickers: 'stickers', photos: 'photos', unitStop: '', unitSheet: '',
      captain: function (n) { return "Today's explorer: " + n; }, brand: 'Rolling Guide',
      date: function (d) { return d.toLocaleDateString('en-US', { weekday: 'long', month: 'long', day: 'numeric', year: 'numeric' }); }
    }
  };

  // ------------------------------------------------------------------ 데이터 모으기

  function pick(l10n, lang) { return l10n ? (l10n[lang] || l10n.ko || l10n.en || '') : ''; }
  function distM(a, b) {
    var R = 6371000, toR = Math.PI / 180;
    var x = (b[1] - a[1]) * toR * Math.cos((a[0] + b[0]) / 2 * toR), y = (b[0] - a[0]) * toR;
    return Math.sqrt(x * x + y * y) * R;
  }

  /**
   * record: 차가 올린 여행 기록 { startedAt, distanceM, route: [[t, lat, lon]...], events: [{ t, type, placeId, title, detail }] }
   * scenario: 코스 JSON. photos: 올린 사진 [{ url, by, takenAt }]. opt: { lang, base }
   */
  function build(record, scenario, photos, opt) {
    opt = opt || {};
    var lang = opt.lang || record.lang || 'ko';
    var base = opt.base == null ? '../' : opt.base;
    var places = {};
    (scenario.places || []).concat(scenario.sights || []).forEach(function (p) { places[p.id] = p; });
    var route = (record.route || []).map(function (r) { return [r[1], r[2]]; });
    // 기록한 길이 너무 짧으면(출발과 도착 근처를 지운 뒤 남은 것이 없을 때) 코스의 길을 대신 그림
    if (route.length < 8 && scenario.route) route = scenario.route.slice();
    var found = {}, order = [], asked = 0;
    (record.events || []).forEach(function (e) {
      if (e.type === 'found' && e.placeId) found[e.placeId] = e.detail || true;
      if (e.type === 'pass' && e.placeId && order.indexOf(e.placeId) < 0) order.push(e.placeId);
      if (e.type === 'qa') asked++;
    });
    var stops = order.map(function (id) { return places[id]; }).filter(function (p) { return p && p.kind !== 'city'; }).map(function (p) {
      var st = (scenario.stickers || {})[p.id];
      return {
        id: p.id, name: pick(p.name, lang), pos: [p.lat, p.lon],
        photo: (opt.photoFor && opt.photoFor(p.id)) || (base + 'photos/' + p.id + '_1.jpg'),
        sticker: st ? base + 'stickers/' + st + '.png' : null,
        found: !!found[p.id], foundBy: typeof found[p.id] === 'string' ? found[p.id] : '',
        tagline: pick((scenario.tagline || {})[p.id], lang)
      };
    });
    var names = stops.map(function (s) { return s.foundBy; }).filter(Boolean);
    var captain = names.sort(function (a, b) { return names.filter(function (n) { return n === b; }).length - names.filter(function (n) { return n === a; }).length; })[0] || '';
    var shots = (photos || []).slice().sort(function (a, b) { return (a.takenAt || 0) - (b.takenAt || 0); });
    // 사진이 많으면 고르게 여섯 장만
    if (shots.length > 6) { var keep = []; for (var i = 0; i < 6; i++) keep.push(shots[Math.round(i * (shots.length - 1) / 5)]); shots = keep; }
    return {
      lang: lang, title: pick(scenario.title, lang), intro: pick(scenario.reelIntro, lang),
      date: TEXT[lang === 'en' ? 'en' : 'ko'].date(new Date(record.startedAt || Date.now())),
      km: Math.round((record.distanceM || 0) / 100) / 10, route: route, stops: stops,
      photos: shots.map(function (p) { return { url: p.url, by: p.by || '' }; }),
      stickerCount: stops.filter(function (s) { return s.found && s.sticker; }).length, captain: captain
    };
  }

  // ------------------------------------------------------------------ 그리기 도구

  function clamp(v, a, b) { return Math.max(a, Math.min(b, v)); }
  function outCubic(t) { t = clamp(t, 0, 1); return 1 - Math.pow(1 - t, 3); }
  function inOut(t) { t = clamp(t, 0, 1); return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2; }
  function outBack(t) { t = clamp(t, 0, 1); var c1 = 1.70158, c3 = c1 + 1; return 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2); }
  function seg(t, a, b) { return clamp((t - a) / (b - a), 0, 1); }
  function rnd(i) { var x = Math.sin(i * 127.1 + 311.7) * 43758.5453; return x - Math.floor(x); }

  function loadImage(src) {
    return new Promise(function (res) {
      if (!src) return res(null);
      var im = new Image();
      var done = false, t = setTimeout(function () { if (!done) { done = true; res(null); } }, 8000);
      im.onload = function () { if (!done) { done = true; clearTimeout(t); res(im); } };
      im.onerror = function () { if (!done) { done = true; clearTimeout(t); res(null); } };
      im.src = src;
    });
  }

  function rr(ctx, x, y, w, h, r) {
    r = Math.min(r, w / 2, h / 2);
    ctx.beginPath(); ctx.moveTo(x + r, y); ctx.arcTo(x + w, y, x + w, y + h, r); ctx.arcTo(x + w, y + h, x, y + h, r); ctx.arcTo(x, y + h, x, y, r); ctx.arcTo(x, y, x + w, y, r); ctx.closePath();
  }
  /** 사진을 칸에 꽉 차게(넘치는 쪽은 잘라) 그림. zoom 으로 천천히 다가감 */
  function cover(ctx, im, x, y, w, h, zoom) {
    if (!im) { ctx.fillStyle = SAND; ctx.fillRect(x, y, w, h); return; }
    var s = Math.max(w / im.width, h / im.height) * (zoom || 1);
    var dw = im.width * s, dh = im.height * s;
    ctx.save(); ctx.beginPath(); ctx.rect(x, y, w, h); ctx.clip();
    ctx.drawImage(im, x + (w - dw) / 2, y + (h - dh) / 2, dw, dh);
    ctx.restore();
  }
  function contain(ctx, im, cx, cy, maxW, maxH, scale, rot, alpha) {
    if (!im) return;
    var s = Math.min(maxW / im.width, maxH / im.height) * (scale == null ? 1 : scale);
    ctx.save(); ctx.globalAlpha *= (alpha == null ? 1 : alpha); ctx.translate(cx, cy); ctx.rotate(rot || 0);
    ctx.drawImage(im, -im.width * s / 2, -im.height * s / 2, im.width * s, im.height * s);
    ctx.restore();
  }
  function font(ctx, weight, px, family) { ctx.font = weight + ' ' + Math.round(px) + 'px ' + family; }
  /** 칸 너비에 맞게 글자를 줄여 한 줄로 그림 */
  function fit(ctx, s, x, y, maxW, weight, px, family, color, align) {
    var size = px;
    font(ctx, weight, size, family);
    while (ctx.measureText(s).width > maxW && size > px * 0.5) { size -= 2; font(ctx, weight, size, family); }
    ctx.fillStyle = color; ctx.textAlign = align || 'left'; ctx.textBaseline = 'alphabetic';
    ctx.fillText(s, x, y);
    return size;
  }
  function chip(ctx, s, cx, y, u, bg, fg) {
    font(ctx, 800, 26 * u, SANS);
    var w = ctx.measureText(s).width + 36 * u, h = 46 * u;
    ctx.fillStyle = bg; rr(ctx, cx - w / 2, y, w, h, h / 2); ctx.fill();
    ctx.fillStyle = fg; ctx.textAlign = 'center'; ctx.textBaseline = 'middle'; ctx.fillText(s, cx, y + h / 2 + 1 * u);
    ctx.textBaseline = 'alphabetic';
  }

  // ------------------------------------------------------------------ 길(지도) 준비

  function prepareMap(model) {
    var r = model.route, n = r.length;
    if (n < 2) return null;
    var lat0 = 0; r.forEach(function (p) { lat0 += p[0]; }); lat0 /= n;
    var k = Math.cos(lat0 * Math.PI / 180);
    var pts = r.map(function (p) { return [p[1] * k, -p[0]]; });
    var box = [Infinity, Infinity, -Infinity, -Infinity];
    pts.forEach(function (p) { box[0] = Math.min(box[0], p[0]); box[1] = Math.min(box[1], p[1]); box[2] = Math.max(box[2], p[0]); box[3] = Math.max(box[3], p[1]); });
    var cum = [0];
    for (var i = 1; i < n; i++) cum.push(cum[i - 1] + distM(r[i - 1], r[i]));
    var total = cum[n - 1] || 1;
    // 명소를 길 위 가장 가까운 점에 붙임
    model.stops.forEach(function (s) {
      var best = 0, bd = Infinity;
      for (var j = 0; j < n; j++) { var d = distM(r[j], s.pos); if (d < bd) { bd = d; best = j; } }
      s.at = cum[best] / total;
    });
    model.stops.sort(function (a, b) { return a.at - b.at; });
    return { pts: pts, cum: cum, total: total, box: box };
  }
  function mapXY(map, rect, p) {
    var bw = (map.box[2] - map.box[0]) || 1e-9, bh = (map.box[3] - map.box[1]) || 1e-9;
    var s = Math.min(rect.w / bw, rect.h / bh);
    return [rect.x + (rect.w - bw * s) / 2 + (p[0] - map.box[0]) * s, rect.y + (rect.h - bh * s) / 2 + (p[1] - map.box[1]) * s];
  }
  function pointAt(map, f) {
    var d = clamp(f, 0, 1) * map.total, c = map.cum, lo = 0, hi = c.length - 1;
    while (hi - lo > 1) { var mid = (lo + hi) >> 1; if (c[mid] <= d) lo = mid; else hi = mid; }
    var span = (c[hi] - c[lo]) || 1, t = (d - c[lo]) / span, a = map.pts[lo], b = map.pts[hi];
    return { p: [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t], i: lo };
  }

  function timeline(model, hasMap) {
    var sc = [{ k: 'title', d: 3.6 }];
    if (model.intro) sc.push({ k: 'intro', d: 2.8 });
    model.stops.forEach(function (s, i) { sc.push({ k: 'stop', d: 7.0, i: i }); });
    if (hasMap) sc.push({ k: 'arrive', d: model.stops.length ? 1.6 : 4.0 });
    model.photos.forEach(function (p, i) { sc.push({ k: 'photo', d: 3.0, i: i }); });
    sc.push({ k: 'end', d: 7.0 });
    var t = 0; sc.forEach(function (s) { s.t0 = t; t += s.d; });
    return { scenes: sc, total: t };
  }
  var TRAVEL = 1.8;

  // ------------------------------------------------------------------ 음악과 효과음 (WebAudio)

  function makeAudio(total, cues) {
    var AC = root.AudioContext || root.webkitAudioContext;
    if (!AC) return null;
    var ac = new AC();
    var master = ac.createGain(); master.gain.value = 0.9;
    var lp = ac.createBiquadFilter(); lp.type = 'lowpass'; lp.frequency.value = 3400;
    var delay = ac.createDelay(1.0), fb = ac.createGain(), wet = ac.createGain();
    delay.delayTime.value = 0.49; fb.gain.value = 0.26; wet.gain.value = 0.22;
    lp.connect(master); lp.connect(delay); delay.connect(fb); fb.connect(delay); delay.connect(wet); wet.connect(master);
    master.connect(ac.destination);
    var dest = ac.createMediaStreamDestination ? ac.createMediaStreamDestination() : null;
    if (dest) master.connect(dest);
    function note(freq, when, len, vol, type) {
      var o = ac.createOscillator(), g = ac.createGain();
      o.type = type || 'triangle'; o.frequency.value = freq;
      g.gain.setValueAtTime(0.0001, when); g.gain.exponentialRampToValueAtTime(vol, when + 0.012); g.gain.exponentialRampToValueAtTime(0.0001, when + len);
      o.connect(g); g.connect(lp); o.start(when); o.stop(when + len + 0.05);
    }
    function hz(m) { return 440 * Math.pow(2, (m - 69) / 12); }
    // 다장조 네 마디를 돌림: C, G, Am, F. 여덟 번 나눠 뜯는 오르골 소리와 낮은 음 하나
    var chords = [[60, 64, 67], [59, 62, 67], [57, 60, 64], [57, 60, 65]], bass = [36, 43, 45, 41];
    var beat = 60 / 92, step = beat / 2;
    function schedule(t0) {
      var bars = Math.max(1, Math.floor((total - 4.2) / (beat * 4)));
      for (var b = 0; b < bars; b++) {
        var c = chords[b % 4], at = t0 + b * beat * 4;
        var pat = [c[0], c[2], c[1] + 12, c[2], c[0] + 12, c[2], c[1] + 12, c[2]];
        for (var s = 0; s < 8; s++) {
          note(hz(pat[s]), at + s * step, 1.1, s % 4 === 0 ? 0.15 : 0.1, 'triangle');
          if (s % 2 === 0) note(hz(pat[s] + 12), at + s * step, 0.7, 0.03, 'sine');
        }
        note(hz(bass[b % 4]), at, beat * 3.6, 0.16, 'sine');
      }
      // 끝맺음: 으뜸화음을 위로 펼치고 길게 울림
      var end = t0 + bars * beat * 4;
      [48, 60, 64, 67, 72, 76].forEach(function (m, i) { note(hz(m), end + i * 0.11, 3.6, i === 0 ? 0.18 : 0.12, i === 0 ? 'sine' : 'triangle'); });
      (cues || []).forEach(function (q) {
        var w = t0 + q.t;
        if (q.k === 'pop') { note(660, w, 0.16, 0.12, 'sine'); note(990, w + 0.06, 0.18, 0.09, 'sine'); }
        if (q.k === 'ding') { note(1318.5, w, 1.0, 0.16, 'sine'); note(1760, w + 0.09, 1.2, 0.13, 'sine'); note(2637, w + 0.18, 0.9, 0.05, 'sine'); }
      });
      master.gain.setValueAtTime(0.9, t0 + Math.max(0, total - 0.9)); master.gain.linearRampToValueAtTime(0.0001, t0 + total + 0.2);
    }
    return { ac: ac, dest: dest, schedule: schedule, close: function () { try { ac.close(); } catch (e) {} } };
  }

  // ------------------------------------------------------------------ 재생기

  function makeNoise() {
    var c = document.createElement('canvas'); c.width = c.height = 200;
    var x = c.getContext('2d'), id = x.createImageData(200, 200), d = id.data;
    for (var i = 0; i < 200 * 200; i++) { var v = rnd(i) > 0.5 ? 255 : 90; d[i * 4] = d[i * 4 + 1] = d[i * 4 + 2] = v; d[i * 4 + 3] = Math.floor(rnd(i + 0.37) * 13); }
    x.putImageData(id, 0, 0);
    return c;
  }
  function fontsReady() {
    if (!document.fonts || !document.fonts.load) return Promise.resolve();
    var wait = new Promise(function (res) { setTimeout(res, 2500); });
    return Promise.race([Promise.all([document.fonts.load('40px "RG Pen"', '가'), document.fonts.load('800 40px "Asta Sans"', '가')]).catch(function () {}), wait]);
  }

  function create(canvas, model, opt) {
    opt = opt || {};
    var ctx = canvas.getContext('2d');
    var L = TEXT[model.lang === 'en' ? 'en' : 'ko'];
    var map = prepareMap(model);
    var tl = timeline(model, !!map);
    var img = { stop: [], sticker: [], photo: [] };
    var noise = makeNoise(), pattern = null;
    var W = 0, H = 0, u = 1, land = true;
    var ready = Promise.all([
      Promise.all(model.stops.map(function (s) { return loadImage(s.photo); })).then(function (a) { img.stop = a; }),
      Promise.all(model.stops.map(function (s) { return loadImage(s.sticker); })).then(function (a) { img.sticker = a; }),
      Promise.all(model.photos.map(function (p) { return loadImage(p.url); })).then(function (a) { img.photo = a; }),
      fontsReady()
    ]);
    var cues = [];
    tl.scenes.forEach(function (s) {
      if (s.k === 'stop') { cues.push({ t: s.t0 + TRAVEL, k: 'pop' }); if (model.stops[s.i].found) cues.push({ t: s.t0 + TRAVEL + 1.8, k: 'ding' }); }
      if (s.k === 'photo') cues.push({ t: s.t0, k: 'pop' });
      if (s.k === 'end' && model.stickerCount) cues.push({ t: s.t0 + 2.0, k: 'ding' });
    });

    function paper() {
      ctx.globalAlpha = 1; ctx.fillStyle = PAPER; ctx.fillRect(0, 0, W, H);
      if (!pattern) pattern = ctx.createPattern(noise, 'repeat');
      ctx.fillStyle = pattern; ctx.fillRect(0, 0, W, H);
    }
    function areas() {
      var m = 36 * u;
      if (!map) return { map: null, card: { x: W * (land ? 0.2 : 0) + m, y: m, w: W * (land ? 0.6 : 1) - m * 2, h: H - m * 2 } };
      if (land) { var mw = (W - m * 3) * 0.5; return { map: { x: m, y: m, w: mw, h: H - m * 2 }, card: { x: m * 2 + mw, y: m, w: W - m * 3 - mw, h: H - m * 2 } }; }
      var mh = (H - m * 3) * 0.36;
      return { map: { x: m, y: m, w: W - m * 2, h: mh }, card: { x: m, y: m * 2 + mh, w: W - m * 2, h: H - m * 3 - mh } };
    }
    function routePath(inner, f) {
      var end = pointAt(map, f), q;
      ctx.beginPath();
      for (var i = 0; i <= end.i; i++) { q = mapXY(map, inner, map.pts[i]); if (i === 0) ctx.moveTo(q[0], q[1]); else ctx.lineTo(q[0], q[1]); }
      q = mapXY(map, inner, end.p); ctx.lineTo(q[0], q[1]);
    }
    function drawMap(r, prog, upto) {
      ctx.fillStyle = SAND; rr(ctx, r.x, r.y, r.w, r.h, 30 * u); ctx.fill();
      var pad = 60 * u, inner = { x: r.x + pad, y: r.y + pad * 1.9, w: r.w - pad * 2, h: r.h - pad * 2.9 };
      // 지금까지 달린 길
      font(ctx, 800, 30 * u, SANS); ctx.fillStyle = INK; ctx.textAlign = 'left'; ctx.textBaseline = 'alphabetic';
      ctx.fillText((model.km * prog).toFixed(1) + 'km', r.x + 34 * u, r.y + 58 * u);
      ctx.lineJoin = ctx.lineCap = 'round';
      routePath(inner, 1); ctx.strokeStyle = WHITE; ctx.lineWidth = 17 * u; ctx.stroke();
      routePath(inner, prog); ctx.strokeStyle = SKY; ctx.lineWidth = 9 * u; ctx.stroke();
      model.stops.forEach(function (s, j) {
        var q = mapXY(map, inner, pointAt(map, s.at).p), seen = j < upto;
        ctx.beginPath(); ctx.arc(q[0], q[1], 15 * u, 0, Math.PI * 2);
        ctx.fillStyle = seen ? (s.found ? GOLD : SKY) : WHITE; ctx.fill();
        ctx.strokeStyle = INK; ctx.lineWidth = 3.5 * u; ctx.stroke();
        if (seen) {
          font(ctx, 800, 25 * u, SANS); ctx.textAlign = 'center'; ctx.textBaseline = 'alphabetic';
          // 가까이 붙은 곳끼리 이름이 겹치지 않게 번갈아 위아래에 적음
          var tw = ctx.measureText(s.name).width / 2 + 8 * u, tx = clamp(q[0], r.x + tw, r.x + r.w - tw), ty = j % 2 ? q[1] + 46 * u : q[1] - 26 * u;
          ctx.lineWidth = 8 * u; ctx.strokeStyle = SAND; ctx.strokeText(s.name, tx, ty);
          ctx.fillStyle = INK; ctx.fillText(s.name, tx, ty);
        }
      });
      var c = mapXY(map, inner, pointAt(map, prog).p);
      ctx.beginPath(); ctx.arc(c[0], c[1], 16 * u, 0, Math.PI * 2); ctx.fillStyle = WHITE; ctx.fill();
      ctx.beginPath(); ctx.arc(c[0], c[1], 10 * u, 0, Math.PI * 2); ctx.fillStyle = INK; ctx.fill();
    }
    function polaroid(x, y, w, h, fr, lab) {
      ctx.save(); ctx.shadowColor = 'rgba(18,35,63,.22)'; ctx.shadowBlur = 28 * u; ctx.shadowOffsetY = 10 * u;
      ctx.fillStyle = WHITE; rr(ctx, x - fr, y - fr, w + fr * 2, h + fr + lab, 10 * u); ctx.fill(); ctx.restore();
    }
    function drawCard(c, i, a, slide, td) {
      var s = model.stops[i]; if (!s || a <= 0) return;
      ctx.save(); ctx.globalAlpha = a; ctx.translate(land ? slide : 0, land ? 0 : slide);
      var fr = 18 * u, lab = 88 * u;
      // 사진틀은 칸 너비와, 아래에 한 줄과 [찾았다] 표시가 들어갈 높이를 함께 보고 정함
      var pw = Math.min(c.w * 0.86, Math.max(220 * u, (c.h - lab - 230 * u) / 0.72)), ph = pw * 0.72;
      var px = c.x + (c.w - pw) / 2, py = c.y + 34 * u, mx = px + pw / 2, my = py + (ph + lab) / 2;
      ctx.save(); ctx.translate(mx, my); ctx.rotate((i % 2 ? 1 : -1) * 0.022); ctx.translate(-mx, -my);
      polaroid(px, py, pw, ph, fr, lab);
      cover(ctx, img.stop[i], px, py, pw, ph, 1 + 0.07 * clamp(td / 5.2, 0, 1));
      fit(ctx, s.name, mx, py + ph + lab * 0.64, pw - 24 * u, 800, 46 * u, SANS, INK, 'center');
      if (s.found && img.sticker[i] && td >= 1.8) contain(ctx, img.sticker[i], px + pw - 70 * u, py + 26 * u, 200 * u, 170 * u, 0.4 + 0.6 * outBack(seg(td, 1.8, 2.35)), 0.14, seg(td, 1.8, 2.0));
      ctx.restore();
      var ty = py + ph + fr + lab + 80 * u;
      if (s.tagline) { ctx.globalAlpha = a * seg(td, 0.9, 1.4); fit(ctx, s.tagline, c.x + c.w / 2, ty, c.w * 0.96, 400, 60 * u, PEN, INK, 'center'); }
      if (s.found) { ctx.globalAlpha = a * seg(td, 2.1, 2.5); chip(ctx, L.found + (s.foundBy ? '  ' + s.foundBy : ''), c.x + c.w / 2, ty + 28 * u, u, GOLD, INK); }
      ctx.restore();
    }

    function sTitle(lt) {
      ctx.globalAlpha = seg(lt, 0.1, 0.5); chip(ctx, L.today, W / 2, H * 0.2, u, INK, WHITE);
      var e = outCubic(seg(lt, 0.3, 1.0));
      ctx.globalAlpha = e; fit(ctx, model.title, W / 2, H * 0.43 + (1 - e) * 22 * u, W * 0.86, 800, 98 * u, SANS, INK, 'center');
      ctx.globalAlpha = seg(lt, 0.7, 1.2); fit(ctx, model.date, W / 2, H * 0.43 + 80 * u, W * 0.84, 400, 58 * u, PEN, SUB, 'center');
      // 길이 한 줄 그려짐
      var p = outCubic(seg(lt, 0.9, 2.7)), x0 = W * 0.24, x1 = W * 0.76, y0 = H * 0.66, n = 60, hx = x0, hy = y0;
      ctx.globalAlpha = 1; ctx.lineCap = ctx.lineJoin = 'round'; ctx.strokeStyle = SKY; ctx.lineWidth = 8 * u; ctx.beginPath();
      for (var i = 0; i <= Math.round(n * p); i++) { hx = x0 + (x1 - x0) * i / n; hy = y0 + Math.sin(i / n * Math.PI * 2) * 20 * u; if (i === 0) ctx.moveTo(hx, hy); else ctx.lineTo(hx, hy); }
      if (p > 0) { ctx.stroke(); ctx.beginPath(); ctx.arc(hx, hy, 15 * u, 0, Math.PI * 2); ctx.fillStyle = WHITE; ctx.fill(); ctx.beginPath(); ctx.arc(hx, hy, 9 * u, 0, Math.PI * 2); ctx.fillStyle = INK; ctx.fill(); }
      var idx = []; model.stops.forEach(function (s, i) { if (img.sticker[i]) idx.push(i); }); idx = idx.slice(0, 4);
      idx.forEach(function (si, j) {
        var k = outBack(seg(lt, 1.7 + 0.22 * j, 2.2 + 0.22 * j)); if (k <= 0) return;
        contain(ctx, img.sticker[si], W / 2 + (j - (idx.length - 1) / 2) * 190 * u, H * 0.85, 170 * u, 140 * u, k, (j % 2 ? 1 : -1) * 0.08, 1);
      });
    }
    function sIntro(lt) {
      ctx.globalAlpha = seg(lt, 0, 0.4); chip(ctx, L.first, W / 2, H * 0.3, u, INK, WHITE);
      var full = '“' + model.intro + '”', size = 116 * u;
      font(ctx, 400, size, PEN);
      while (ctx.measureText(full).width > W * 0.86 && size > 40 * u) { size -= 2; font(ctx, 400, size, PEN); }
      var x0 = W / 2 - ctx.measureText(full).width / 2, n = Math.ceil(full.length * seg(lt, 0.3, 1.5));
      ctx.globalAlpha = 1; ctx.fillStyle = INK; ctx.textAlign = 'left'; ctx.textBaseline = 'alphabetic';
      ctx.fillText(full.slice(0, n), x0, H * 0.3 + 190 * u);
    }
    function sStop(sc, lt) {
      var A = areas(), n = model.stops.length;
      if (sc.k === 'arrive') {
        var from = n ? model.stops[n - 1].at : 0;
        drawMap(A.map, from + (1 - from) * inOut(seg(lt, 0, sc.d - 0.3)), n);
        if (n) drawCard(A.card, n - 1, 1 - seg(lt, 0, 0.5), 0, 5.2);
        return;
      }
      var i = sc.i, prev = i > 0 ? model.stops[i - 1].at : 0;
      if (map) drawMap(A.map, prev + (model.stops[i].at - prev) * inOut(seg(lt, 0, TRAVEL)), lt >= TRAVEL ? i + 1 : i);
      // 다음 곳으로 달리는 동안에는 앞 카드를 그대로 두었다가 닿기 직전에 거둠. 첫 구간에는 한마디만
      if (lt < TRAVEL) {
        if (i > 0) drawCard(A.card, i - 1, 1 - seg(lt, TRAVEL - 0.45, TRAVEL - 0.05), 0, 5.2);
        else { ctx.globalAlpha = seg(lt, 0.1, 0.5) * (1 - seg(lt, TRAVEL - 0.4, TRAVEL)); fit(ctx, L.go, A.card.x + A.card.w / 2, A.card.y + A.card.h * 0.45, A.card.w * 0.8, 400, 120 * u, PEN, INK, 'center'); ctx.globalAlpha = 1; }
        return;
      }
      var td = lt - TRAVEL, e = outCubic(seg(td, 0, 0.55));
      drawCard(A.card, i, e, (1 - e) * 90 * u, td);
    }
    function sPhoto(sc, lt) {
      ctx.globalAlpha = 1; chip(ctx, L.ours, W / 2, H * 0.06, u, INK, WHITE);
      var fr = 20 * u, lab = 86 * u, pw = Math.min(H * 0.6 * 1.3, W * 0.78), ph = pw * 0.75, cx = W / 2, cy = H * 0.54;
      for (var j = Math.max(0, sc.i - 2); j <= sc.i; j++) {
        var cur = j === sc.i, e = cur ? outCubic(seg(lt, 0, 0.5)) : 1, top = -(ph + fr + lab) / 2;
        ctx.save(); ctx.globalAlpha = e; ctx.translate(cx + (rnd(j + 7) - 0.5) * 44 * u, cy + (rnd(j + 11) - 0.5) * 26 * u); ctx.rotate((rnd(j + 3) - 0.5) * 0.16);
        var z = 1 + (1 - e) * 0.18; ctx.scale(z, z);
        polaroid(-pw / 2, top + fr, pw, ph, fr, lab);
        cover(ctx, img.photo[j], -pw / 2, top + fr, pw, ph, 1 + (cur ? 0.05 * seg(lt, 0, 3) : 0.05));
        var by = model.photos[j].by;
        if (by) fit(ctx, L.by(by), 0, top + fr + ph + lab * 0.66, pw - 30 * u, 400, 52 * u, PEN, INK, 'center');
        ctx.restore();
      }
    }
    function sEnd(lt) {
      // 위에서부터 차례로 쌓고 남는 높이는 위아래로 나눔 (가로로 긴 화면, 세로로 긴 화면, 네모에 가까운 화면 모두)
      var gap = Math.min(1.35, Math.max(1, 1 + (H / u - 720) / 600)), hasRow = model.stickerCount > 0 || model.stops.length > 0;
      var rel = [0, 132, 190, 190 + 150 * gap, 190 + 150 * gap + 46 + 120 * gap, 190 + 150 * gap + 46 + 120 * gap + 130 * gap];
      var block = (model.captain ? rel[5] + 20 : hasRow ? rel[4] + 90 : rel[3] + 60) * u, off = Math.max(24 * u, (H - block) / 2 - 10 * u);
      var y = rel.map(function (v) { return (off + v * u) / H; });
      ctx.globalAlpha = seg(lt, 0, 0.4); chip(ctx, L.today, W / 2, H * y[0], u, INK, WHITE);
      ctx.globalAlpha = seg(lt, 0.2, 0.7); fit(ctx, model.title, W / 2, H * y[1], W * 0.86, 800, 74 * u, SANS, INK, 'center');
      fit(ctx, model.date, W / 2, H * y[2], W * 0.84, 400, 48 * u, PEN, SUB, 'center');
      var list = [{ v: model.km, unit: 'km', label: L.km, dec: 1 }, { v: model.stops.length, unit: L.unitStop, label: L.stops }];
      if (model.stickerCount) list.push({ v: model.stickerCount, unit: L.unitSheet, label: L.stickers });
      else if (model.photos.length) list.push({ v: model.photos.length, unit: L.unitSheet, label: L.photos });
      var colW = W * (land ? 0.72 : 0.9) / list.length, left = (W - colW * list.length) / 2;
      list.forEach(function (s, j) {
        var k = outCubic(seg(lt, 0.7 + 0.15 * j, 1.9 + 0.15 * j)), x = left + colW * (j + 0.5);
        ctx.globalAlpha = seg(lt, 0.6 + 0.15 * j, 1.0 + 0.15 * j);
        fit(ctx, (s.dec ? (s.v * k).toFixed(1) : String(Math.round(s.v * k))) + s.unit, x, H * y[3], colW - 16 * u, 800, 88 * u, SANS, INK, 'center');
        fit(ctx, s.label, x, H * y[3] + 46 * u, colW - 16 * u, 600, 30 * u, SANS, SUB, 'center');
      });
      var got = []; model.stops.forEach(function (s, i) { if (s.found && img.sticker[i]) got.push(i); }); got = got.slice(0, 5);
      ctx.globalAlpha = 1;
      got.forEach(function (si, j) {
        var k = outBack(seg(lt, 2.0 + 0.3 * j, 2.5 + 0.3 * j)); if (k <= 0) return;
        contain(ctx, img.sticker[si], W / 2 + (j - (got.length - 1) / 2) * (land ? 200 : 170) * u, H * y[4], 180 * u, 150 * u, k, (j % 2 ? 1 : -1) * 0.07, 1);
      });
      // 모은 스티커가 없으면 들른 곳 사진을 동그랗게 늘어놓음
      if (!got.length) model.stops.slice(0, 4).forEach(function (s, j, arr) {
        var k = outBack(seg(lt, 2.0 + 0.25 * j, 2.5 + 0.25 * j)); if (k <= 0 || !img.stop[j]) return;
        var cx = W / 2 + (j - (arr.length - 1) / 2) * (land ? 190 : 165) * u, cy = H * y[4], rad = 72 * u * k;
        ctx.save(); ctx.beginPath(); ctx.arc(cx, cy, rad + 7 * u, 0, Math.PI * 2); ctx.fillStyle = WHITE; ctx.shadowColor = 'rgba(18,35,63,.2)'; ctx.shadowBlur = 18 * u; ctx.shadowOffsetY = 6 * u; ctx.fill(); ctx.restore();
        ctx.save(); ctx.beginPath(); ctx.arc(cx, cy, rad, 0, Math.PI * 2); ctx.clip(); cover(ctx, img.stop[j], cx - rad, cy - rad, rad * 2, rad * 2, 1); ctx.restore();
      });
      if (model.captain) { ctx.globalAlpha = seg(lt, 3.2, 3.8); fit(ctx, L.captain(model.captain), W / 2, H * y[5], W * 0.86, 400, 58 * u, PEN, INK, 'center'); }
      ctx.globalAlpha = 0.75 * seg(lt, 0.4, 1.0); fit(ctx, L.brand, W - 30 * u, H - 24 * u, W * 0.4, 700, 22 * u, SANS, SUB, 'right');
    }

    function draw(t) {
      // 네모에 가까운 화면(차 화면의 영상 칸)은 가로 배치로: 왼쪽에 길, 오른쪽에 사진
      W = canvas.width; H = canvas.height; u = Math.min(W, H) / 720; land = W >= H * 0.8;
      t = clamp(t, 0, tl.total - 0.001);
      var idx = 0; tl.scenes.forEach(function (s, i) { if (t >= s.t0) idx = i; });
      var sc = tl.scenes[idx], lt = t - sc.t0, prev = tl.scenes[idx - 1];
      ctx.save(); paper();
      if (sc.k === 'title') sTitle(lt); else if (sc.k === 'intro') sIntro(lt); else if (sc.k === 'stop' || sc.k === 'arrive') sStop(sc, lt); else if (sc.k === 'photo') sPhoto(sc, lt); else sEnd(lt);
      ctx.restore();
      // 다른 갈래의 장면으로 넘어갈 때는 종이빛에서 떠오르게
      var flow = function (k) { return k === 'stop' || k === 'arrive' ? 'map' : k; };
      if (!prev || flow(prev.k) !== flow(sc.k)) { var a = 1 - seg(lt, 0, 0.4); if (a > 0) { ctx.save(); ctx.globalAlpha = a; ctx.fillStyle = PAPER; ctx.fillRect(0, 0, W, H); ctx.restore(); } }
    }

    var raf = 0, t0 = 0, audio = null, endRes = null;
    function frame() {
      var t = (performance.now() - t0) / 1000;
      if (t >= tl.total) {
        draw(tl.total); var a = audio; audio = null; if (a) setTimeout(a.close, 900);
        if (opt.onProgress) opt.onProgress(1);
        if (opt.onEnd) opt.onEnd();
        if (endRes) { var r = endRes; endRes = null; r(); }
        return;
      }
      draw(Math.max(0, t)); if (opt.onProgress) opt.onProgress(Math.max(0, t) / tl.total);
      raf = requestAnimationFrame(frame);
    }
    function stop() { cancelAnimationFrame(raf); if (audio) { audio.close(); audio = null; } endRes = null; }
    function begin(sound) {
      if (sound) { audio = makeAudio(tl.total, cues); if (audio) { if (audio.ac.state === 'suspended') audio.ac.resume(); audio.schedule(audio.ac.currentTime + 0.12); } }
      t0 = performance.now() + 120; raf = requestAnimationFrame(frame);
      return new Promise(function (res) { endRes = res; });
    }
    function play(o) { stop(); return ready.then(function () { return begin(!(o && o.mute) && opt.audio !== false); }); }
    /** 처음부터 한 번 틀면서 영상 파일로 담음. { blob, ext } 를 돌려줌 */
    function save() {
      stop();
      return ready.then(function () {
        if (!canvas.captureStream || !root.MediaRecorder) throw new Error('unsupported');
        var types = ['video/mp4;codecs="avc1.42E01E,mp4a.40.2"', 'video/mp4', 'video/webm;codecs=vp9,opus', 'video/webm;codecs=vp8,opus', 'video/webm'];
        var mime = types.filter(function (m) { return MediaRecorder.isTypeSupported(m); })[0];
        if (!mime) throw new Error('unsupported');
        var done = begin(true);
        var tracks = canvas.captureStream(30).getVideoTracks();
        if (audio && audio.dest) tracks = tracks.concat(audio.dest.stream.getAudioTracks());
        var rec = new MediaRecorder(new MediaStream(tracks), { mimeType: mime, videoBitsPerSecond: 5000000 }), chunks = [];
        rec.ondataavailable = function (e) { if (e.data && e.data.size) chunks.push(e.data); };
        var out = new Promise(function (res) { rec.onstop = function () { res({ blob: new Blob(chunks, { type: mime.split(';')[0] }), ext: mime.indexOf('mp4') > 0 ? 'mp4' : 'webm' }); }; });
        rec.start(500);
        return done.then(function () { return new Promise(function (res) { setTimeout(function () { rec.stop(); res(out); }, 350); }); });
      });
    }
    return { ready: ready, play: play, stop: stop, save: save, drawAt: draw, poster: function () { return ready.then(function () { draw(2.9); }); }, duration: tl.total, model: model };
  }

  root.Reel = { build: build, create: create, canSave: function () { return !!(root.MediaRecorder && HTMLCanvasElement.prototype.captureStream); } };
})(window);

