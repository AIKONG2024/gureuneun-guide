// 구르는 가이드 시연 필름의 3D 지도 장면 (three.js)
// 앱이 쓰는 경주 지도 위에서 차가 실제 경로를 달리고, 담은 곳이 핀으로 꽂히고, 요금소를 지나면 금빛이 번진다.
// HyperFrames 가 프레임마다 보내는 hf-seek 시각으로만 그림(재생 시계 없음) → 어느 프레임을 다시 그려도 같은 그림.
//   window.WORLD   world.py 가 만든 경로와 지도
//   window.SCENE3D build.py 가 장면마다 넣는 설정(차 위치, 카메라, 핀, 금빛 번짐, 안내 방향 빛 등)
import * as THREE from 'three';
import { RoomEnvironment } from '../vendor/three/examples/jsm/environments/RoomEnvironment.js';
import { EffectComposer } from '../vendor/three/examples/jsm/postprocessing/EffectComposer.js';
import { RenderPass } from '../vendor/three/examples/jsm/postprocessing/RenderPass.js';
import { UnrealBloomPass } from '../vendor/three/examples/jsm/postprocessing/UnrealBloomPass.js';
import { OutputPass } from '../vendor/three/examples/jsm/postprocessing/OutputPass.js';

const W = window.WORLD, S = window.SCENE3D;
const canvas = document.getElementById('gl');

function track(keys, t, ease) {
  if (!keys || !keys.length) return 0;
  if (t <= keys[0][0]) return keys[0][1];
  for (let i = 1; i < keys.length; i++) {
    if (t <= keys[i][0]) {
      const a = keys[i - 1], b = keys[i];
      let k = (t - a[0]) / Math.max(1e-6, b[0] - a[0]);
      if (ease) k = k * k * (3 - 2 * k);
      return a[1] + (b[1] - a[1]) * k;
    }
  }
  return keys[keys.length - 1][1];
}
const clamp01 = (v) => Math.max(0, Math.min(1, v));
const easeOutBack = (x) => { const c1 = 1.70158, c3 = c1 + 1; return 1 + c3 * Math.pow(x - 1, 3) + c1 * Math.pow(x - 1, 2); };
const rad = (d) => d * Math.PI / 180;

function start() {
  window.THREE = THREE;                                        // HyperFrames 가 텍스처 로딩이 끝날 때까지 기다리게 함
  const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, preserveDrawingBuffer: true, powerPreference: 'high-performance' });
  renderer.setPixelRatio(1);
  renderer.setSize(1920, 1080, false);
  renderer.shadowMap.enabled = true;
  renderer.shadowMap.type = THREE.PCFSoftShadowMap;
  renderer.toneMapping = THREE.NoToneMapping;
  const scene = new THREE.Scene();
  const BG = new THREE.Color(W.bg);
  scene.background = BG.clone();
  scene.fog = new THREE.Fog(BG.clone(), 4000, 16000);
  const pmrem = new THREE.PMREMGenerator(renderer);
  scene.environment = pmrem.fromScene(new RoomEnvironment(), 0.04).texture;
  scene.environmentIntensity = 0.35;
  const cam = new THREE.PerspectiveCamera(30, 16 / 9, 20, 60000);

  const BLUE = new THREE.Color('#00AAD2'), GOLD = new THREE.Color('#E9B96E'), GOLD_HI = new THREE.Color('#FFD9A0');
  const K = S.carScale || 16;

  // ---------------------------------------------------------------- 경로
  const P = W.pts, STEP = W.step, N = P.length;
  const v1 = new THREE.Vector3(), v2 = new THREE.Vector3(), v3 = new THREE.Vector3();
  function posAt(s, out) {
    const f = Math.min(Math.max(s / STEP, 0), N - 1.001);
    const i = Math.floor(f), k = f - i;
    return out.set(P[i][0] + (P[i + 1][0] - P[i][0]) * k, 0, P[i][1] + (P[i + 1][1] - P[i][1]) * k);
  }
  function headingAt(s) {
    const a = posAt(s - 30, v1), b = posAt(s + 30, v2);
    return Math.atan2(b.x - a.x, b.z - a.z);
  }
  function ribbon(y) {
    const pos = new Float32Array(N * 6), aS = new Float32Array(N * 2), aN = new Float32Array(N * 4), aSide = new Float32Array(N * 2), uv = new Float32Array(N * 4), idx = [];
    for (let i = 0; i < N; i++) {
      const a = P[Math.max(0, i - 1)], b = P[Math.min(N - 1, i + 1)];
      let tx = b[0] - a[0], tz = b[1] - a[1];
      const l = Math.hypot(tx, tz) || 1; tx /= l; tz /= l;
      const nx = -tz, nz = tx;
      pos.set([P[i][0], y, P[i][1], P[i][0], y, P[i][1]], i * 6);
      aN.set([nx, nz, nx, nz], i * 4); aSide.set([1, -1], i * 2);
      aS[i * 2] = aS[i * 2 + 1] = i * STEP;
      uv.set([0, 0, 1, 0], i * 4);
      if (i < N - 1) idx.push(i * 2, i * 2 + 1, i * 2 + 2, i * 2 + 1, i * 2 + 3, i * 2 + 2);
    }
    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.BufferAttribute(pos, 3));
    g.setAttribute('aS', new THREE.BufferAttribute(aS, 1));
    g.setAttribute('aN', new THREE.BufferAttribute(aN, 2));
    g.setAttribute('aSide', new THREE.BufferAttribute(aSide, 1));
    g.setAttribute('uv', new THREE.BufferAttribute(uv, 2));
    g.setIndex(idx);
    return g;
  }

  // ---------------------------------------------------------------- 바닥: 실제 지도
  const mapU = { tMap: { value: null }, uBg: { value: BG.clone() }, uCenter: { value: new THREE.Vector2(0, 0) }, uRadius: { value: -1 }, uGold: { value: 0 }, uRing: { value: 0 }, uDim: { value: 1 }, uReady: { value: 0 } };
  const mw = W.map.x1 - W.map.x0, mh = W.map.z1 - W.map.z0;
  const mapMesh = new THREE.Mesh(new THREE.PlaneGeometry(mw, mh), new THREE.ShaderMaterial({
    uniforms: mapU,
    vertexShader: 'varying vec2 vUv; varying vec2 vW; void main(){ vUv = uv; vec4 w = modelMatrix * vec4(position,1.0); vW = w.xz; gl_Position = projectionMatrix * viewMatrix * w; }',
    fragmentShader: [
      'uniform sampler2D tMap; uniform vec3 uBg; uniform vec2 uCenter; uniform float uRadius, uGold, uRing, uDim, uReady; varying vec2 vUv; varying vec2 vW;',
      'void main(){',
      '  vec3 c = mix(uBg, texture2D(tMap, vUv).rgb, uReady);',
      '  float e = smoothstep(0.0, 0.05, vUv.x) * smoothstep(1.0, 0.95, vUv.x) * smoothstep(0.0, 0.05, vUv.y) * smoothstep(1.0, 0.95, vUv.y);',
      '  c = mix(uBg, c, e);',
      '  float d = distance(vW, uCenter);',
      '  float k = smoothstep(uRadius, uRadius - 500.0, d) * uGold;',
      '  float l = dot(c, vec3(0.299, 0.587, 0.114));',
      '  vec3 warm = vec3(l) * vec3(2.3, 1.62, 0.86) + vec3(0.012, 0.007, 0.0);',
      '  c = mix(c, warm, k * 0.85);',
      '  float ring = smoothstep(42.0, 0.0, abs(d - uRadius)) * uRing;',
      '  c += vec3(1.0, 0.72, 0.36) * ring * 0.7;',
      '  gl_FragColor = vec4(c * uDim, 1.0);',
      '}'].join('\n'),
  }));
  mapMesh.rotation.x = -Math.PI / 2;
  mapMesh.position.set((W.map.x0 + W.map.x1) / 2, 0, (W.map.z0 + W.map.z1) / 2);
  scene.add(mapMesh);
  const ground = new THREE.Mesh(new THREE.PlaneGeometry(120000, 120000), new THREE.MeshBasicMaterial({ color: BG }));
  ground.rotation.x = -Math.PI / 2; ground.position.y = -2; scene.add(ground);
  const shadowPlane = new THREE.Mesh(new THREE.PlaneGeometry(mw, mh), new THREE.ShadowMaterial({ opacity: 0.42 }));
  shadowPlane.rotation.x = -Math.PI / 2; shadowPlane.position.copy(mapMesh.position).setY(0.6); shadowPlane.receiveShadow = true; scene.add(shadowPlane);
  let dirty = null;
  new THREE.TextureLoader().load(W.map.src, (tex) => {
    tex.colorSpace = THREE.SRGBColorSpace; tex.anisotropy = renderer.capabilities.getMaxAnisotropy(); tex.generateMipmaps = true;
    mapU.tMap.value = tex; mapU.uReady.value = 1;
    if (dirty != null) render(dirty);
  });

  // ---------------------------------------------------------------- 빛
  const hemi = new THREE.HemisphereLight(0xdfe8f2, 0x2a3138, 1.0); scene.add(hemi);
  const sun = new THREE.DirectionalLight(0xffffff, 1.7); sun.castShadow = true;
  sun.shadow.mapSize.set(4096, 4096); sun.shadow.bias = -0.0004; sun.shadow.normalBias = 1.5; sun.shadow.radius = 6;
  scene.add(sun, sun.target);
  const COOL_SUN = new THREE.Color('#F2F6FF'), WARM_SUN = new THREE.Color('#FFD9A8'), COOL_SKY = new THREE.Color('#DFE8F2'), WARM_SKY = new THREE.Color('#F3DDBB');

  // ---------------------------------------------------------------- 경로 선
  const routeU = { uHead: { value: 0 }, uDraw: { value: 1e9 }, uFrom: { value: -1e9 }, uGold: { value: 0 }, uAlpha: { value: 1 }, uW: { value: 24 }, uBlue: { value: BLUE.clone() }, uGoldC: { value: GOLD_HI.clone() } };
  const route = new THREE.Mesh(ribbon(2.2), new THREE.ShaderMaterial({
    uniforms: routeU, transparent: true, depthWrite: false, side: THREE.DoubleSide,
    vertexShader: 'attribute float aS; attribute vec2 aN; attribute float aSide; uniform float uW; varying float vS; varying float vU; void main(){ vS = aS; vU = uv.x; vec3 p = position + vec3(aN.x, 0.0, aN.y) * aSide * uW * 0.5; gl_Position = projectionMatrix * modelViewMatrix * vec4(p,1.0); }',
    fragmentShader: [
      'uniform float uHead, uDraw, uFrom, uGold, uAlpha; uniform vec3 uBlue, uGoldC; varying float vS; varying float vU;',
      'void main(){',
      '  if (vS > uDraw || vS < uFrom) discard;',
      '  vec3 c = mix(uBlue, uGoldC, uGold);',
      '  float ahead = step(uHead, vS);',
      '  float edge = smoothstep(0.0, 0.2, vU) * smoothstep(1.0, 0.8, vU);',
      '  float tip = smoothstep(uDraw, uDraw - 60.0, vS);',
      '  gl_FragColor = vec4(c * (0.62 + 0.3 * ahead), mix(0.5, 1.0, ahead) * uAlpha * edge * tip);',
      '}'].join('\n'),
  }));
  route.renderOrder = 2; route.frustumCulled = false; scene.add(route);

  // ---------------------------------------------------------------- 재질
  const M = {
    paint: new THREE.MeshPhysicalMaterial({ color: 0xe9edf1, metalness: 0.25, roughness: 0.42, clearcoat: 0.6, clearcoatRoughness: 0.25 }),
    glass: new THREE.MeshStandardMaterial({ color: 0x10161c, metalness: 0.7, roughness: 0.18, emissive: 0x000000 }),
    tire: new THREE.MeshStandardMaterial({ color: 0x15181b, roughness: 0.9 }),
    rim: new THREE.MeshStandardMaterial({ color: 0xb9c0c7, metalness: 0.8, roughness: 0.35 }),
    wood: new THREE.MeshStandardMaterial({ color: 0x8a5a41, roughness: 0.85 }),
    tile: new THREE.MeshStandardMaterial({ color: 0x30363d, roughness: 0.8 }),
    stone: new THREE.MeshStandardMaterial({ color: 0xcfc8ba, roughness: 0.95 }),
    stoneDark: new THREE.MeshStandardMaterial({ color: 0x858b92, roughness: 0.95 }),
    grass: new THREE.MeshStandardMaterial({ color: 0x3f5645, roughness: 1 }),
    water: new THREE.MeshStandardMaterial({ color: 0x24384f, roughness: 0.12, metalness: 0.65 }),
    booth: new THREE.MeshStandardMaterial({ color: 0x59616b, roughness: 0.8 }),
  };
  const glow = (hex, k) => new THREE.MeshBasicMaterial({ color: new THREE.Color(hex).multiplyScalar(k || 1), toneMapped: false });
  const shadowed = (o) => { o.traverse((m) => { if (m.isMesh) { m.castShadow = true; } }); return o; };

  // ---------------------------------------------------------------- 차
  function makeCar() {
    const g = new THREE.Group();
    // 위에서 봤을 때 모서리가 둥근 판을 쌓아 만든 차 (차체, 유리, 지붕)
    const slab = (w, l, rad_, h, y0, z0, mat, bevel) => {
      const s = new THREE.Shape(), x = -w / 2, y = -l / 2;
      s.moveTo(x + rad_, y); s.lineTo(x + w - rad_, y); s.quadraticCurveTo(x + w, y, x + w, y + rad_); s.lineTo(x + w, y + l - rad_); s.quadraticCurveTo(x + w, y + l, x + w - rad_, y + l);
      s.lineTo(x + rad_, y + l); s.quadraticCurveTo(x, y + l, x, y + l - rad_); s.lineTo(x, y + rad_); s.quadraticCurveTo(x, y, x + rad_, y);
      const geo = new THREE.ExtrudeGeometry(s, { depth: h, bevelEnabled: true, bevelThickness: bevel, bevelSize: bevel, bevelSegments: 4, curveSegments: 10 });
      geo.rotateX(-Math.PI / 2); geo.translate(0, y0, z0);
      return new THREE.Mesh(geo, mat);
    };
    g.add(slab(1.66, 4.42, 0.62, 0.50, 0.42, 0, M.paint, 0.11));
    const glassMat = M.glass.clone();
    g.add(slab(1.34, 2.52, 0.5, 0.40, 1.08, -0.28, glassMat, 0.1));
    g.add(slab(1.18, 1.62, 0.42, 0.04, 1.56, -0.36, M.paint, 0.03));
    for (const [x, z] of [[0.9, 1.5], [-0.9, 1.5], [0.9, -1.5], [-0.9, -1.5]]) {
      const t = new THREE.Mesh(new THREE.CylinderGeometry(0.37, 0.37, 0.26, 28), M.tire); t.rotation.z = Math.PI / 2; t.position.set(x, 0.37, z); g.add(t);
      const r = new THREE.Mesh(new THREE.CylinderGeometry(0.23, 0.23, 0.28, 20), M.rim); r.rotation.z = Math.PI / 2; r.position.set(x, 0.37, z); g.add(r);
    }
    const head = glow('#FFFFFF', 2.6);
    for (const x of [0.62, -0.62]) { const h = new THREE.Mesh(new THREE.BoxGeometry(0.36, 0.09, 0.06), head); h.position.set(x, 0.80, 2.36); g.add(h); }
    const drl = new THREE.Mesh(new THREE.BoxGeometry(0.86, 0.035, 0.05), glow('#CFE6FF', 1.6)); drl.position.set(0, 0.80, 2.36); g.add(drl);
    const tail = new THREE.Mesh(new THREE.BoxGeometry(1.5, 0.07, 0.05), glow('#FF3B30', 2.2)); tail.position.set(0, 0.88, -2.40); g.add(tail);
    const sigMat = { L: glow('#FFB020', 0.0), R: glow('#FFB020', 0.0) };
    for (const side of ['L', 'R']) for (const z of [2.3, -2.36]) { const m = new THREE.Mesh(new THREE.BoxGeometry(0.22, 0.12, 0.1), sigMat[side]); m.position.set(side === 'L' ? 0.86 : -0.86, 0.8, z); g.add(m); }
    // 전조등이 비추는 길
    const cv = document.createElement('canvas'); cv.width = 64; cv.height = 256; const cx = cv.getContext('2d');
    const gr = cx.createLinearGradient(0, 256, 0, 0); gr.addColorStop(0, 'rgba(255,255,255,0.85)'); gr.addColorStop(1, 'rgba(255,255,255,0)'); cx.fillStyle = gr; cx.fillRect(0, 0, 64, 256);
    const side = cx.createLinearGradient(0, 0, 64, 0); side.addColorStop(0, 'rgba(0,0,0,1)'); side.addColorStop(0.25, 'rgba(0,0,0,0)'); side.addColorStop(0.75, 'rgba(0,0,0,0)'); side.addColorStop(1, 'rgba(0,0,0,1)');
    cx.globalCompositeOperation = 'destination-out'; cx.fillStyle = side; cx.fillRect(0, 0, 64, 256);
    const beamTex = new THREE.CanvasTexture(cv);
    const beam = new THREE.Mesh(new THREE.PlaneGeometry(4.6, 11), new THREE.MeshBasicMaterial({ map: beamTex, transparent: true, opacity: 0.2, blending: THREE.AdditiveBlending, depthWrite: false, color: 0xdfeaff }));
    beam.rotation.x = -Math.PI / 2; beam.position.set(0, 0.06, 2.4 + 5.5); beam.renderOrder = 3; g.add(beam);
    // 차 아래로 새는 실내 조명 빛
    const cv2 = document.createElement('canvas'); cv2.width = cv2.height = 128; const c2 = cv2.getContext('2d');
    const rg = c2.createRadialGradient(64, 64, 4, 64, 64, 64); rg.addColorStop(0, 'rgba(255,255,255,0.9)'); rg.addColorStop(1, 'rgba(255,255,255,0)'); c2.fillStyle = rg; c2.fillRect(0, 0, 128, 128);
    const under = new THREE.Mesh(new THREE.PlaneGeometry(8.5, 10.5), new THREE.MeshBasicMaterial({ map: new THREE.CanvasTexture(cv2), transparent: true, opacity: 0.5, blending: THREE.AdditiveBlending, depthWrite: false, color: BLUE.clone() }));
    under.rotation.x = -Math.PI / 2; under.position.y = 0.05; under.renderOrder = 3; g.add(under);
    g.traverse((m) => { if (m.isMesh && m !== beam && m !== under) m.castShadow = true; });
    g.userData = { glassMat, sigMat, under, beam };
    return g;
  }
  const car = makeCar(); car.scale.setScalar(K); scene.add(car);
  car.visible = S.carOn !== false;

  // ---------------------------------------------------------------- 한옥 지붕
  function hanokRoof(len, k, pitch) {
    const sh = new THREE.Shape();
    sh.moveTo(-18, 0); sh.quadraticCurveTo(-7, 3.5, 0, 19); sh.quadraticCurveTo(7, 3.5, 18, 0); sh.lineTo(15.5, -1.4); sh.quadraticCurveTo(6, 1.2, 0, 8.5); sh.quadraticCurveTo(-6, 1.2, -15.5, -1.4); sh.lineTo(-18, 0);
    const geo = new THREE.ExtrudeGeometry(sh, { depth: len, bevelEnabled: false, curveSegments: 14 });
    const py = pitch || 0.7;
    geo.translate(0, 0, -len / 2); geo.rotateY(Math.PI / 2); geo.scale(1, k * py, k);
    const g = new THREE.Group();
    g.add(new THREE.Mesh(geo, M.tile));
    const ridge = new THREE.Mesh(new THREE.BoxGeometry(len + 4, 2.4 * k, 3.0 * k), M.stone); ridge.position.y = 19 * py * k; g.add(ridge);
    return g;
  }
  function makeGate() {
    const g = new THREE.Group();
    for (const x of [-66, -22, 22, 66]) {
      const p = new THREE.Mesh(new THREE.BoxGeometry(7, 36, 7), M.wood); p.position.set(x, 18, 0); g.add(p);
      const b = new THREE.Mesh(new THREE.BoxGeometry(11, 11, 22), M.booth); b.position.set(x, 5.5, 0); g.add(b);
      const l = new THREE.Mesh(new THREE.BoxGeometry(11.4, 1.4, 1.0), glow('#3DA9FF', 1.8)); l.position.set(x, 12.2, -11.2); g.add(l);
    }
    const beamM = new THREE.Mesh(new THREE.BoxGeometry(160, 4.5, 10), M.wood); beamM.position.y = 37; g.add(beamM);
    const roof = hanokRoof(176, 1.25, 1.0); roof.position.y = 39; g.add(roof);
    const lamps = [];
    for (const x of [-44, 0, 44]) { const m = new THREE.Mesh(new THREE.BoxGeometry(16, 1.0, 5), glow('#FFE2B0', 1.2)); m.position.set(x, 34.4, 0); g.add(m); lamps.push(m); }
    g.userData.lamps = lamps;
    return shadowed(g);
  }
  const gateA = W.anchors.gate;
  const gate = makeGate(); posAt(gateA.s, v3); gate.position.copy(v3); gate.rotation.y = headingAt(gateA.s); gate.scale.set(1.15, 1.25, 2.0); scene.add(gate);

  // ---------------------------------------------------------------- 고분, 첨성대, 계림, 동궁과 월지
  let seed = 7; const rnd = () => { seed = (seed * 1664525 + 1013904223) >>> 0; return seed / 4294967296; };
  function mound(x, z, r, h) { const m = new THREE.Mesh(new THREE.SphereGeometry(1, 40, 18, 0, Math.PI * 2, 0, Math.PI / 2), M.grass); m.scale.set(r, h, r); m.position.set(x, 0, z); m.castShadow = true; scene.add(m); return m; }
  const dr = W.anchors.daereungwon, cm = W.anchors.cheomseongdae, gy = W.anchors.gyerim, dg = W.anchors.donggung, ch = W.anchors.cheonmachong;
  const mounds = [[ch.x - 20, ch.z + 40, 62, 30], [ch.x + 90, ch.z + 10, 78, 36], [dr.x + 60, dr.z + 120, 70, 32], [dr.x - 40, dr.z + 150, 52, 24], [dr.x + 170, dr.z + 70, 58, 27], [ch.x - 110, ch.z + 120, 46, 21],
    [cm.x - 150, cm.z - 190, 60, 27], [cm.x - 20, cm.z - 230, 50, 23], [cm.x + 110, cm.z - 200, 66, 30], [cm.x + 230, cm.z - 150, 44, 20]];
  for (const m of mounds) mound(m[0], m[1], m[2], m[3]);
  function makeTower() {
    const g = new THREE.Group();
    const prof = [[15.5, 0], [15.5, 3], [15.0, 8], [13.9, 18], [12.3, 28], [10.7, 38], [9.7, 46], [9.4, 52], [9.6, 56]].map((p) => new THREE.Vector2(p[0], p[1]));
    g.add(new THREE.Mesh(new THREE.LatheGeometry(prof, 40), M.stone));
    const base = new THREE.Mesh(new THREE.BoxGeometry(38, 3, 38), M.stoneDark); base.position.y = 1.5; g.add(base);
    for (const [y, r] of [[57.2, 0], [59.6, Math.PI / 2]]) for (const o of [-7.5, 7.5]) { const b = new THREE.Mesh(new THREE.BoxGeometry(24, 2.3, 2.3), M.stone); b.position.set(r ? o : 0, y, r ? 0 : o); b.rotation.y = r; g.add(b); }
    const win = new THREE.Mesh(new THREE.BoxGeometry(4.2, 4.2, 2), new THREE.MeshStandardMaterial({ color: 0x23272b })); win.position.set(0, 30, 11.6); g.add(win);
    return shadowed(g);
  }
  const tower = makeTower(); tower.position.set(cm.x, 0, cm.z); tower.scale.setScalar(1.25); scene.add(tower);
  // 나무 (계림은 단풍 빛)
  const treeGeo = new THREE.IcosahedronGeometry(1, 1), trunkGeo = new THREE.CylinderGeometry(0.16, 0.22, 1, 6);
  const treeList = [];
  for (let i = 0; i < 22; i++) { const a = rnd() * Math.PI * 2, r = Math.sqrt(rnd()); treeList.push([gy.x + Math.cos(a) * r * 150, gy.z + Math.sin(a) * r * 95, 15 + rnd() * 9, ['#9A5A44', '#A9764A', '#7E463D', '#5D7350', '#B08D52'][Math.floor(rnd() * 5)]]); }
  for (let i = 0; i < 46; i++) { const m = mounds[Math.floor(rnd() * mounds.length)], a = rnd() * Math.PI * 2, r = m[2] * (1.25 + rnd() * 0.9); treeList.push([m[0] + Math.cos(a) * r, m[1] + Math.sin(a) * r, 11 + rnd() * 7, ['#3F6B4A', '#4C7A55', '#35583F'][Math.floor(rnd() * 3)]]); }
  for (let i = 0; i < 16; i++) { const a = rnd() * Math.PI * 2, r = 120 + rnd() * 110; treeList.push([dg.x + Math.cos(a) * r, dg.z + Math.sin(a) * r * 0.8, 12 + rnd() * 7, ['#3F6B4A', '#4C7A55'][Math.floor(rnd() * 2)]]); }
  const canopy = new THREE.InstancedMesh(treeGeo, new THREE.MeshStandardMaterial({ color: 0xb4b4b4, roughness: 1, flatShading: true }), treeList.length);
  const trunks = new THREE.InstancedMesh(trunkGeo, M.wood, treeList.length);
  const dummy = new THREE.Object3D(), col = new THREE.Color();
  treeList.forEach((t, i) => {
    dummy.position.set(t[0], t[2] * 1.25, t[1]); dummy.scale.set(t[2], t[2] * 1.15, t[2]); dummy.rotation.set(0, rnd() * 6, 0); dummy.updateMatrix(); canopy.setMatrixAt(i, dummy.matrix); canopy.setColorAt(i, col.set(t[3]));
    dummy.position.set(t[0], t[2] * 0.5, t[1]); dummy.scale.set(t[2] * 0.9, t[2] * 1.0, t[2] * 0.9); dummy.updateMatrix(); trunks.setMatrixAt(i, dummy.matrix);
  });
  canopy.castShadow = true; trunks.castShadow = true; scene.add(canopy, trunks);
  function makePavilion() {
    const g = new THREE.Group();
    const pond = new THREE.Mesh(new THREE.CircleGeometry(1, 48), M.water); pond.rotation.x = -Math.PI / 2; pond.scale.set(150, 100, 1); pond.position.set(40, 1.2, 60); g.add(pond);
    const base = new THREE.Mesh(new THREE.BoxGeometry(84, 6, 50), M.stoneDark); base.position.y = 3; g.add(base);
    for (const x of [-34, -17, 0, 17, 34]) for (const z of [-18, 18]) { const p = new THREE.Mesh(new THREE.BoxGeometry(3.2, 22, 3.2), M.wood); p.position.set(x, 17, z); g.add(p); }
    const roof = hanokRoof(96, 1.7); roof.position.y = 27; g.add(roof);
    const lampMat = glow('#FFD9A0', 0.25);
    const lamp = new THREE.Mesh(new THREE.BoxGeometry(80, 1.6, 44), lampMat); lamp.position.y = 26; g.add(lamp);
    g.userData.lampMat = lampMat;
    return shadowed(g);
  }
  const pav = makePavilion(); pav.position.set(dg.x + 40, 0, dg.z - 30); scene.add(pav);
  // 황리단길 한옥 몇 채
  const hw = W.anchors.hwangridan;
  for (let i = 0; i < (S.houses || 0); i++) {
    const h = new THREE.Group();
    const b = new THREE.Mesh(new THREE.BoxGeometry(30, 12, 20), M.stone); b.position.y = 6; h.add(b);
    const r = hanokRoof(36, 0.72); r.position.y = 12; h.add(r);
    h.position.set(hw.x - 120 + (i % 3) * 62 + rnd() * 14, 0, hw.z + 70 + Math.floor(i / 3) * 52 + rnd() * 10); h.rotation.y = (rnd() < 0.5 ? 0 : Math.PI / 2) + (rnd() - 0.5) * 0.2; scene.add(shadowed(h));
  }

  // ---------------------------------------------------------------- 요금소 뒤로 켜지는 등
  const LN = 46, lanterns = new THREE.InstancedMesh(new THREE.SphereGeometry(1, 12, 8), glow('#FFC878', 1.5), LN * 2);
  const lanS = [];
  for (let i = 0; i < LN; i++) lanS.push(gateA.s + 90 + i * 85);
  scene.add(lanterns);
  function setLanterns(goldT, big) {       // goldT: 금빛이 번지기 시작한 뒤 흐른 시간(초). 음수면 꺼짐
    const speed = (S.ring && S.ring.speed) || 900;
    for (let i = 0; i < LN; i++) {
      const s = lanS[i], on = clamp01((goldT - (s - gateA.s) / speed) / 0.35);
      const h = headingAt(s); posAt(s, v1);
      for (const sd of [1, -1]) {
        dummy.position.set(v1.x + Math.cos(h) * sd * 32 * big, 14, v1.z - Math.sin(h) * sd * 32 * big);
        dummy.scale.setScalar(on > 0 ? 5.5 * big * easeOutBack(on) : 0.0001); dummy.rotation.set(0, 0, 0); dummy.updateMatrix();
        lanterns.setMatrixAt(i * 2 + (sd > 0 ? 0 : 1), dummy.matrix);
      }
    }
    lanterns.instanceMatrix.needsUpdate = true;
  }

  // ---------------------------------------------------------------- 핀 (담은 곳, 기록)
  const pins = (S.pins || []).map((p) => {
    const g = new THREE.Group();
    const c = new THREE.Color(p.color || '#00AAD2');
    const h = p.h || 120;
    const stem = new THREE.Mesh(new THREE.CylinderGeometry(2.2, 2.2, h, 10), new THREE.MeshStandardMaterial({ color: 0xf2f4f6, roughness: 0.6 })); stem.position.y = h / 2; g.add(stem);
    const head = new THREE.Mesh(new THREE.SphereGeometry(p.r || 24, 28, 18), new THREE.MeshStandardMaterial({ color: c, roughness: 0.35, emissive: c, emissiveIntensity: 0.35 })); head.position.y = h + (p.r || 24) * 0.7; g.add(head);
    const dot = new THREE.Mesh(new THREE.CircleGeometry(p.saved ? 15 : 12, 24), new THREE.MeshBasicMaterial({ color: 0xffffff })); dot.rotation.x = -Math.PI / 2; dot.position.y = 3; g.add(dot);
    g.position.set(p.pos[0], 0, p.pos[1]); shadowed(g); scene.add(g);
    return { g, p, head };
  });

  // ---------------------------------------------------------------- 안내 방향 빛 (어느 쪽 창밖인지)
  const wedgeGeo = new THREE.BufferGeometry();
  { const seg = 24, half = rad(9), pos = [0, 0, 0], al = [0.75]; for (let i = 0; i <= seg; i++) { const a = -half + (2 * half) * i / seg; pos.push(Math.sin(a), 0, Math.cos(a)); al.push(0.0); } const idx = []; for (let i = 1; i <= seg; i++) idx.push(0, i, i + 1);
    wedgeGeo.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3)); wedgeGeo.setAttribute('aA', new THREE.Float32BufferAttribute(al, 1)); wedgeGeo.setIndex(idx); }
  const wedgeU = { uK: { value: 0 }, uC: { value: new THREE.Color('#BFEFFF') } };
  const wedge = new THREE.Mesh(wedgeGeo, new THREE.ShaderMaterial({ uniforms: wedgeU, transparent: true, depthWrite: false, blending: THREE.AdditiveBlending, side: THREE.DoubleSide,
    vertexShader: 'attribute float aA; varying float vA; void main(){ vA = aA; gl_Position = projectionMatrix * modelViewMatrix * vec4(position,1.0); }',
    fragmentShader: 'uniform float uK; uniform vec3 uC; varying float vA; void main(){ gl_FragColor = vec4(uC * 1.2, vA * uK); }' }));
  wedge.position.y = 3; wedge.renderOrder = 4; wedge.visible = false; scene.add(wedge);
  const halo = new THREE.Mesh(new THREE.RingGeometry(0.82, 1, 64), new THREE.MeshBasicMaterial({ color: 0xbfefff, transparent: true, opacity: 0, depthWrite: false, blending: THREE.AdditiveBlending, side: THREE.DoubleSide }));
  halo.rotation.x = -Math.PI / 2; halo.position.y = 3.5; halo.renderOrder = 4; scene.add(halo);

  // ---------------------------------------------------------------- 그리기
  const target = new THREE.WebGLRenderTarget(1920, 1080, { samples: 4, type: THREE.HalfFloatType });
  const composer = new EffectComposer(renderer, target);
  composer.addPass(new RenderPass(scene, cam));
  const bloom = new UnrealBloomPass(new THREE.Vector2(1920, 1080), 0.24, 0.45, 0.95); composer.addPass(bloom);
  composer.addPass(new OutputPass());
  const labels = (S.labels || []).map((l) => ({ l, el: document.querySelector(l.sel) })).filter((x) => x.el);
  const camKeys = S.cam || [{ t: 0, tx: 1500, tz: -1600, dist: 6000, pitch: 55, yaw: 0, fov: 30 }];
  const CF = ['tx', 'tz', 'ty', 'dist', 'pitch', 'yaw', 'fov', 'sx', 'sy', 'follow'];
  function camAt(t) {
    let a = camKeys[0], b = camKeys[camKeys.length - 1], k = 0;
    if (t <= a.t) b = a; else if (t >= b.t) a = b; else for (let i = 1; i < camKeys.length; i++) if (t <= camKeys[i].t) { a = camKeys[i - 1]; b = camKeys[i]; k = (t - a.t) / Math.max(1e-6, b.t - a.t); break; }
    if (a.cut || b.cutIn) k = 0;                       // cut: 다음 키까지 그대로 있다가 바로 넘어감
    k = k * k * (3 - 2 * k);
    const o = {};
    for (const f of CF) o[f] = (a[f] || 0) + ((b[f] || 0) - (a[f] || 0)) * k;
    return o;
  }
  const carPos = new THREE.Vector3(), sunOff = new THREE.Vector3(900, 1700, 700);

  function render(t) {
    dirty = t;
    const c = camAt(t);
    const far = Math.max(1, c.dist / 2400);                 // 멀리서 잡을수록 선, 핀, 등을 키워 화면에서 비슷한 굵기로 보이게
    // 차
    const s = track(S.car, t), h = headingAt(s);
    posAt(s, carPos);
    const lat = track(S.lat, t, true);
    carPos.x += Math.cos(h) * -lat; carPos.z += Math.sin(h) * lat;
    car.position.copy(carPos); car.rotation.y = h;
    const gold = clamp01(track(S.gold, t, true));
    const ud = car.userData;
    ud.glassMat.emissive.copy(BLUE).lerp(GOLD, gold).multiplyScalar(0.22 + gold * 0.2);
    ud.under.material.color.copy(BLUE).lerp(GOLD_HI, gold); ud.under.material.opacity = 0.22 + gold * 0.22;
    ud.beam.visible = !!S.night;
    for (const sd of ['L', 'R']) ud.sigMat[sd].color.setScalar(0);
    for (const sg of (S.signal || [])) if (t >= sg[0] && t < sg[1] && ((t - sg[0]) % 0.7) < 0.38) ud.sigMat[sg[2]].color.set('#FFB020').multiplyScalar(3.2);
    // 경로 선
    routeU.uHead.value = S.head ? track(S.head, t) : s;
    routeU.uDraw.value = S.draw ? track(S.draw, t, true) : 1e6;
    routeU.uFrom.value = S.from != null ? S.from : -1e6;
    routeU.uGold.value = gold;
    routeU.uAlpha.value = S.routeAlpha ? track(S.routeAlpha, t, true) : 1;
    routeU.uW.value = Math.max(S.routeWidth || 22, c.dist / 190);
    // 금빛 번짐
    const rt = S.ring ? t - S.ring.t : -1;
    if (S.ring && rt >= 0) { mapU.uRadius.value = rt * S.ring.speed; mapU.uGold.value = 1; mapU.uRing.value = clamp01(1 - rt * S.ring.speed / (S.ring.max || 5200)); }
    else if (S.goldAll) { mapU.uRadius.value = 1e6; mapU.uGold.value = gold; mapU.uRing.value = 0; }
    else { mapU.uRadius.value = -1; mapU.uGold.value = 0; mapU.uRing.value = 0; }
    mapU.uDim.value = S.dim ? track(S.dim, t, true) : 1;
    setLanterns(S.ring ? rt : (S.goldAll ? 99 : -1), Math.max(1, c.dist / 2000));
    for (const m of gate.userData.lamps) m.material.color.set('#FFE2B0').multiplyScalar(1.2 + gold * 1.6);
    pav.userData.lampMat.color.set('#FFD9A0').multiplyScalar(0.25 + (S.night || 0) * 2.2);
    sun.color.copy(COOL_SUN).lerp(WARM_SUN, gold); hemi.color.copy(COOL_SKY).lerp(WARM_SKY, gold);
    const dimK = mapU.uDim.value; sun.intensity = 1.7 * dimK; hemi.intensity = 1.0 * dimK;
    // 핀
    for (const pn of pins) {
      const k = clamp01((t - (pn.p.at || 0)) / 0.45), out = pn.p.out != null ? clamp01((t - pn.p.out) / 0.3) : 0;
      const sc = k <= 0 ? 0.0001 : easeOutBack(k) * (1 - out);
      pn.g.scale.setScalar(Math.max(0.0001, sc * far)); pn.g.visible = sc > 0.001;
      if (pn.p.pulse) pn.head.material.emissiveIntensity = 0.35 + 0.5 * clamp01(1 - Math.abs(((t - pn.p.pulse) % 1.4) / 0.7 - 1)) * (t >= pn.p.pulse ? 1 : 0);
    }
    // 안내 방향 빛
    if (S.beam) {
      const k = clamp01(track(S.beam.k, t, true));
      wedge.visible = k > 0.002; wedgeU.uK.value = k * 0.34;
      const dx = S.beam.target[0] - carPos.x, dz = S.beam.target[1] - carPos.z, d = Math.hypot(dx, dz);
      wedge.position.set(carPos.x, 3, carPos.z); wedge.rotation.y = Math.atan2(dx, dz); wedge.scale.set(d * 1.08, 1, d * 1.08);
      wedgeU.uC.value.set('#BFEFFF').lerp(GOLD_HI, gold);
      const pr = 70 + 26 * Math.sin(t * 2.4);
      halo.position.set(S.beam.target[0], 3.5, S.beam.target[1]); halo.scale.setScalar(pr); halo.material.opacity = k * 0.5; halo.material.color.copy(wedgeU.uC.value);
    }
    // 카메라
    const ax = c.tx + carPos.x * c.follow, az = c.tz + carPos.z * c.follow, ay = c.ty;
    const pt = rad(c.pitch), yw = rad(c.yaw);
    cam.position.set(ax + c.dist * Math.cos(pt) * Math.sin(yw), ay + c.dist * Math.sin(pt), az + c.dist * Math.cos(pt) * Math.cos(yw));
    cam.lookAt(ax, ay, az);
    cam.fov = c.fov || 30;
    cam.setViewOffset(1920, 1080, -c.sx, -c.sy, 1920, 1080);
    cam.updateProjectionMatrix(); cam.updateMatrixWorld();
    scene.fog.near = c.dist * 1.6; scene.fog.far = c.dist * 5.5;
    sun.target.position.set(ax, 0, az); sun.position.set(ax, 0, az).add(sunOff);
    const ex = Math.max(700, c.dist * 0.75);
    Object.assign(sun.shadow.camera, { left: -ex, right: ex, top: ex, bottom: -ex, near: 100, far: 6000 }); sun.shadow.camera.updateProjectionMatrix();
    composer.render();
    // 3D 위치에 붙는 글자
    for (const { l, el } of labels) {
      if (l.car) v1.copy(carPos).setY(l.y || 0); else v1.set(l.pos[0], l.pos[1], l.pos[2]);
      v1.project(cam);
      const x = (v1.x * 0.5 + 0.5) * 1920, y = (-v1.y * 0.5 + 0.5) * 1080;
      el.style.transform = 'translate(' + x.toFixed(1) + 'px,' + y.toFixed(1) + 'px)';
      el.style.visibility = v1.z < 1 ? 'visible' : 'hidden';
    }
  }
  window.addEventListener('hf-seek', (e) => render(Math.max(0, Number(e.detail && e.detail.time) || 0)));
  window.__film3d = { render };
  render(S.t0 || 0);
}

if (W && S && canvas) start();

