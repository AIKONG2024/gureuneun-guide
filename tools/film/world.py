#!/usr/bin/env python3
"""3D 지도 재료 만들기: 경로(미터 좌표), 장소 위치, 지도 텍스처
  python3 tools/film/world.py   →  tools/film/assets/world_data.js, map.jpg
좌표: 원점 = 경주 요금소, x = 동쪽(m), z = 남쪽(m). 지도는 앱이 쓰는 사전 렌더 타일(app/src/main/assets/tiles)을 이어 붙임
"""
import json, math, os
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
A = os.path.join(HERE, 'assets')
os.makedirs(A, exist_ok=True)
D = os.path.join(ROOT, 'companion/web/data')
g = json.load(open(os.path.join(D, 'gyeongju.json'), encoding='utf-8'))
ap = json.load(open(os.path.join(D, 'approach.json'), encoding='utf-8'))
seed = json.load(open(os.path.join(D, 'trip_seed.json'), encoding='utf-8'))
places = json.load(open(os.path.join(D, 'places.json'), encoding='utf-8'))
lat0, lon0 = g['gate']['lat'], g['gate']['lon']
KX = math.cos(math.radians(lat0)) * 111320.0
KZ = 110540.0


def xz(lat, lon):
    return ((lon - lon0) * KX, -(lat - lat0) * KZ)


def chaikin(pts):
    out = [pts[0]]
    for a, b in zip(pts, pts[1:]):
        out.append((a[0] * .75 + b[0] * .25, a[1] * .75 + b[1] * .25))
        out.append((a[0] * .25 + b[0] * .75, a[1] * .25 + b[1] * .75))
    out.append(pts[-1])
    return out


def resample(pts, step):
    out = [pts[0]]
    carry = 0.0
    for a, b in zip(pts, pts[1:]):
        d = math.hypot(b[0] - a[0], b[1] - a[1])
        if d < 1e-6:
            continue
        t = step - carry
        while t <= d:
            k = t / d
            out.append((a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k))
            t += step
        carry = (carry + d) % step
    return out


raw = [xz(p[0], p[1]) for p in ap] + [xz(p[0], p[1]) for p in g['route'][1:]]
STEP = 5.0
pts = resample(chaikin(chaikin(raw)), STEP)


def nearest(x, z, lo=0, hi=None):
    best = (1e18, 0)
    for i in range(lo, hi or len(pts)):
        d = (pts[i][0] - x) ** 2 + (pts[i][1] - z) ** 2
        if d < best[0]:
            best = (d, i)
    i = best[1]
    j = min(len(pts) - 1, i + 2)
    k = max(0, i - 2)
    tx, tz = pts[j][0] - pts[k][0], pts[j][1] - pts[k][1]
    cross = tx * (z - pts[i][1]) - tz * (x - pts[i][0])        # z 가 남쪽이라 cross > 0 이면 진행 방향 오른쪽
    return {'x': round(x, 1), 'z': round(z, 1), 's': round(i * STEP, 1), 'd': round(math.sqrt(best[0]), 1), 'side': 'R' if cross > 0 else 'L'}


anchors = {'gate': nearest(0.0, 0.0)}
for p in seed['places']:
    anchors[p['id']] = nearest(*xz(p['lat'], p['lon']))
for p in places if isinstance(places, list) else places.get('places', []):
    if p.get('id') == 'hwangridan':
        anchors['hwangridan'] = nearest(*xz(p['lat'], p['lon']))
for s in g['sights']:
    if s['id'] not in anchors:
        anchors[s['id']] = nearest(*xz(s['lat'], s['lon']))
# 경로가 대릉원 둘레를 한 바퀴 돌아서, 순서대로(앞 지점 뒤쪽에서) 가장 가까운 곳을 다시 찾음
order = ['lunch', 'hwangridan', 'daereungwon', 'cheomseongdae', 'donggung']
lo = int(anchors['gate']['s'] / STEP)
for k in order:
    if k in anchors:
        a = nearest(anchors[k]['x'], anchors[k]['z'], lo)
        anchors[k] = a
        lo = int(a['s'] / STEP)

# 지도 텍스처
meta = json.load(open(os.path.join(ROOT, 'app/src/main/assets/tiles/meta.json')))
T = meta['tile']
n = 2 ** meta['z']


def tile_lon(tx):
    return tx / n * 360.0 - 180.0


def tile_lat(ty):
    return math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * ty / n))))


big = Image.new('RGB', ((meta['txMax'] - meta['txMin'] + 1) * T, (meta['tyMax'] - meta['tyMin'] + 1) * T), meta.get('bg', '#161A1E'))
for tx in range(meta['txMin'], meta['txMax'] + 1):
    for ty in range(meta['tyMin'], meta['tyMax'] + 1):
        f = os.path.join(ROOT, 'app/src/main/assets/tiles/z%d/%d_%d.jpg' % (meta['z'], tx, ty))
        if os.path.exists(f):
            big.paste(Image.open(f).convert('RGB'), ((tx - meta['txMin']) * T, (ty - meta['tyMin']) * T))
wx0, wz0 = xz(tile_lat(meta['tyMin']), tile_lon(meta['txMin']))
wx1, wz1 = xz(tile_lat(meta['tyMax'] + 1), tile_lon(meta['txMax'] + 1))
xs = [p[0] for p in pts]
zs = [p[1] for p in pts]
M = 900.0
cx0, cx1 = max(wx0, min(xs) - M), min(wx1, max(xs) + M)
cz0, cz1 = max(wz0, min(zs) - M), min(wz1, max(zs) + M)
px = lambda x: (x - wx0) / (wx1 - wx0) * big.width
pz = lambda z: (z - wz0) / (wz1 - wz0) * big.height
crop = big.crop((int(px(cx0)), int(pz(cz0)), int(px(cx1)), int(pz(cz1))))
if crop.width > 4800:
    crop = crop.resize((4800, round(crop.height * 4800 / crop.width)), Image.LANCZOS)
crop.save(os.path.join(A, 'map.jpg'), quality=90)

W = {'step': STEP, 'pts': [[round(x, 1), round(z, 1)] for x, z in pts], 'len': round((len(pts) - 1) * STEP, 1), 'anchors': anchors,
     'map': {'src': '../assets/map.jpg', 'x0': round(cx0, 1), 'x1': round(cx1, 1), 'z0': round(cz0, 1), 'z1': round(cz1, 1)}, 'bg': meta.get('bg', '#161A1E')}
open(os.path.join(A, 'world_data.js'), 'w', encoding='utf-8').write('window.WORLD = ' + json.dumps(W, ensure_ascii=False) + ';\n')
json.dump(W, open(os.path.join(A, 'world.json'), 'w', encoding='utf-8'), ensure_ascii=False)
print('경로 %.0fm, 점 %d, 지도 %dx%d (%.0f x %.0f m)' % (W['len'], len(pts), crop.width, crop.height, cx1 - cx0, cz1 - cz0))
for k, v in anchors.items():
    print(' ', k, v)

