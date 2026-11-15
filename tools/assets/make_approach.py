#!/usr/bin/env python3
# 경주 IC 요금소로 서울 방향(경부고속도로 하행)에서 들어오는 실제 도로선 추출 → companion/web/data/approach.json
# 입력: tools/assets/maprender/motorway_raw.json
#   render.html?mode=tiles 가 Aside 브라우저에서 OpenFreeMap 벡터 타일의 transportation 레이어를 읽어 저장한 것
#   (map.querySourceFeatures: 화면에 잘리지 않은 원래 선, oneway 속성 포함)
# 방법: 요금소 좌표에서 시작해 '일방통행 방향이 진행 방향과 같은' motorway 선을 5m씩 거슬러 올라감
#      → 고속도로 → 요금소 순으로 뒤집고, 요금소에서 1.5km 되는 지점부터 20m 간격으로 다시 찍음. 마지막 점은 요금소 좌표 그대로
# 사용: python3 tools/assets/make_approach.py [motorway_raw.json]
import json, math, os, sys
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
SRC = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, 'tools', 'assets', 'maprender', 'motorway_raw.json')
REG = json.load(open(os.path.join(ROOT, 'companion', 'web', 'data', 'gyeongju.json')))
GATE = (REG['gate']['lat'], REG['gate']['lon'])
LENGTH, SPACING, STEP = 1500.0, 20.0, 5.0
R = 6371000.0
K = math.cos(math.radians(GATE[0])) * math.pi / 180 * R
def xy(lat, lon): return ((lon - GATE[1]) * K, (lat - GATE[0]) * math.pi / 180 * R)
def ll(x, y): return (GATE[0] + y / (math.pi / 180 * R), GATE[1] + x / K)

segs = []
for f in json.load(open(SRC)):
    p = f['p']
    if p.get('class') != 'motorway' or p.get('oneway') != 1: continue
    g = f['g']
    for c in ([g['coordinates']] if g['type'] == 'LineString' else g['coordinates']):
        pts = [xy(la, lo) for lo, la in c]
        for a, b in zip(pts, pts[1:]):
            if math.hypot(b[0] - a[0], b[1] - a[1]) > 0.01: segs.append((a[0], a[1], b[0], b[1]))

def near(P, h, maxd, mindot):
    best = None
    for ax, ay, bx, by in segs:
        dx, dy = bx - ax, by - ay; L = math.hypot(dx, dy); ux, uy = dx / L, dy / L
        if ux * h[0] + uy * h[1] < mindot: continue
        t = max(0, min(L, (P[0] - ax) * ux + (P[1] - ay) * uy))
        q = (ax + ux * t, ay + uy * t); d = math.hypot(P[0] - q[0], P[1] - q[1])
        if d <= maxd and (best is None or d < best[0]): best = (d, q, (ux, uy))
    return best

r1 = xy(*REG['route'][1]); n0 = math.hypot(*r1); h0 = (r1[0] / n0, r1[1] / n0)   # 요금소를 지나는 진행 방향
b = near((0.0, 0.0), h0, 5.0, 0.3)
if b is None: sys.exit('요금소 근처에서 같은 방향 고속도로 선을 찾지 못함')
P, h = b[1], b[2]; path = [P]; total = 0.0
while total < LENGTH + 100:
    b = near((P[0] - h[0] * STEP, P[1] - h[1] * STEP), h, 6.0, 0.8)
    if b is None: break
    h = (h[0] + b[2][0], h[1] + b[2][1]); n = math.hypot(*h); h = (h[0] / n, h[1] / n)
    total += math.hypot(path[-1][0] - b[1][0], path[-1][1] - b[1][1]); P = b[1]; path.append(P)
if total < LENGTH: sys.exit(f'선이 {total:.0f}m 에서 끊김')
path = path[::-1]
cum = [0.0]
for a, c in zip(path, path[1:]): cum.append(cum[-1] + math.hypot(c[0] - a[0], c[1] - a[1]))
start = cum[-1] - LENGTH
def at(s):
    for i in range(1, len(cum)):
        if cum[i] >= s:
            u = (s - cum[i - 1]) / max(1e-9, cum[i] - cum[i - 1]); a, c = path[i - 1], path[i]
            return (a[0] + (c[0] - a[0]) * u, a[1] + (c[1] - a[1]) * u)
    return path[-1]
n = int(round(LENGTH / SPACING))
out = [at(start + LENGTH * k / n) for k in range(n)] + [(0.0, 0.0)]
pts = [[round(v, 6) for v in ll(x, y)] for x, y in out]
dst = os.path.join(ROOT, 'companion', 'web', 'data', 'approach.json')
with open(dst, 'w') as f:
    f.write('[\n' + ',\n'.join(json.dumps(p) for p in pts) + '\n]\n')
print(f'approach.json: {len(pts)}점, {LENGTH:.0f}m, {SPACING:.0f}m 간격, 시작 {pts[0]} → 요금소 {pts[-1]}')

