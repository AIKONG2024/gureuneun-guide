/* 구르는 가이드 3D 뷰어
 * 명소를 코드로 만든 3D 모형으로 보여 준다. 외부 사진이나 영상 없이 동작한다.
 * mode 'cinematic' : 카메라가 천천히 돌며 보여 주는 짧은 영상 장면
 * mode 'orbit'     : 손으로 돌려 보는 3D 모형
 */
(function () {
  'use strict';
  var T = window.THREE;
  var GG = window.GG = {};
  var renderer, scene, camera, root, host, titleEl, capEl, badgeEl;
  var mode = 'cinematic', current = null, mood = { rain: false, night: false };
  var view = { yaw: 0.7, pitch: 0.32, dist: 20, target: new T.Vector3(0, 3, 0), radius: 8 };
  var drag = null, lastInteract = 0, t0 = performance.now();
  var animators = [], rain = null, stars = null, sunLight, hemiLight, glowLights = [];

  function mat(color, opts) {
    var o = { color: color, flatShading: true, roughness: 0.85, metalness: 0.0 };
    for (var k in (opts || {})) o[k] = opts[k];
    return new T.MeshStandardMaterial(o);
  }
  function box(w, h, d, m) { var g = new T.Mesh(new T.BoxGeometry(w, h, d), m); g.castShadow = true; g.receiveShadow = true; return g; }
  function cyl(rt, rb, h, seg, m) { var g = new T.Mesh(new T.CylinderGeometry(rt, rb, h, seg || 16), m); g.castShadow = true; g.receiveShadow = true; return g; }
  function at(o, x, y, z) { o.position.set(x, y, z); return o; }
  function ground(color, r) { var g = new T.Mesh(new T.CircleGeometry(r || 60, 48), mat(color)); g.rotation.x = -Math.PI / 2; g.receiveShadow = true; return g; }
  function rng(seed) { var s = seed || 1; return function () { s = (s * 16807) % 2147483647; return (s - 1) / 2147483646; }; }

  function stripeTexture(rows, base, line, joints) {
    var c = document.createElement('canvas'); c.width = 512; c.height = 512;
    var x = c.getContext('2d'); x.fillStyle = base; x.fillRect(0, 0, 512, 512);
    var h = 512 / rows, r = rng(7);
    for (var i = 0; i < rows; i++) {
      var shade = 0.9 + r() * 0.2;
      x.fillStyle = 'rgba(0,0,0,' + (0.05 + (1 - shade) * 0.6) + ')'; x.fillRect(0, i * h, 512, h);
      x.fillStyle = line; x.fillRect(0, i * h, 512, Math.max(1.5, h * 0.08));
      if (joints) for (var j = 0; j < joints; j++) { var jx = (j + (i % 2) * 0.5) * 512 / joints; x.fillRect(jx, i * h, 2, h); }
    }
    var t = new T.CanvasTexture(c); t.wrapS = t.wrapT = T.RepeatWrapping; t.colorSpace = T.SRGBColorSpace; return t;
  }

  function water(color, size, amp) {
    var g = new T.PlaneGeometry(size, size, 48, 48);
    var m = new T.Mesh(g, new T.MeshStandardMaterial({ color: color, roughness: 0.25, metalness: 0.1, flatShading: true, transparent: true, opacity: 0.92 }));
    m.rotation.x = -Math.PI / 2; m.receiveShadow = true;
    var pos = g.attributes.position, base = pos.array.slice();
    animators.push(function (t) {
      for (var i = 0; i < pos.count; i++) {
        var px = base[i * 3], py = base[i * 3 + 1];
        pos.array[i * 3 + 2] = Math.sin(px * 0.35 + t * 1.3) * amp + Math.cos(py * 0.3 + t * 1.1) * amp * 0.7;
      }
      pos.needsUpdate = true; g.computeVertexNormals();
    });
    return m;
  }

  function tree(r, x, z, s) {
    var g = new T.Group();
    g.add(at(cyl(0.12 * s, 0.18 * s, 1.2 * s, 6, mat('#6b4f2f')), 0, 0.6 * s, 0));
    g.add(at(new T.Mesh(new T.ConeGeometry(0.9 * s, 2.4 * s, 7), mat(r() > 0.5 ? '#3f7a3a' : '#4f8a45')), 0, 2.2 * s, 0));
    g.children[1].castShadow = true;
    return at(g, x, 0, z);
  }

  function roof(w, d, h, m) {
    var r = new T.Mesh(new T.ConeGeometry(Math.SQRT2 / 2, 1, 4), m);
    r.rotation.y = Math.PI / 4; r.scale.set(w, h, d); r.castShadow = true; return r;
  }

  // ------------------------------------------------------------ 모형
  var MODELS = {};

  MODELS.cheomseongdae = function () {
    var g = new T.Group();
    g.add(ground('#6f9e5a', 40));
    var stone = mat('#cbbd98');
    g.add(at(box(6.6, 0.4, 6.6, stone), 0, 0.2, 0));
    g.add(at(box(5.8, 0.4, 5.8, stone), 0, 0.6, 0));
    var H = 8.2, pts = [];
    for (var i = 0; i <= 30; i++) { var y = H * i / 30; pts.push(new T.Vector2(1.5 + 1.15 * Math.pow(1 - y / H, 1.7), y)); }
    var tex = stripeTexture(27, '#d6c7a1', 'rgba(80,60,40,0.35)', 14); tex.repeat.set(3, 1);
    var body = new T.Mesh(new T.LatheGeometry(pts, 56), new T.MeshStandardMaterial({ map: tex, roughness: 0.95 }));
    body.position.y = 0.8; body.castShadow = true; body.receiveShadow = true; g.add(body);
    var wy = 0.8 + H * 14 / 27, wr = 1.5 + 1.15 * Math.pow(1 - (wy - 0.8) / H, 1.7);
    g.add(at(box(0.95, 0.95, 0.5, mat('#1d1a16')), 0, wy, wr - 0.12));
    var beam = mat('#c9b88e');
    var top = 0.8 + H;
    [-1, 1].forEach(function (s) { g.add(at(box(3.9, 0.32, 0.4, beam), 0, top + 0.16, s * 1.15)); });
    [-1, 1].forEach(function (s) { g.add(at(box(0.4, 0.32, 3.9, beam), s * 1.15, top + 0.48, 0)); });
    var r = rng(3);
    for (var k = 0; k < 14; k++) { var a = r() * Math.PI * 2, d = 14 + r() * 12; g.add(tree(r, Math.cos(a) * d, Math.sin(a) * d, 1 + r() * 0.6)); }
    var glow = new T.PointLight('#ffd27a', 0, 20); glow.position.set(0, 5, 5); g.add(glow); glowLights.push(glow);
    return { group: g, target: new T.Vector3(0, 4.6, 0), radius: 7 };
  };

  MODELS.tumuli = function () {
    var g = new T.Group();
    g.add(ground('#79a25e', 70));
    var grass = mat('#5e8f45');
    function mound(r, h, x, z) { var m = new T.Mesh(new T.SphereGeometry(r, 28, 14, 0, Math.PI * 2, 0, Math.PI / 2), grass); m.scale.y = h / r; m.position.set(x, 0, z); m.castShadow = true; m.receiveShadow = true; g.add(m); }
    mound(7.5, 4.6, -4, -2); mound(7.0, 4.2, 5.2, -3.2);
    mound(5.2, 3.2, 10, 10); mound(4.0, 2.4, -12, 9); mound(3.4, 2.0, 15, -10); mound(4.6, 2.8, -14, -12);
    var path = new T.Mesh(new T.PlaneGeometry(2.2, 60), mat('#d8c9a3')); path.rotation.x = -Math.PI / 2; path.rotation.z = 0.5; path.position.y = 0.02; g.add(path);
    var r = rng(11);
    for (var k = 0; k < 22; k++) { var a = r() * Math.PI * 2, d = 20 + r() * 14; g.add(tree(r, Math.cos(a) * d, Math.sin(a) * d, 1 + r() * 0.8)); }
    return { group: g, target: new T.Vector3(0, 2.5, 0), radius: 13 };
  };

  function pavilion(w, d, x, z, ry) {
    var p = new T.Group();
    p.add(at(box(w + 1, 0.6, d + 1, mat('#bdb3a0')), 0, 0.3, 0));
    var pillar = mat('#8b3a2b');
    [[-1, -1], [1, -1], [-1, 1], [1, 1], [0, -1], [0, 1]].forEach(function (c) { p.add(at(cyl(0.16, 0.16, 2.4, 8, pillar), c[0] * w / 2, 1.8, c[1] * d / 2)); });
    p.add(at(box(w + 0.4, 0.3, d + 0.4, mat('#3c6e5e')), 0, 3.1, 0));
    var rf = roof(w + 2.2, d + 2.2, 1.8, mat('#39434a')); rf.position.y = 4.1; p.add(rf);
    var win = new T.Mesh(new T.BoxGeometry(w * 0.8, 1.2, d * 0.8), new T.MeshStandardMaterial({ color: '#ffcf7a', emissive: '#ff9f43', emissiveIntensity: 0 }));
    win.position.y = 1.7; p.add(win);
    animators.push(function () { win.material.emissiveIntensity = mood.night ? 1.4 : 0.05; });
    p.position.set(x, 0, z); p.rotation.y = ry || 0; return p;
  }

  MODELS.donggung = function () {
    var g = new T.Group();
    g.add(ground('#7aa25f', 70));
    var pond = water('#2c6e8f', 34, 0.05); pond.position.y = 0.05; g.add(pond);
    var isl = mat('#5f8f45');
    [[-6, -4, 2.2], [3, 3, 1.6], [8, -6, 1.3]].forEach(function (s) { var m = new T.Mesh(new T.SphereGeometry(s[2], 18, 9, 0, Math.PI * 2, 0, Math.PI / 2), isl); m.scale.y = 0.45; m.position.set(s[0], 0, s[1]); g.add(m); });
    var pavs = [pavilion(5, 3.4, -9, 13, 0.2), pavilion(4, 3, 0, 15, 0), pavilion(4.4, 3, 8.5, 13.5, -0.25)];
    pavs.forEach(function (p) { g.add(p); var mirror = p.clone(); mirror.scale.y = -0.9; mirror.position.y = 0.1; mirror.traverse(function (o) { if (o.isMesh) { o.material = o.material.clone(); o.material.transparent = true; o.material.opacity = 0.28; } }); g.add(mirror); });
    var r = rng(5);
    for (var k = 0; k < 16; k++) { var a = r() * Math.PI * 2, d = 22 + r() * 10; g.add(tree(r, Math.cos(a) * d, Math.sin(a) * d, 1 + r() * 0.5)); }
    for (var i = 0; i < 3; i++) { var l = new T.PointLight('#ffb35c', 0, 26); l.position.set(-9 + i * 9, 3, 13); g.add(l); glowLights.push(l); }
    return { group: g, target: new T.Vector3(0, 2, 5), radius: 15 };
  };

  function horse(color) {
    var m = mat(color, { roughness: 0.5 });
    var h = new T.Group();
    h.add(at(box(3.4, 1.5, 1.3, m), 0, 3.2, 0));
    var neck = at(box(0.9, 2.0, 0.9, m), 1.6, 4.4, 0); neck.rotation.z = -0.45; h.add(neck);
    var head = at(box(1.5, 0.8, 0.85, m), 2.35, 5.3, 0); head.rotation.z = -0.25; h.add(head);
    [[-1.2, -0.4], [-1.2, 0.4], [1.2, -0.4], [1.2, 0.4]].forEach(function (p) { h.add(at(box(0.36, 2.5, 0.36, m), p[0], 1.25, p[1])); });
    var tail = at(box(0.3, 1.6, 0.3, m), -1.9, 2.8, 0); tail.rotation.z = 0.5; h.add(tail);
    var mane = at(box(0.25, 1.4, 0.2, mat('#222')), 1.35, 4.8, 0); mane.rotation.z = -0.45; h.add(mane);
    var lamp = new T.Mesh(new T.SphereGeometry(0.35, 12, 8), new T.MeshStandardMaterial({ color: '#fff7c2', emissive: '#ffe680', emissiveIntensity: 0 }));
    lamp.position.set(2.8, 5.6, 0); h.add(lamp);
    animators.push(function (t) { lamp.material.emissiveIntensity = mood.night ? 1.5 + Math.sin(t * 3) : 0.1; });
    return h;
  }

  MODELS.horse_lighthouse = function () {
    var g = new T.Group();
    var sea = water('#1f7fbf', 120, 0.18); g.add(sea);
    var rock = mat('#7d7d7d');
    var bw1 = at(box(22, 1.2, 3, rock), -8, 0.4, 2); bw1.rotation.y = 0.25; g.add(bw1);
    var bw2 = at(box(22, 1.2, 3, rock), 8, 0.4, -4); bw2.rotation.y = -0.3; g.add(bw2);
    var sand = new T.Mesh(new T.PlaneGeometry(80, 26), mat('#e8dcc0')); sand.rotation.x = -Math.PI / 2; sand.position.set(0, 0.08, 30); g.add(sand);
    var red = horse('#e53935'); red.position.set(-17, 1.0, 4.6); red.rotation.y = 0.25; g.add(red);
    var white = horse('#f4f4f4'); white.position.set(17.5, 1.0, -7); white.rotation.y = Math.PI - 0.3; g.add(white);
    return { group: g, target: new T.Vector3(0, 3, 0), radius: 18 };
  };

  function rocks(g, r, n, cx, cz, spread) {
    var m = mat('#232323', { roughness: 1 });
    for (var i = 0; i < n; i++) { var s = 0.4 + r() * 1.3; var k = new T.Mesh(new T.DodecahedronGeometry(s, 0), m); k.position.set(cx + (r() - 0.5) * spread, s * 0.3, cz + (r() - 0.5) * spread * 0.4); k.rotation.set(r() * 3, r() * 3, r() * 3); k.castShadow = true; g.add(k); }
  }

  MODELS.beach = function () {
    var g = new T.Group();
    var sea = water('#1aa6b7', 140, 0.22); sea.position.set(0, 0, -30); g.add(sea);
    var sand = new T.Mesh(new T.PlaneGeometry(90, 30), mat('#e6d8b6')); sand.rotation.x = -Math.PI / 2; sand.position.set(0, 0.1, 18); g.add(sand);
    var r = rng(21); rocks(g, r, 26, 0, 3, 50);
    var cafe = new T.Group();
    cafe.add(at(box(8, 4, 5, mat('#f5f5f0')), 0, 2, 0));
    cafe.add(at(box(7.6, 2.4, 0.2, new T.MeshStandardMaterial({ color: '#9fd8ff', transparent: true, opacity: 0.6 })), 0, 2.2, -2.6));
    cafe.position.set(10, 0, 22); g.add(cafe);
    return { group: g, target: new T.Vector3(0, 1.5, 0), radius: 16 };
  };

  MODELS.hyeopjae = function () {
    var g = new T.Group();
    var deep = water('#0b8fb3', 200, 0.24); deep.position.set(0, -0.05, -60); g.add(deep);
    var shallow = water('#46d7d0', 60, 0.08); shallow.position.set(0, 0.02, -8); shallow.scale.set(1.6, 1, 0.5); g.add(shallow);
    var sand = new T.Mesh(new T.PlaneGeometry(110, 34), mat('#f3ecd8')); sand.rotation.x = -Math.PI / 2; sand.position.set(0, 0.12, 22); g.add(sand);
    var r = rng(9); rocks(g, r, 34, 0, 6, 70);
    var isle = new T.Mesh(new T.ConeGeometry(9, 6, 16), mat('#4f7f3d')); isle.position.set(-6, 1.5, -70); g.add(isle);
    var isle2 = new T.Mesh(new T.CylinderGeometry(10, 11, 1.2, 16), mat('#6d6a5c')); isle2.position.set(-6, 0, -70); g.add(isle2);
    return { group: g, target: new T.Vector3(0, 1.2, -10), radius: 20 };
  };

  MODELS.building63 = function () {
    var g = new T.Group();
    g.add(ground('#7d9a6a', 90));
    var river = new T.Mesh(new T.PlaneGeometry(200, 36), new T.MeshStandardMaterial({ color: '#2f6f8f', roughness: 0.3 })); river.rotation.x = -Math.PI / 2; river.position.set(0, 0.05, -34); g.add(river);
    var lines = stripeTexture(60, '#c9a227', 'rgba(70,45,0,0.55)', 0); lines.repeat.set(1, 1);
    var gold = new T.MeshStandardMaterial({ color: '#e0b43a', map: lines, metalness: 0.75, roughness: 0.28, emissive: '#3a2a00', emissiveIntensity: 0.2 });
    var tower = new T.Mesh(new T.CylinderGeometry(3.4, 5.4, 44, 4, 1), gold);
    tower.rotation.y = Math.PI / 4; tower.scale.set(1.6, 1, 0.75); tower.position.y = 22; tower.castShadow = true; g.add(tower);
    var crown = new T.Mesh(new T.CylinderGeometry(1.2, 3.4, 4, 4, 1), gold); crown.rotation.y = Math.PI / 4; crown.scale.set(1.6, 1, 0.75); crown.position.y = 46; g.add(crown);
    g.add(at(cyl(0.12, 0.12, 6, 6, mat('#dddddd')), 0, 51, 0));
    g.add(at(box(16, 3, 12, mat('#c7c2b6')), 0, 1.5, 3));
    var r = rng(4), grey = mat('#9aa3ad');
    for (var i = 0; i < 12; i++) { var h = 6 + r() * 16; g.add(at(box(4 + r() * 4, h, 4 + r() * 4, grey), -40 + i * 7 + r() * 3, h / 2, 22 + r() * 12)); }
    var night = [];
    for (var j = 0; j < 40; j++) { var win = new T.Mesh(new T.PlaneGeometry(1.2, 0.5), new T.MeshBasicMaterial({ color: '#ffe9a8', transparent: true, opacity: 0 })); win.position.set((r() - 0.5) * 9, 4 + r() * 36, 2.5); g.add(win); night.push(win); }
    animators.push(function () { night.forEach(function (w) { w.material.opacity = mood.night ? 0.9 : 0; }); gold.emissiveIntensity = mood.night ? 0.55 : 0.18; });
    return { group: g, target: new T.Vector3(0, 20, 0), radius: 24 };
  };

  MODELS.riverpark = function () {
    var g = new T.Group();
    var river = water('#2a6f93', 160, 0.12); river.position.set(0, 0, -26); g.add(river);
    var bank = new T.Mesh(new T.PlaneGeometry(120, 30), mat('#6fa456')); bank.rotation.x = -Math.PI / 2; bank.position.set(0, 0.1, 14); g.add(bank);
    var bridge = new T.Group();
    bridge.add(at(box(110, 1.2, 4, mat('#9fa8b3')), 0, 5, 0));
    for (var i = -4; i <= 4; i++) { bridge.add(at(cyl(0.6, 0.8, 5, 8, mat('#8a929c')), i * 12, 2.5, 0)); }
    bridge.position.z = -40; g.add(bridge);
    var r = rng(13), colors = ['#ff6b6b', '#ffd166', '#4ecdc4', '#a78bfa'];
    for (var k = 0; k < 10; k++) { var tent = new T.Mesh(new T.ConeGeometry(1.2, 1.6, 4), mat(colors[k % 4])); tent.position.set(-30 + k * 6 + r() * 2, 0.8, 12 + r() * 8); g.add(tent); }
    for (var t = 0; t < 18; t++) g.add(tree(r, -50 + t * 6, 24 + r() * 5, 1 + r() * 0.5));
    var fw = new T.Group(); g.add(fw);
    animators.push(function (time) {
      fw.visible = mood.night;
      if (!mood.night) return;
      if (fw.children.length < 4 && Math.random() < 0.03) {
        var geo = new T.BufferGeometry(), n = 160, arr = new Float32Array(n * 3), vel = [];
        for (var i = 0; i < n; i++) { var a = Math.random() * Math.PI * 2, b = Math.random() * Math.PI, s = 5 + Math.random() * 4; vel.push([Math.cos(a) * Math.sin(b) * s, Math.cos(b) * s, Math.sin(a) * Math.sin(b) * s]); }
        geo.setAttribute('position', new T.BufferAttribute(arr, 3));
        var p = new T.Points(geo, new T.PointsMaterial({ color: colors[(Math.random() * 4) | 0], size: 0.7, transparent: true }));
        p.userData = { born: time, vel: vel, origin: [(Math.random() - 0.5) * 50, 22 + Math.random() * 8, -30] }; fw.add(p);
      }
      fw.children.slice().forEach(function (p) {
        var age = time - p.userData.born, a = p.geometry.attributes.position;
        for (var i = 0; i < a.count; i++) { var v = p.userData.vel[i]; a.array[i * 3] = p.userData.origin[0] + v[0] * age; a.array[i * 3 + 1] = p.userData.origin[1] + v[1] * age - 4 * age * age; a.array[i * 3 + 2] = p.userData.origin[2] + v[2] * age; }
        a.needsUpdate = true; p.material.opacity = Math.max(0, 1 - age / 2.2);
        if (age > 2.2) { fw.remove(p); p.geometry.dispose(); }
      });
    });
    return { group: g, target: new T.Vector3(0, 4, -12), radius: 22 };
  };

  MODELS.assembly = function () {
    var g = new T.Group();
    g.add(ground('#79a35f', 80));
    var plaza = new T.Mesh(new T.PlaneGeometry(40, 30), mat('#d9d4c7')); plaza.rotation.x = -Math.PI / 2; plaza.position.set(0, 0.05, 8); g.add(plaza);
    var white = mat('#ece9e1');
    g.add(at(box(24, 1.2, 16, white), 0, 0.6, 0));
    g.add(at(box(20, 6.4, 12, mat('#e3dfd4')), 0, 4.4, 0));
    var col = mat('#f4f2ec'), n = 0;
    function column(x, z) { g.add(at(cyl(0.42, 0.48, 6.4, 12, col), x, 4.4, z)); n++; }
    for (var i = 0; i < 8; i++) { var x = -10.5 + i * 3; column(x, 7.4); column(x, -7.4); }
    for (var j = 0; j < 4; j++) { var z = -4.5 + j * 3; column(11.4, z); column(-11.4, z); }
    g.add(at(box(25, 0.8, 17, white), 0, 8, 0));
    g.add(at(cyl(5.2, 5.6, 1.6, 32, mat('#d7d2c4')), 0, 9.2, 0));
    var dome = new T.Mesh(new T.SphereGeometry(5.2, 36, 18, 0, Math.PI * 2, 0, Math.PI / 2), mat('#5f9c86', { roughness: 0.6, flatShading: false }));
    dome.position.y = 10; dome.castShadow = true; g.add(dome);
    for (var s = 0; s < 5; s++) g.add(at(box(14 - s, 0.25, 1.2, white), 0, 0.125 + s * 0.25, 9.2 - s * 0.9));
    var r = rng(17);
    for (var t = 0; t < 18; t++) { var a = r() * Math.PI * 2, d = 26 + r() * 10; g.add(tree(r, Math.cos(a) * d, Math.sin(a) * d, 1 + r() * 0.5)); }
    var glow = new T.PointLight('#fff1c9', 0, 40); glow.position.set(0, 14, 12); g.add(glow); glowLights.push(glow);
    g.userData.columns = n;
    return { group: g, target: new T.Vector3(0, 6, 0), radius: 15 };
  };

  MODELS.generic = function () {
    var g = new T.Group();
    g.add(ground('#7aa25f', 60));
    var r = rng(2), m = mat('#9aa3ad');
    for (var i = 0; i < 18; i++) { var h = 3 + r() * 10; g.add(at(box(3, h, 3, m), (r() - 0.5) * 30, h / 2, (r() - 0.5) * 30)); }
    return { group: g, target: new T.Vector3(0, 4, 0), radius: 14 };
  };

  // ------------------------------------------------------------ 장면과 하늘
  function skyTexture(top, bottom) {
    var c = document.createElement('canvas'); c.width = 2; c.height = 256;
    var x = c.getContext('2d'), gr = x.createLinearGradient(0, 0, 0, 256);
    gr.addColorStop(0, top); gr.addColorStop(1, bottom); x.fillStyle = gr; x.fillRect(0, 0, 2, 256);
    var t = new T.CanvasTexture(c); t.colorSpace = T.SRGBColorSpace; return t;
  }

  function applyMood() {
    if (!scene) return;
    var night = mood.night, wet = mood.rain;
    scene.background = night ? skyTexture('#060b1f', '#1c2445') : wet ? skyTexture('#5f6b78', '#a7b0b8') : skyTexture('#5aa7ff', '#cfe8ff');
    scene.fog = new T.Fog(night ? '#141a33' : wet ? '#9aa4ad' : '#cfe8ff', 60, 220);
    hemiLight.intensity = night ? 0.35 : wet ? 0.8 : 1.1;
    sunLight.intensity = night ? 0.25 : wet ? 0.55 : 1.6;
    sunLight.color.set(night ? '#8fa8ff' : '#fff4e0');
    glowLights.forEach(function (l) { l.intensity = night ? 40 : 0; });
    if (stars) stars.visible = night;
    if (rain) rain.visible = wet;
  }

  function makeStars() {
    var n = 900, arr = new Float32Array(n * 3), r = rng(31);
    for (var i = 0; i < n; i++) { var a = r() * Math.PI * 2, b = r() * Math.PI * 0.45, d = 150; arr[i * 3] = Math.cos(a) * Math.sin(b) * d; arr[i * 3 + 1] = Math.cos(b) * d * 0.6 + 10; arr[i * 3 + 2] = Math.sin(a) * Math.sin(b) * d; }
    var g = new T.BufferGeometry(); g.setAttribute('position', new T.BufferAttribute(arr, 3));
    return new T.Points(g, new T.PointsMaterial({ color: '#ffffff', size: 1.1, sizeAttenuation: false }));
  }

  function makeRain() {
    var n = 1600, arr = new Float32Array(n * 6), r = rng(41);
    for (var i = 0; i < n; i++) { var x = (r() - 0.5) * 120, y = r() * 60, z = (r() - 0.5) * 120; arr.set([x, y, z, x + 0.15, y - 1.4, z], i * 6); }
    var g = new T.BufferGeometry(); g.setAttribute('position', new T.BufferAttribute(arr, 3));
    var lines = new T.LineSegments(g, new T.LineBasicMaterial({ color: '#d7e3f0', transparent: true, opacity: 0.5 }));
    animators.push(function (t, dt) {
      if (!lines.visible) return;
      var a = g.attributes.position.array;
      for (var i = 0; i < a.length; i += 3) { a[i + 1] -= 40 * dt; if (a[i + 1] < -2) a[i + 1] += 62; }
      g.attributes.position.needsUpdate = true;
    });
    return lines;
  }

  function build(id) {
    animators = []; glowLights = [];
    if (root) { scene.remove(root); root.traverse(function (o) { if (o.geometry) o.geometry.dispose(); }); }
    root = new T.Group();
    var made = (MODELS[id] || MODELS.generic)();
    root.add(made.group);
    view.target.copy(made.target); view.radius = made.radius; view.dist = made.radius * 2.5;
    stars = makeStars(); root.add(stars);
    rain = makeRain(); root.add(rain);
    scene.add(root);
    current = id;
    applyMood();
  }

  // ------------------------------------------------------------ 시작
  GG.mount = function (el, opts) {
    host = el; opts = opts || {};
    renderer = new T.WebGLRenderer({ antialias: true, alpha: false });
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
    renderer.shadowMap.enabled = true; renderer.shadowMap.type = T.PCFSoftShadowMap;
    renderer.outputColorSpace = T.SRGBColorSpace;
    el.appendChild(renderer.domElement);
    scene = new T.Scene();
    camera = new T.PerspectiveCamera(42, 1, 0.1, 600);
    hemiLight = new T.HemisphereLight('#ffffff', '#5d6b4a', 1.1); scene.add(hemiLight);
    sunLight = new T.DirectionalLight('#fff4e0', 1.6); sunLight.position.set(30, 50, 25); sunLight.castShadow = true;
    sunLight.shadow.mapSize.set(2048, 2048); var sc = sunLight.shadow.camera; sc.left = -60; sc.right = 60; sc.top = 60; sc.bottom = -60; sc.far = 200;
    scene.add(sunLight);

    titleEl = document.createElement('div'); titleEl.className = 'gg-title'; el.appendChild(titleEl);
    capEl = document.createElement('div'); capEl.className = 'gg-caption'; el.appendChild(capEl);
    badgeEl = document.createElement('div'); badgeEl.className = 'gg-badge'; el.appendChild(badgeEl);

    var c = renderer.domElement;
    c.addEventListener('pointerdown', function (e) { drag = { x: e.clientX, y: e.clientY }; lastInteract = performance.now(); if (mode === 'cinematic' && opts.tapToOrbit !== false) GG.setMode('orbit'); c.setPointerCapture(e.pointerId); });
    c.addEventListener('pointermove', function (e) {
      if (!drag) return;
      view.yaw -= (e.clientX - drag.x) * 0.008; view.pitch = Math.max(0.05, Math.min(1.35, view.pitch + (e.clientY - drag.y) * 0.006));
      drag = { x: e.clientX, y: e.clientY }; lastInteract = performance.now();
    });
    c.addEventListener('pointerup', function () { drag = null; });
    c.addEventListener('wheel', function (e) { view.dist = Math.max(view.radius * 1.2, Math.min(view.radius * 5, view.dist * (1 + e.deltaY * 0.001))); lastInteract = performance.now(); e.preventDefault(); }, { passive: false });
    var pinch = null;
    c.addEventListener('touchmove', function (e) {
      if (e.touches.length === 2) {
        var d = Math.hypot(e.touches[0].clientX - e.touches[1].clientX, e.touches[0].clientY - e.touches[1].clientY);
        if (pinch) view.dist = Math.max(view.radius * 1.2, Math.min(view.radius * 5, view.dist * pinch / d));
        pinch = d; drag = null; e.preventDefault();
      }
    }, { passive: false });
    c.addEventListener('touchend', function () { pinch = null; });

    function resize() { var w = el.clientWidth || 300, h = el.clientHeight || 200; renderer.setSize(w, h, false); c.style.width = '100%'; c.style.height = '100%'; camera.aspect = w / h; camera.updateProjectionMatrix(); }
    if (window.ResizeObserver) new ResizeObserver(resize).observe(el); else window.addEventListener('resize', resize);
    resize();
    build(opts.model || 'cheomseongdae');

    var last = performance.now();
    (function loop() {
      requestAnimationFrame(loop);
      var now = performance.now(), dt = Math.min(0.05, (now - last) / 1000), t = (now - t0) / 1000; last = now;
      animators.forEach(function (f) { f(t, dt); });
      if (mode === 'cinematic') {
        var a = t * 0.16, d = view.radius * (2.35 + 0.25 * Math.sin(t * 0.21));
        camera.position.set(view.target.x + Math.cos(a) * d, view.target.y + view.radius * (0.55 + 0.18 * Math.sin(t * 0.13)), view.target.z + Math.sin(a) * d);
      } else {
        if (!drag && now - lastInteract > 4000) view.yaw += dt * 0.12;
        camera.position.set(view.target.x + Math.cos(view.yaw) * Math.cos(view.pitch) * view.dist, view.target.y + Math.sin(view.pitch) * view.dist, view.target.z + Math.sin(view.yaw) * Math.cos(view.pitch) * view.dist);
      }
      camera.lookAt(view.target);
      renderer.render(scene, camera);
    })();
  };

  GG.show = function (o) {
    if (!renderer) return;
    o = o || {};
    if (o.model && o.model !== current) build(o.model);
    if (typeof o.rain === 'boolean' || typeof o.night === 'boolean') { mood.rain = !!o.rain; mood.night = !!o.night; applyMood(); }
    if (o.name !== undefined) { titleEl.textContent = o.name || ''; titleEl.classList.remove('show'); void titleEl.offsetWidth; if (o.name) titleEl.classList.add('show'); }
    if (o.caption !== undefined) GG.caption(o.caption);
    if (o.mode) GG.setMode(o.mode);
  };
  GG.caption = function (text) { if (!capEl) return; capEl.textContent = text || ''; capEl.style.opacity = text ? 1 : 0; };
  GG.setMode = function (m) { mode = m === 'orbit' ? 'orbit' : 'cinematic'; if (badgeEl) badgeEl.textContent = mode === 'orbit' ? '3D' : ''; if (GG.onMode) GG.onMode(mode); };
  GG.mode = function () { return mode; };
  GG.models = Object.keys(MODELS);
})();
