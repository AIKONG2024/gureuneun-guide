// Lucide 아이콘(ISC) 중 앱에서 쓰는 것만 골라 Kotlin 경로 자료로 만듦. 시안과 같은 선 아이콘을 차량 화면에 그리려고 씀
// 실행: node tools/assets/lucide_kotlin.cjs companion/web/vendor/lucide.min.js app/src/main/java/app/gureuneun/guide/ui/LucideData.kt
const fs = require('fs');
const src = fs.readFileSync(process.argv[2], 'utf8');
const m = { exports: {} };
const g = {};
const lib = new Function('module', 'exports', 'window', 'self', 'globalThis', src + '\n;return (typeof lucide!=="undefined"?lucide:module.exports);')(m, m.exports, g, g, g);
const icons = lib.icons || lib;
const pascal = (n) => n.split('-').map((s) => s[0].toUpperCase() + s.slice(1)).join('');
const want = {
  mic: 'mic', pin: 'map-pin', pinplus: 'map-pin-plus', landmark: 'landmark', cup: 'coffee', food: 'utensils',
  right: 'arrow-right', left: 'arrow-left', ahead: 'arrow-up', back: 'arrow-down',
  turnleft: 'corner-up-left', turnright: 'corner-up-right', straight: 'arrow-up',
  phone: 'smartphone', qr: 'qr-code', check: 'check', music: 'music', light: 'lamp-ceiling', screen: 'palette',
  send: 'send', nav: 'navigation', ticket: 'ticket', x: 'x', replay: 'rotate-ccw', stop: 'square', wave: 'audio-lines',
  bookmark: 'bookmark', route: 'route', film: 'film', more: 'ellipsis', car: 'car', flag: 'flag', photo: 'image',
  camera: 'camera', link: 'link', users: 'users', clock: 'clock', sparkles: 'sparkles', info: 'info', volume: 'volume-2',
  signal: 'signal', mappin: 'map-pin', share: 'share-2', download: 'download', play: 'play', pause: 'pause', plus: 'plus',
};
const num = (v) => Number(v);
function toD(tag, a) {
  switch (tag) {
    case 'path': return a.d;
    case 'circle': { const cx = num(a.cx), cy = num(a.cy), r = num(a.r); return `M${cx - r} ${cy}a${r} ${r} 0 1 0 ${2 * r} 0a${r} ${r} 0 1 0 ${-2 * r} 0z`; }
    case 'ellipse': { const cx = num(a.cx), cy = num(a.cy), rx = num(a.rx), ry = num(a.ry); return `M${cx - rx} ${cy}a${rx} ${ry} 0 1 0 ${2 * rx} 0a${rx} ${ry} 0 1 0 ${-2 * rx} 0z`; }
    case 'line': return `M${a.x1} ${a.y1}L${a.x2} ${a.y2}`;
    case 'polyline': case 'polygon': {
      const p = String(a.points).trim().split(/[\s,]+/).map(Number);
      let d = `M${p[0]} ${p[1]}`;
      for (let i = 2; i < p.length; i += 2) d += `L${p[i]} ${p[i + 1]}`;
      return tag === 'polygon' ? d + 'z' : d;
    }
    case 'rect': {
      const x = num(a.x || 0), y = num(a.y || 0), w = num(a.width), h = num(a.height);
      let rx = a.rx != null ? num(a.rx) : (a.ry != null ? num(a.ry) : 0);
      rx = Math.min(rx, w / 2, h / 2);
      if (!rx) return `M${x} ${y}h${w}v${h}h${-w}z`;
      return `M${x + rx} ${y}h${w - 2 * rx}a${rx} ${rx} 0 0 1 ${rx} ${rx}v${h - 2 * rx}a${rx} ${rx} 0 0 1 ${-rx} ${rx}h${-(w - 2 * rx)}a${rx} ${rx} 0 0 1 ${-rx} ${-rx}v${-(h - 2 * rx)}a${rx} ${rx} 0 0 1 ${rx} ${-rx}z`;
    }
  }
  throw new Error('모르는 요소 ' + tag);
}
const out = [];
out.push('package app.gureuneun.guide.ui');
out.push('');
out.push('// Lucide 아이콘 경로 (ISC License, https://lucide.dev). tools/assets/lucide_kotlin.cjs 로 만듦. 손으로 고치지 않음');
out.push('internal val LUCIDE: Map<String, List<String>> = mapOf(');
for (const [k, name] of Object.entries(want)) {
  const node = icons[pascal(name)];
  if (!node) throw new Error('없는 아이콘 ' + name);
  const ds = node.map(([tag, a]) => toD(tag, a));
  out.push(`    "${k}" to listOf(${ds.map((d) => JSON.stringify(d)).join(', ')}),`);
}
out.push(')');
fs.writeFileSync(process.argv[3], out.join('\n') + '\n');
console.log('아이콘', Object.keys(want).length, '개');

