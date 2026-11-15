#!/usr/bin/env python3
"""구르는 가이드 시연 필름 v5 (HyperFrames + three.js)
  python3 tools/film/media.py /tmp/rec tools/film/assets     # 녹화 → MP4 재료
  python3 tools/film/world.py                                 # 3D 지도 재료 (경로, 지도 텍스처)
  python3 tools/film/build.py /tmp/rec                        # compositions/*.html, assets/audio.m4a
  python3 tools/film/render.py apk/구르는가이드_시연영상.mp4    # 장면별 렌더 → 이어 붙이기 → 소리 합치기

한 컷에 한 동작만 보여 준다. 휴대폰에서 담기 → 차 일정 → 버튼 하나로 출발 → 요금소 → 달리는 동안 → 도착과 기록.
차량·휴대폰 화면은 Pleos Connect 에뮬레이터와 휴대폰 웹 화면 실제 실행 녹화.
지도 장면은 앱이 쓰는 경주 지도와 경로를 three.js 로 그린 것(src/film3d.js).
차 실내·요금소·도로는 생성 이미지와 스톡 영상, 목소리는 AI 합성 음성(voice/), 음악은 Pixabay 음원.
"""
import html, json, math, os, re, shutil, subprocess, sys
from PIL import Image, ImageFilter
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
ARGS = [a for a in sys.argv[1:] if not a.startswith('--')]
REC = ARGS[0] if ARGS else '/tmp/rec'
A = os.path.join(HERE, 'assets')
FF = shutil.which('ffmpeg') or '/opt/homebrew/bin/ffmpeg'
FP = FF.replace('ffmpeg', 'ffprobe')
PLATES = os.path.join(HERE, 'plates')        # 차 실내(창은 초록), 요금소, 도로 생성 이미지
VOICE = os.path.join(HERE, 'voice')          # AI 합성 음성 (차 a01~a10, 아빠 b01, 둘째 c01)
MUSIC = os.environ.get('MUSIC', os.path.join(A, 'music.mp3'))      # Pixabay 'Chill Reel' (Kulakovka). 저장소에는 넣지 않음
ROAD = 'stock_road_1.mp4'                    # Pexels 'Vehicle Traveling on Roadway'. 저장소에는 넣지 않음
W, H = 1920, 1080


def run(cmd):
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode:
        print(' '.join(map(str, cmd)))
        print(r.stderr[-2000:])
        raise SystemExit(1)
    return r.stdout


def dur(path):
    return float(run([FP, '-v', 'error', '-show_entries', 'format=duration', '-of', 'default=nw=1:nk=1', path]).strip())


# ------------------------------------------------------------------ 녹화 시각 (모두 차량 녹화 시작 기준 초)
T0 = int(open(os.path.join(REC, 'car_start.txt')).read())
PH0 = int(open(os.path.join(REC, 'phone_start.txt')).read())
P_OFF = (PH0 - T0) / 1000.0           # 차량 시각 = 휴대폰 영상 시각 + P_OFF
EV = []
for line in open(os.path.join(REC, 'car', 'events.tsv'), encoding='utf-8'):
    p = line.rstrip('\n').split('\t')
    if len(p) >= 4:
        EV.append(((int(p[3]) - T0) / 1000.0, p[2]))
PEV = []
for line in open(os.path.join(REC, 'phone_events.tsv'), encoding='utf-8'):
    p = line.rstrip('\n').split('\t')
    if len(p) >= 2 and p[0].isdigit():
        PEV.append(((int(p[0]) - T0) / 1000.0, p[1]))


def ev(prefix, after=-1e9):
    return next(t for t, e in EV if e.startswith(prefix) and t >= after)


def pev(word):
    return next(t for t, e in PEV if word in e)


c_add, c_start, c_enter = ev('phone_add'), ev('navi:'), ev('enter:')
c_offer, c_reply = ev('offer:'), ev('reply:')
c_sight = ev('sight:cheomseongdae')
c_pause, c_resume = ev('pause:', c_sight), ev('resume', c_sight)
c_ask = ev('ask:')
c_arrive, c_record = ev('arrive'), ev('record')
c_recv = pev('알림 뜸')
p_tab, p_paste, p_tap = pev('담기 탭'), pev('붙여 넣기 누름'), pev('우리 여행에 담기 누름')      # 차량 시각
p_ticket = pev('여행 티켓 보이게') - P_OFF                                                    # 휴대폰 영상 시각

# ------------------------------------------------------------------ 재료
for d_ in ['photos', 'fonts']:
    os.makedirs(os.path.join(A, d_), exist_ok=True)
for w in ['regular', 'medium', 'semibold', 'bold', 'extrabold']:
    shutil.copy(os.path.join(ROOT, 'app/src/main/res/font/asta_%s.otf' % w), os.path.join(A, 'fonts'))
CREDITS = {c['file']: c for c in json.load(open(os.path.join(ROOT, 'companion/web/photos/credits.json'), encoding='utf-8'))}
PHOTOS = ['hwangridan_1.jpg', 'daereungwon_1.jpg', 'cheomseongdae_1.jpg', 'gyerim_1.jpg', 'donggung_night_1.jpg']
for f in PHOTOS:
    shutil.copy(os.path.join(ROOT, 'companion/web/photos', f), os.path.join(A, 'photos', f))


def credit(files):
    seen = []
    for f in files:
        c = CREDITS.get(f) or {}
        s = '%s (%s)' % (c.get('author', ''), c.get('license', ''))
        if s not in seen:
            seen.append(s)
    return '사진 ' + ', '.join(seen)


def key_cabin(name, out):
    """실내 이미지의 초록 창을 투명하게 (가장자리 초록 번짐도 지움)"""
    if os.path.exists(out):
        return
    im = Image.open(os.path.join(PLATES, name)).convert('RGB').resize((W, 1081), Image.LANCZOS)
    a = np.asarray(im).astype(np.float32)
    r, g, b = a[..., 0], a[..., 1], a[..., 2]
    green = g - np.maximum(r, b)
    alpha = 1.0 - np.clip((green - 30.0) / 80.0, 0, 1)
    g = np.where(green > 4, g - np.clip(green, 0, None) * np.clip((green - 4) / 30.0, 0, 1), g)
    img = Image.fromarray(np.dstack([r, g, b, alpha * 255]).clip(0, 255).astype(np.uint8), 'RGBA')
    img.putalpha(img.getchannel('A').filter(ImageFilter.MinFilter(3)).filter(ImageFilter.GaussianBlur(0.8)))
    img.save(out)


key_cabin('cabin_key.png', os.path.join(A, 'cabin_day.png'))
key_cabin('cabin_gold_key.png', os.path.join(A, 'cabin_gold.png'))
for n in ['gate', 'road']:
    if not os.path.exists(os.path.join(A, n + '.jpg')):
        Image.open(os.path.join(PLATES, n + '.png')).convert('RGB').resize((W, 1081), Image.LANCZOS).save(os.path.join(A, n + '.jpg'), quality=92)
if not os.path.exists(os.path.join(A, 'navi.jpg')):
    Image.open(os.path.join(REC, 'navi_route.png')).convert('RGB').crop((853, 90, 2535, 1300)).resize((840, 604), Image.LANCZOS).save(os.path.join(A, 'navi.jpg'), quality=92)
if not os.path.exists(os.path.join(A, 'navi_big.jpg')):      # 에뮬레이터 순정 내비게이션 창 (목적지 대릉원, 경유지 1개 포함)
    Image.open(os.path.join(REC, 'navi_route.png')).convert('RGB').crop((853, 90, 2535, 1300)).resize((1400, 1007), Image.LANCZOS).save(os.path.join(A, 'navi_big.jpg'), quality=92)
rec_shot = os.path.join(A, 'phone_record.jpg')          # 가족 휴대폰 화면: 휴대폰 '여행 기록' 한 장
if not os.path.exists(rec_shot):
    run([FF, '-y', '-ss', '%.2f' % (p_ticket + 0.5), '-i', os.path.join(REC, 'phone.webm'), '-frames:v', '1', '-vf', 'scale=600:-2', rec_shot])
SEGS = [(4.0, 8.4), (15.0, 16.9), (24.6, 26.5), (42.3, 44.1), (57.0, 61.0)]      # 여행 영상에서 보여 줄 구간
poster = os.path.join(A, 'reel_poster.jpg')
if not os.path.exists(poster):
    run([FF, '-y', '-ss', '%.2f' % SEGS[0][0], '-i', os.path.join(A, 'reel.mp4'), '-frames:v', '1', poster])
if not os.path.exists(os.path.join(A, 'world_data.js')):
    run([sys.executable, os.path.join(HERE, 'world.py')])
WORLD = json.load(open(os.path.join(A, 'world.json'), encoding='utf-8'))
AN = WORLD['anchors']
js_src, js_out = os.path.join(HERE, 'src', 'film3d.js'), os.path.join(A, 'film3d.js')
if not os.path.exists(js_out) or os.path.getmtime(js_out) < os.path.getmtime(js_src):
    esb = os.environ.get('ESBUILD') or shutil.which('esbuild')
    cmd = ([esb] if esb else ['npx', '--yes', 'esbuild']) + [js_src, '--bundle', '--format=iife', '--outfile=' + js_out,
                                                              '--alias:three=' + os.path.join(HERE, 'vendor/three/build/three.module.js')]
    run(cmd)

# ------------------------------------------------------------------ 목소리와 효과음
CUES = []          # (시각, 파일, 볼륨, 앞쪽 필터)


def say(t, key, vol=1.25):
    f = os.path.join(VOICE, key + '.mp3')
    CUES.append((t, f, vol, ''))
    return dur(f)


def fx(t, kind, vol):
    CUES.append((t, 'sfx:' + kind, vol, ''))


# ------------------------------------------------------------------ HTML 조각 (@A@ 는 재료 폴더 자리)
def esc(s):
    return html.escape(s, quote=False)


VID = [0]


def video(src, start, d, media, rate=1.0, cls='', style=''):
    VID[0] += 1
    extra = (' data-playback-rate="%g"' % rate) if abs(rate - 1) > 1e-3 else ''
    return ('<video id="v%02d" class="clip %s" src="@A@/%s" data-start="%.3f" data-duration="%.3f" data-media-start="%.3f"%s muted playsinline style="%s"></video>'
            % (VID[0], cls, src, start, d, media, extra, style))


def anim(*parts):
    return 'animation:' + ', '.join(parts) + ';'


def sub(t, d, text, who=None, s0=0.0, ink=False, inst=False):
    """자막: 차가 하는 말은 아래 얇은 빛 선, 사람 말은 앞에 흐린 이름. inst 는 앞 장면에서 이어지는 말"""
    cls = ('car' if who is None else 'person') + (' ink' if ink else '')
    lead = '' if who is None else '<em>%s</em>' % esc(who)
    a = ['reveal .01s linear %.3fs both' % (t - s0)] if inst else ['subIn .3s ease %.3fs both' % (t - s0)]
    a.append('fadeOut .25s ease %.3fs forwards' % (t - s0 + d))
    return '<div class="sub %s" style="%s"><span>%s%s</span></div>' % (cls, anim(*a), lead, esc(text))


def listen(t, d, s0=0.0):
    return '<div class="listen" style="%s"></div>' % anim('listenIn .35s ease %.3fs both' % (t - s0), 'fadeOut .3s ease %.3fs forwards' % (t - s0 + d))


def cam(name, d, stops, k=0.55):
    """기기 카드 장면의 카메라. stops=[(시각, 초점x, 초점y, 배율)] — 초점이 화면 가운데 쪽으로 k 만큼 오도록 당기고 확대"""
    rules = []
    for tt, fx_, fy, s in stops:
        tx = fx_ + k * (960 - fx_) - s * fx_
        ty = fy + k * (540 - fy) - s * fy
        rules.append('%.2f%%{transform:translate(%.1fpx,%.1fpx) scale(%.4f);animation-timing-function:cubic-bezier(.45,0,.25,1)}' % (min(100.0, tt / d * 100), tx, ty, s))
    return '<style>@keyframes %s{%s}</style>' % (name, ''.join(rules)), 'transform-origin:0 0;animation:%s %.3fs linear 0s both' % (name, d)


def chapter(title, at=0.35, hold=3.2, ink=False):
    return ('<div class="chap%s" style="%s">%s</div>'
            % (' ink' if ink else '', anim('chapIn .6s cubic-bezier(.2,.8,.2,1) %.2fs both' % at, 'fadeOut .5s ease %.2fs forwards' % (at + hold)), esc(title)))


def tap(x, y, t):
    return '<i class="tap" style="left:%.0fpx;top:%.0fpx;%s"></i>' % (x, y, anim('tap .75s ease-out %.2fs both' % t))


def step(n, text, t_in, t_out, style, cls=''):
    return ('<div class="stp %s" style="%s;%s"><b>%s</b>%s</div>'
            % (cls, style, anim('subIn .4s ease %.2fs both' % t_in, 'fadeOut .3s ease %.2fs forwards' % t_out), n, esc(text)))


# 차량 녹화 car.mp4 는 2560→1920 축소본. 앱 영역은 (640,68)~(1901,975): 왼쪽 절반 지도, 오른쪽 절반 안내 패널
def car_card(cw, start, d, media, rate=1.0):
    s = cw / 1261.5
    st = 'width:%.1fpx;height:%.1fpx;left:%.1fpx;top:%.1fpx' % (1920 * s, 1080 * s, -639.75 * s, -67.5 * s)
    return video('car.mp4', start, d, media, rate, 'cv', st), cw, 907.5 * s


def panel_card(pw, start, d, media, rate=1.0):
    s = pw / 630.75
    st = 'width:%.1fpx;height:%.1fpx;left:%.1fpx;top:%.1fpx' % (1920 * s, 1080 * s, -1270.5 * s, -67.5 * s)
    return video('car.mp4', start, d, media, rate, 'cv', st), pw, 907.5 * s


def phone_card(pw, start, d, media):
    return video('phone.mp4', start, d, media, 1.0, 'pv', 'width:%dpx;height:%.1fpx' % (pw, pw * 1688 / 780)), pw, pw * 1688 / 780


def inset(start, d, media, t_in=0.3, t_out=None, pw=540, left=1300, top=108):
    """3D 지도 장면 오른쪽에 띄우는 차량 화면(안내 패널)"""
    v, pw, ph = panel_card(pw, start, d, media)
    a = ['insetIn .7s cubic-bezier(.2,.8,.2,1) %.2fs both' % t_in]
    if t_out is not None:
        a.append('insetOut .5s ease %.2fs forwards' % t_out)
    return '<div class="card car inset" style="left:%dpx;top:%dpx;width:%dpx;height:%.0fpx;%s">%s<i class="sheen"></i></div>' % (left, top, pw, ph, anim(*a), v)


# ---- 3D 지도 장면
def gl(cfg):
    """canvas 와 스크립트. cfg 는 src/film3d.js 의 SCENE3D"""
    tail = ('<script src="@A@/world_data.js"></script>\n<script>window.WORLD.map.src="@A@/map.jpg";window.SCENE3D=%s;</script>\n<script src="@A@/film3d.js"></script>'
            % json.dumps(cfg, ensure_ascii=False))
    return '<canvas id="gl" width="1920" height="1080"></canvas><div class="shade"></div>', tail


def l3(lid, inner, t_in, t_out=None, cls='', a_in='l3In'):
    """3D 위치를 따라다니는 글자 (창밖 HUD 라벨처럼 글자와 가는 선)"""
    a = ['%s .5s cubic-bezier(.2,.8,.2,1) %.2fs both' % (a_in, t_in)]
    if t_out is not None:
        a.append('fadeOut .3s ease %.2fs forwards' % t_out)
    return '<div class="l3 %s" id="%s"><div class="in" style="%s">%s<span class="ln"></span></div></div>' % (cls, lid, anim(*a), inner)


def project(c, P):
    """film3d.js 와 같은 카메라 식으로 3D 점이 화면 어디에 오는지 (카메라가 멈춘 구간에서 DOM 을 맞출 때)"""
    p, y = math.radians(c['pitch']), math.radians(c.get('yaw', 0))
    T = (c['tx'], c.get('ty', 0), c['tz'])
    C = (T[0] + c['dist'] * math.cos(p) * math.sin(y), T[1] + c['dist'] * math.sin(p), T[2] + c['dist'] * math.cos(p) * math.cos(y))
    f = [T[i] - C[i] for i in range(3)]
    n = math.sqrt(sum(v * v for v in f))
    f = [v / n for v in f]
    r = [f[1] * 0 - f[2] * 1, f[2] * 0 - f[0] * 0, f[0] * 1 - f[1] * 0]
    n = math.sqrt(sum(v * v for v in r))
    r = [v / n for v in r]
    u = [r[1] * f[2] - r[2] * f[1], r[2] * f[0] - r[0] * f[2], r[0] * f[1] - r[1] * f[0]]
    d_ = [P[i] - C[i] for i in range(3)]
    xc, yc, zc = sum(d_[i] * r[i] for i in range(3)), sum(d_[i] * u[i] for i in range(3)), sum(d_[i] * f[i] for i in range(3))
    th = math.tan(math.radians(c.get('fov', 30)) / 2)
    return ((xc / (zc * th * 16 / 9)) * 0.5 + 0.5) * 1920 + c.get('sx', 0), (-(yc / (zc * th)) * 0.5 + 0.5) * 1080 + c.get('sy', 0)


def xz(k):
    return [AN[k]['x'], AN[k]['z']]


MOM, DAD, KID1 = '#C2416B', '#2F6FD0', '#2E9C78'
SCENES = []


def scene(sid, start, d, body, cls='', tail=''):
    d = round(d * 30) / 30.0                 # 장면 길이를 프레임(1/30초) 단위로 맞춰 이어 붙여도 소리와 어긋나지 않게
    SCENES.append((sid, start, d, body, cls, tail))
    return round((start + d) * 30) / 30.0


MARK = ('<svg class="mark" viewBox="0 0 120 120"><rect width="120" height="120" rx="34" fill="#0B4A8F"/>'
        '<path d="M24 52 q9 -10 18 0 t18 0 t18 0 t18 0" stroke="#00AAD2" stroke-width="9" fill="none" stroke-linecap="round"/>'
        '<path d="M24 76 q9 -10 18 0 t18 0 t18 0 t18 0" stroke="#BFE6F2" stroke-width="9" fill="none" stroke-linecap="round"/></svg>')

# ================================================================== 장면
t = 0.0
# ---- S1 제목
body = ('<div class="studio"></div><div class="lock ink" style="%s">%s<div><h1>구르는 가이드</h1><p>내 차의 여행 모드</p></div></div>'
        % (anim('lockIn .8s cubic-bezier(.2,.8,.2,1) .25s both', 'fadeOut .45s ease 2.9s forwards'), MARK))
fx(0.3, 'chime', 0.2)
t = scene('s1', t, 3.4, body)

# ---- S1b 지금까지는: 휴대폰에서 찾은 곳을 내비게이션에 하나씩 다시 입력 (설명용 그림)
s1b = t
d1b = 5.8
UT = ('<svg viewBox="0 0 24 24" width="36" height="36" fill="none" stroke="#8A5A3C" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">'
      '<path d="M7 3v7a2 2 0 0 0 4 0V3M9 12v9M16 3c-1.5 2-2 4-2 7h3v11"/></svg>')
MAG = '<svg viewBox="0 0 24 24" fill="none" stroke="#8A939C" stroke-width="2" stroke-linecap="round"><circle cx="11" cy="11" r="7"/><path d="M20 20l-3.5-3.5"/></svg>'
found = [('<span class="ic">%s</span>' % UT, '황남동 쌈밥집', '인스타그램에서 저장', 0.9, 2.2, 0.9), ('<img src="@A@/photos/daereungwon_1.jpg">', '대릉원', '지도 앱에서 저장', 2.2, 3.4, 0.5),
         ('<img src="@A@/photos/hwangridan_1.jpg">', '황리단길 카페', '블로그 글 캡처', 3.4, 9.9, 0.9)]
items, fields = '', ''
for (pic, name, src_, on, off, ty) in found:
    items += ('<div class="mk-it" style="%s">%s<div><b>%s</b><small>%s</small></div></div>'
              % (anim('mkOn .3s ease %.2fs both' % on, 'mkOff .3s ease %.2fs forwards' % off), pic, name, src_))
    fields += ('<div class="mk-f" style="%s">%s<span class="ty" style="%s">%s</span></div>'
               % (anim('fIn .25s ease %.2fs both' % on, 'fDone .3s ease %.2fs forwards' % off), MAG,
                  anim('typeIn %.2fs steps(%d) %.2fs both' % (ty, len(name), on + 0.1)), name))
    CUES.append((s1b + on + 0.1, 'sfx:keys', 0.26, 'atrim=0:%.2f,' % ty))
kcss, kst = cam('cam_s1b', d1b, [(0, 960, 540, 1.0), (d1b, 1000, 540, 1.05)])
body = '<div class="studio"></div>' + chapter('지금까지는', ink=True, hold=4.6) + kcss
body += '<div class="stage" style="%s">' % kst
body += ('<div class="card phone" style="left:380px;top:92px;width:404px;height:874px;%s"><div class="bezel"><div class="mk-ph"><div class="mk-h">찾아 둔 곳</div>%s</div></div></div>'
         % (anim('riseIn .7s cubic-bezier(.2,.8,.2,1) .1s both'), items))
body += ('<div class="card car" style="left:920px;top:206px;width:900px;height:647px;%s"><div class="mk-nv"><div class="mk-nh">내비게이션 목적지 검색</div>%s<div class="mk-kb">%s</div></div></div>'
         % (anim('slideIn .7s cubic-bezier(.2,.8,.2,1) .3s both'), fields, '<i></i>' * 30))
body += step('', '휴대폰에서 찾고', 0.6, d1b + 1, 'right:%dpx;top:230px' % (1920 - 380 + 22), 'r')
body += step('', '내비게이션에 하나씩 다시 입력', 1.1, d1b + 1, 'left:1180px;top:928px', 'dn')
body += '</div>'
body += '<div class="credit3 ink">%s</div>' % esc(credit(['daereungwon_1.jpg', 'hwangridan_1.jpg']))
t = scene('s1b', s1b, d1b, body)

# ---- S2 떠나기 전: 휴대폰에 링크를 붙여 넣고 [우리 여행에 담기] → 차 일정에 바로 담김
s2 = t
c0 = p_tab - 0.9                       # 이 장면 0초의 차량 시각
L = lambda c: c - c0
d2 = 10.6
PX, PY, PW = 380, 92, 404              # 휴대폰 카드
pv, pw, ph = phone_card(PW, s2, d2, c0 - P_OFF)
ox, oy = PX + 11, PY + 11
CX, CY, CW = 920, 206, 900             # 차량 화면 카드
cv, cw, ch = car_card(CW, s2, d2, c0)
qx, qy = CX + 10, CY + 10
t_paste, t_tap, t_add = L(p_paste), L(p_tap), L(c_add)
row = (qx + 0.52 * cw - 6, qy + 0.58 * ch - 5, 0.45 * cw + 12, 0.08 * ch + 10)        # 차 일정에 새로 생긴 줄
bx, by = ox + 0.5 * pw, oy + 0.565 * ph                                              # [우리 여행에 담기] 버튼
kcss, kst = cam('cam_s2', d2, [(0, 960, 540, 1.0), (0.8, 960, 540, 1.0), (1.7, 700, 290, 1.30), (t_tap - 0.9, 700, 320, 1.30), (t_tap - 0.2, 660, 560, 1.30),
                               (t_tap + 0.25, 660, 560, 1.30), (t_add + 0.6, 1040, 560, 1.0), (t_add + 1.6, 1040, 560, 1.0), (t_add + 2.8, 1590, 600, 1.36), (d2, 1590, 606, 1.40)])
path = 'M %.0f %.0f C %.0f %.0f, %.0f %.0f, %.0f %.0f' % (bx + 150, by, bx + 330, by - 150, row[0] - 220, row[1] - 110, row[0] - 6, row[1] + row[3] / 2)
body = '<div class="studio"></div>' + chapter('구르는 가이드는', ink=True) + kcss
body += '<div class="stage" style="%s">' % kst
body += ('<div class="card phone" style="left:%dpx;top:%dpx;width:%dpx;height:%.0fpx;%s"><div class="bezel">%s</div></div>'
         % (PX, PY, pw, ph, anim('riseIn .8s cubic-bezier(.2,.8,.2,1) .1s both'), pv))
body += ('<div class="card car" style="left:%dpx;top:%dpx;width:%dpx;height:%.0fpx;%s">%s<i class="sheen"></i></div>'
         % (CX, CY, cw, ch, anim('slideIn .8s cubic-bezier(.2,.8,.2,1) %.2fs both' % (t_tap - 0.1)), cv))
body += tap(ox + 0.82 * pw, oy + 0.153 * ph, t_paste) + tap(bx, by, t_tap)
body += step('1', '찾은 곳 링크 붙여 넣기', t_paste - 0.6, t_tap - 0.5, 'right:%dpx;top:%.0fpx' % (1920 - PX + 22, oy + 0.153 * ph - 20), 'r')
body += step('2', '우리 여행에 담기', t_tap - 0.4, t_add + 1.8, 'right:%dpx;top:%.0fpx' % (1920 - PX + 22, by - 20), 'r')
body += ('<svg class="link" viewBox="0 0 1920 1080"><path d="%s" pathLength="1" style="%s"/></svg>'
         % (path, anim('draw .6s cubic-bezier(.5,0,.3,1) %.2fs forwards' % t_tap, 'fadeOut .5s ease %.2fs forwards' % (t_add + 2.0))))
body += '<i class="dot" style="offset-path:path(\'%s\');%s"></i>' % (path, anim('ride .6s cubic-bezier(.5,0,.3,1) %.2fs both' % t_tap))
body += '<i class="hl" style="left:%.0fpx;top:%.0fpx;width:%.0fpx;height:%.0fpx;%s"></i>' % (row[0], row[1], row[2], row[3], anim('hlIn .5s cubic-bezier(.2,.8,.2,1) %.2fs both' % (t_add + 0.1)))
body += step('3', '차 일정에 바로 담김', t_add + 0.3, t_add + 3.5, 'left:%.0fpx;top:%.0fpx' % (row[0] + 6, row[1] + row[3] + 12), 'lt')
body += step('', '가족이 각자 담은 곳이 한 일정으로', t_add + 3.7, d2 + 1, 'left:%.0fpx;top:%.0fpx' % (row[0] + 6, row[1] + row[3] + 12), 'lt')
for i_, (who_, src_, yy) in enumerate([('엄마', '인스타그램에서', 0.245), ('아빠', '지도 앱에서', 0.34), ('첫째', '블로그에서', 0.44)]):      # 일정 줄마다 누가 어디서 찾아 담았는지
    body += ('<div class="who" style="right:%.0fpx;top:%.0fpx;%s">%s<small>%s</small></div>'
             % (1920 - (qx + 0.5 * cw - 8), qy + yy * ch - 13, anim('subIn .4s ease %.2fs both' % (t_add + 3.0 + i_ * 0.45)), who_, src_))
    fx(s2 + t_add + 3.0 + i_ * 0.45, 'pop', 0.16)
body += '</div>'
fx(s2 + t_paste, 'pop', 0.3)
fx(s2 + t_tap, 'pop', 0.34)
fx(s2 + t_tap + 0.05, 'whoosh_s', 0.3)
fx(s2 + t_add + 0.05, 'ding', 0.3)
t = scene('s2', s2, d2, body)

# ---- S3 출발: 버튼 하나 → 순정 내비에 목적지와 경유지
s3 = t
c0 = c_start - 2.1
t_go = c_start - c0
d3 = t_go + 0.75
CX, CY, CW = 360, 96, 1180
cv, cw, ch = car_card(CW, s3, d3, c0)
qx, qy = CX + 10, CY + 10
gx, gy = qx + 0.86 * cw, qy + 0.91 * ch
kcss, kst = cam('cam_s3', d3, [(0, 960, 540, 1.0), (0.4, 960, 540, 1.0), (1.5, gx - 60, gy - 60, 1.4), (d3, gx - 60, gy - 60, 1.4)])
body = '<div class="studio"></div>' + chapter('출발', ink=True) + kcss
body += ('<div class="stage" style="%s"><div class="card car" style="left:%dpx;top:%dpx;width:%dpx;height:%.0fpx;%s">%s<i class="sheen"></i></div>'
         % (kst, CX, CY, cw, ch, anim('riseIn .7s cubic-bezier(.2,.8,.2,1) 0s both'), cv))
body += tap(gx, gy, t_go)
body += step('', '버튼 하나로 출발', 0.9, t_go + 0.6, 'left:%.0fpx;top:%.0fpx' % (gx - 118, CY + ch + 20 + 44), 'dn')
body += '</div>'
A03 = '대릉원으로 안내를 시작할게요. 점심 식당을 경유지로 넣었어요'
La = say(s3 + t_go + 0.3, 'a03')
body += sub(t_go + 0.3, d3, A03, ink=True)            # 다음 장면으로 이어짐
fx(s3 + t_go, 'pop', 0.34)
t = scene('s3', s3, d3, body)

# ---- S3n 누르면 순정 내비게이션에 목적지와 경유지가 입력 없이 들어감 (에뮬레이터 내비게이션 실제 화면)
s3n = t
d3n = 3.8
NX, NY, NW = 440, 118, 1120
ns = NW / 1682.0
ix, iy = NX + 10, NY + 10
kcss, kst = cam('cam_s3n', d3n, [(0, 960, 540, 1.0), (0.5, 960, 540, 1.0), (d3n, 760, 330, 1.16)])
body = '<div class="studio"></div>' + chapter('순정 내비게이션으로', ink=True) + kcss
body += ('<div class="stage" style="%s"><div class="card" style="left:%dpx;top:%dpx;width:%dpx;height:%.0fpx;%s"><img src="@A@/navi_big.jpg" style="position:absolute;left:0;top:0;width:100%%;height:100%%"></div>'
         % (kst, NX, NY, NW, 1210 * ns, anim('riseIn .6s cubic-bezier(.2,.8,.2,1) 0s both')))
body += ('<i class="hl" style="left:%.0fpx;top:%.0fpx;width:%.0fpx;height:%.0fpx;%s"></i>'
         % (ix + 25 * ns - 5, iy + 25 * ns - 4, 577 * ns + 10, 178 * ns + 8, anim('hlIn .5s cubic-bezier(.2,.8,.2,1) .7s both')))
body += step('', '목적지 대릉원', 0.8, d3n + 1, 'right:%dpx;top:%.0fpx' % (1920 - NX + 14, iy + 74 * ns - 20), 'r')
body += step('', '경유지 점심 식당', 1.2, d3n + 1, 'right:%dpx;top:%.0fpx' % (1920 - NX + 14, iy + 163 * ns - 20), 'r')
body += '</div>'
body += sub(0, (s3 + t_go + 0.3 + La) - s3n + 0.1, A03, ink=True, inst=True)
fx(s3n, 'whoosh_s', 0.3)
fx(s3n + 0.7, 'ding', 0.26)
t = scene('s3n', s3n, d3n, body)

# ---- S3b 지도: 담아 둔 일정이 핀으로 꽂히고, 경로가 그려지고, 차가 출발
s3b = t
d3b = 5.2
rest = (s3 + t_go + 0.3 + La) - s3b
FARV = {'tx': 1300, 'tz': -1700, 'dist': 7400, 'pitch': 60, 'yaw': 0, 'fov': 30}
PH = 480                               # 멀리서 본 핀 머리 높이(m)
cfg = {'car': [[0, 120], [d3b, 1000]], 'gold': [[0, 0]], 'draw': [[0.5, 0], [2.9, WORLD['len']]],
       'pins': [{'pos': xz('lunch'), 'color': MOM, 'at': 0.3, 'r': 17, 'h': 90}, {'pos': xz('daereungwon'), 'color': DAD, 'at': 0.6, 'r': 17, 'h': 90},
                {'pos': xz('donggung'), 'color': KID1, 'at': 0.9, 'r': 17, 'h': 90}, {'pos': xz('hwangridan'), 'color': MOM, 'at': 1.3, 'r': 17, 'h': 90, 'saved': 1}],
       'labels': [{'sel': '#p1', 'pos': [AN['lunch']['x'], PH, AN['lunch']['z']]}, {'sel': '#p2', 'pos': [AN['daereungwon']['x'], PH, AN['daereungwon']['z']]},
                  {'sel': '#p3', 'pos': [AN['donggung']['x'], PH, AN['donggung']['z']]}, {'sel': '#p4', 'pos': [AN['hwangridan']['x'], PH, AN['hwangridan']['z']]},
                  {'sel': '#p5', 'car': 1, 'y': 130}],
       'cam': [dict(FARV, t=0, dist=7900), dict(FARV, t=d3b, dist=6900, tx=1400, tz=-1800)]}
cv_, tail = gl(cfg)
body = cv_
body += l3('p1', '<span class="t"><small>11:40</small>점심</span>', 0.5, cls='sl', a_in='fadeIn')
body += l3('p2', '<span class="t"><small>13:00</small>대릉원</span>', 0.8, cls='sr', a_in='fadeIn')
body += l3('p3', '<span class="t"><small>17:40</small>동궁과 월지</span>', 1.1, cls='sr', a_in='fadeIn')
body += l3('p4', '<span class="t"><i>방금 담음</i>황리단길 카페</span>', 1.5, cls='sr', a_in='fadeIn')
body += l3('p5', '<span class="t">출발</span>', 3.0)
body += sub(0, rest + 0.1, A03, inst=True)
fx(s3b, 'whoosh', 0.3)
for i_, at_ in enumerate([0.3, 0.6, 0.9, 1.3]):
    fx(s3b + at_ + 0.1, 'pop', 0.16)
t = scene('s3b', s3b, d3b, body, tail=tail)

# ---- S4 여행지에 들어서면: 조명이 낮아지고 전주 → 요금소를 지나는 순간 실내가 금빛으로, 가야금
s4 = t
cA = 24.0                              # 접근 구간은 2배속으로
HIT = (c_enter - cA) / 2.0
d4 = HIT + 3.5
SX, SY, SW, SH = 836, 456, 447, 241    # 실내 이미지 속 화면 자리
ss = SW / 1920.0
ss = SW / 1280.0                      # 화면에는 앱 영역(지도와 안내 패널)만
sv = 'width:%.1fpx;height:%.1fpx;left:%.1fpx;top:%.1fpx' % (1920 * ss, 1080 * ss, -640 * ss, -68 * ss)
G0 = 3.9                               # 요금소가 보이기 시작
win = video(ROAD, s4, G0 + 0.7, 6.0, 1.0, 'wv', 'top:-394px;height:1080px')
win += '<img src="@A@/gate.jpg" class="bl" style="top:-312px;height:1081px;opacity:0;%s">' % anim('fadeIn .6s ease %.2fs both' % G0)
win += ('<img src="@A@/gate.jpg" style="top:-312px;height:1081px;transform-origin:50%% 64%%;opacity:0;%s">'
        % anim('fadeIn .6s ease %.2fs both' % G0, 'gatePush %.2fs cubic-bezier(.6,0,.9,.55) %.2fs both' % (HIT - G0, G0)))
win += ('<img src="@A@/road.jpg" style="top:-276px;height:1081px;transform-origin:46.5%% 57%%;opacity:0;%s">'
        % anim('reveal .01s linear %.2fs forwards' % HIT, 'roadPush %.2fs linear %.2fs both' % (d4 - HIT + 0.1, HIT)))
inside = '<div class="win">%s</div>' % win
inside += '<img class="cab" src="@A@/cabin_day.png" style="%s">' % anim('cabDim %.2fs ease-in-out 1.6s both' % (HIT - 1.9))
inside += '<img class="cab" src="@A@/cabin_gold.png" style="opacity:0;%s">' % anim('fadeIn .22s ease %.2fs forwards' % HIT)
inside += ('<div class="scr" style="left:%dpx;top:%dpx;width:%dpx;height:%dpx">%s%s<i class="gl"></i></div>'
           % (SX, SY, SW, SH, video('car.mp4', s4, HIT, cA, 2.0, 'sv', sv), video('car.mp4', s4 + HIT, d4 - HIT, c_enter, 1.0, 'sv', sv)))
body = ('<div class="cam" style="transform-origin:960px 420px;%s">%s</div>'
        % (anim('push4 %.2fs linear 0s both' % HIT, 'bump %.2fs cubic-bezier(.2,.7,.2,1) %.2fs forwards' % (d4 - HIT, HIT)), inside))
body += ('<div class="ar" style="left:600px;top:84px;%s"><span class="t">경주 요금소<small>280m</small></span><span class="ln" style="height:56px"></span><span class="ring"></span></div>'
         % anim('arIn .6s cubic-bezier(.2,.8,.2,1) %.2fs both' % (G0 + 0.5), 'fadeOut .3s ease %.2fs forwards' % (HIT - 1.6)))
body += '<div class="boom" style="%s"></div>' % anim('boom 1.4s ease-out %.2fs both' % HIT)
body += '<div class="warm" style="%s"></div>' % anim('warmIn2 1.6s ease %.2fs both' % (HIT + 0.1))
body += ('<div class="enter" style="%s"><small>여기부터 신라예요</small><b>경주</b><i></i></div>'
         % anim('enterIn 1s cubic-bezier(.2,.8,.2,1) %.2fs both' % (HIT + 0.45)))
body += chapter('여행지에 들어서면', at=0.4, hold=3.0)
CUES.append((s4, 'sfx:road', 0.5, 'atrim=0:%.2f,afade=t=out:st=%.2f:d=0.5,' % (d4, d4 - 0.5)))
CUES.append((s4 + HIT - 2.95, os.path.join(ROOT, 'companion/web/audio/prelude.ogg'), 0.85, ''))
fx(s4 + HIT - 1.5, 'riser', 0.32)
fx(s4 + HIT - 0.02, 'boom', 0.9)
fx(s4 + HIT - 0.1, 'shimmer', 0.3)
CUES.append((s4 + HIT, os.path.join(ROOT, 'companion/web/audio/gyeongju_entry.ogg'), 0.95, ''))
t = scene('s4', s4, d4, body)
S4, GATE = s4, s4 + HIT

# ---- S4b 같은 순간을 지도에서: 요금소부터 금빛이 번지고 길가 등이 켜짐
s4b = t
d4b = 7.2
A04 = '경주에 들어왔어요. 지금부터 지나는 곳을 소개해 드릴게요'
cfg = {'car': [[0, 1350], [0.6, 1500], [d4b, 2900]], 'gold': [[0.55, 0], [1.1, 1]], 'ring': {'t': 0.6, 'speed': 1300, 'max': 7000},
       'labels': [{'sel': '#g1', 'pos': [0, 170, 0]}],
       'cam': [{'t': 0, 'tx': 0, 'tz': 0, 'dist': 1400, 'pitch': 38, 'yaw': -18, 'fov': 30}, {'t': 2.2, 'tx': 150, 'tz': -150, 'dist': 2500, 'pitch': 46, 'yaw': -10, 'fov': 30},
               dict(FARV, t=d4b)]}
cv_, tail = gl(cfg)
body = cv_ + l3('g1', '<span class="t">경주 요금소</span>', 0.15, 3.0)
Lw = say(s4b + 1.4, 'a04')
body += sub(1.4, Lw + 0.15, A04)
body += chapter('여행 모드 · 경주', at=0.6, hold=4.2)
t = scene('s4b', s4b, d4b, body, tail=tail)

# ---- S5 달리는 동안 ① 담아 둔 곳이 가까워지면 먼저 물어봄 → "응" 한마디로 경유지
s5 = t
c0 = c_offer - 0.8
L = lambda c: c - c0
cafe = xz('hwangridan')
Lo = say(s5 + L(c_offer) + 0.1, 'a05')
t_yes = L(c_reply) - 2.05              # 대답이 끝난 뒤에 화면이 반응하게
Ly = say(s5 + t_yes, 'b01', 1.6)
Lr = say(s5 + L(c_reply) + 0.35, 'a06')
d5a = L(c_reply) + 0.35 + Lr + 0.6
cfg = {'car': [[0, 5250], [d5a, 6520]], 'gold': [[0, 1]], 'goldAll': 1,
       'pins': [{'pos': cafe, 'color': MOM, 'at': 0.2, 'saved': 1, 'pulse': L(c_offer)}],
       'labels': [{'sel': '#c1', 'pos': [cafe[0], 190, cafe[1]]}, {'sel': '#c2', 'pos': [cafe[0], 190, cafe[1]]}],
       'cam': [{'t': 0, 'follow': 0.6, 'tx': cafe[0] * 0.4, 'tz': cafe[1] * 0.4, 'dist': 2000, 'pitch': 44, 'yaw': -10, 'fov': 30, 'sx': -290},
               {'t': d5a, 'follow': 0.6, 'tx': cafe[0] * 0.4, 'tz': cafe[1] * 0.4, 'dist': 1250, 'pitch': 40, 'yaw': 6, 'fov': 30, 'sx': -290, 'sy': 70}]}
cv_, tail = gl(cfg)
body = cv_ + chapter('달리는 동안')
body += l3('c1', '<span class="t"><i>엄마가 담음</i>황리단길 카페</span>', 0.5, L(c_reply) + 0.15, cls='g')
body += l3('c2', '<span class="t"><i>경유지</i>황리단길 카페</span>', L(c_reply) + 0.4, cls='g')
body += inset(s5, d5a, c0)
body += sub(L(c_offer) + 0.1, Lo + 0.1, '엄마가 담은 황리단길 카페가 3분 거리예요. 들를까요?')
body += listen(t_yes - 0.25, Ly + 0.3)
body += sub(t_yes, Ly + 0.25, '응, 들르자', '아빠')
body += sub(L(c_reply) + 0.35, Lr + 0.1, '황리단길 카페를 경유지로 넣었어요')
fx(s5, 'whoosh', 0.3)
fx(s5 + L(c_reply) + 0.1, 'ding', 0.26)
t = scene('s5a', s5, d5a, body, tail=tail)

# ---- S5 ② 지나는 명소는 보이는 쪽부터 알려 줌 (실내 시점)
s5b0 = t
c0 = c_sight - 0.6
L = lambda c: c - c0
tower = xz('cheomseongdae')
L1 = say(s5b0 + L(c_sight) + 0.1, 'a01')
d5b0 = max(L(c_pause), L(c_sight) + 0.1 + L1 + 0.15)
win = video(ROAD, s5b0, d5b0, 21.0, 1.0, 'wv dusk', 'top:-394px;height:1080px')
inside = '<div class="win">%s<i class="tint"></i></div><img class="cab" src="@A@/cabin_gold.png">' % win
inside += ('<div class="scr" style="left:%dpx;top:%dpx;width:%dpx;height:%dpx">%s<i class="gl"></i></div>'
           % (SX, SY, SW, SH, video('car.mp4', s5b0, d5b0, c0, 1.0, 'sv', sv)))
body = '<div class="cam" style="transform-origin:1100px 420px;%s">%s</div>' % (anim('push %.2fs linear 0s both' % d5b0), inside)
body += ('<div class="ar" style="left:1290px;top:150px;%s"><span class="t"><i>→</i>오른쪽 · 첨성대<small>240m</small></span><span class="ln" style="height:80px"></span><span class="ring"></span></div>'
         % anim('arIn .6s cubic-bezier(.2,.8,.2,1) %.2fs both' % (L(c_sight) + 0.15)))
body += sub(L(c_sight) + 0.1, L1 + 0.1, '곧 오른쪽에 첨성대가 보여요')
CUES.append((s5b0, 'sfx:road', 0.5, 'atrim=0:%.2f,afade=t=out:st=%.2f:d=0.4,' % (d5b0, d5b0 - 0.4)))
fx(s5b0 + L(c_sight) + 0.05, 'pop', 0.26)
t = scene('s5b0', s5b0, d5b0, body)

# ---- S5 ② 이어서 (지도): 방향지시등을 켜면 잠시 멈췄다가 이어서 들려 줌
s5b = t
c0 = c0 + d5b0
L = lambda c: c - c0
tp, tr = max(0.0, L(c_pause)), L(c_resume)
L2 = say(s5b + tr + 0.2, 'a02')
d5b = tr + 0.2 + L2 + 0.6
cfg = {'car': [[0, 7930], [d5b, 8320]], 'gold': [[0, 1]], 'goldAll': 1, 'signal': [[tp, tr, 'L']], 'lat': [[tp + 0.3, 0], [tr - 0.2, -34]],
       'beam': {'target': tower, 'k': [[0, 1], [d5b, 1]]},
       'labels': [{'sel': '#t1', 'pos': [tower[0], 105, tower[1]]}, {'sel': '#t2', 'car': 1, 'y': 80}],
       'cam': [{'t': 0, 'follow': 0.6, 'tx': tower[0] * 0.4, 'tz': tower[1] * 0.4, 'dist': 1300, 'pitch': 42, 'yaw': -14, 'fov': 30, 'sx': -290},
               {'t': d5b, 'follow': 0.6, 'tx': tower[0] * 0.4, 'tz': tower[1] * 0.4, 'dist': 950, 'pitch': 37, 'yaw': -24, 'fov': 30, 'sx': -290}]}
cv_, tail = gl(cfg)
body = cv_
body += l3('t1', '<img src="@A@/photos/cheomseongdae_1.jpg"><span class="t"><i>오른쪽</i>첨성대</span>', 0.15, cls='g')
body += l3('t2', '<span class="t sm">방향지시등 · 안내 잠시 멈춤</span>', tp + 0.1, tr, cls='g')
body += inset(s5b, d5b, c0, t_in=0.0)
body += sub(tr + 0.2, L2 + 0.1, '신라 선덕여왕 때 세운 천문대예요')
body += '<div class="credit3">%s</div>' % esc(credit(['cheomseongdae_1.jpg']))
CUES.append((s5b + tp, 'sfx:tick', 0.4, 'atrim=0:%.2f,' % (tr - tp)))
t = scene('s5b', s5b, d5b, body, tail=tail)

# ---- S5 ③ 궁금하면 말로 물어봄: "저 숲은 뭐야?"
s5c = t
c0 = c_ask - 2.3
L = lambda c: c - c0
gyr = xz('gyerim')
Lk = say(s5c + 0.25, 'c01', 1.3)
ta = L(c_ask) + 0.45
L7 = say(s5c + ta, 'a07')
L8 = say(s5c + ta + L7 + 0.3, 'a08')
d5c = ta + L7 + 0.3 + L8 + 0.6
cfg = {'car': [[0, 8320], [d5c, 8760]], 'gold': [[0, 1]], 'goldAll': 1,
       'beam': {'target': gyr, 'k': [[L(c_ask) + 0.2, 0], [L(c_ask) + 0.8, 1], [d5c, 1]]},
       'labels': [{'sel': '#k1', 'pos': [gyr[0], 75, gyr[1]]}, {'sel': '#k2', 'pos': [tower[0], 105, tower[1]]}],
       'cam': [{'t': 0, 'tx': 3028, 'tz': -2990, 'dist': 1200, 'pitch': 40, 'yaw': -24, 'fov': 30, 'sx': -290},
               {'t': d5c, 'tx': 3026, 'tz': -2930, 'dist': 860, 'pitch': 32, 'yaw': 6, 'fov': 30, 'sx': -290}]}
cv_, tail = gl(cfg)
body = cv_
body += l3('k1', '<span class="t">계림</span>', L(c_ask) + 0.5, cls='g')
body += l3('k2', '<span class="t sm">첨성대</span>', 0.0, cls='g', a_in='fadeIn')
body += inset(s5c, d5c, c0, t_in=0.0)
body += listen(0.1, Lk + 0.35)
body += sub(0.25, Lk + 0.35, '저 숲은 뭐야?', '둘째')
body += sub(ta, L7 + 0.1, '첨성대 뒤쪽 숲은 계림이에요')
body += sub(ta + L7 + 0.3, L8 + 0.1, '경주 김씨의 시조 김알지가 이 숲의 금궤에서 태어났다는 이야기가 전해져요')
t = scene('s5c', s5c, d5c, body, tail=tail)

# ---- S6 도착하면: 하루 동안 지나온 길, 들른 곳, 들은 설명, 사진이 모여 1분 여행 영상으로
s6 = t
c0 = c_arrive - 0.5
L = lambda c: c - c0
dg = xz('donggung')
La9 = say(s6 + L(c_arrive) + 0.1, 'a09')
t10 = max(L(c_record), L(c_arrive) + 0.1 + La9 + 0.4)
L10 = say(s6 + t10, 'a10')
T_OUT, T_TH, T_FLY, T_CARD, T_FULL = 5.2, 7.7, 10.0, 10.35, 12.4
d6a = T_FULL + 0.7
FAR6 = {'tx': 1750, 'tz': -2100, 'dist': 7600, 'pitch': 62, 'yaw': 0, 'fov': 30}
stops = [('hwangridan', 'hwangridan_1.jpg', MOM, (870, 262)), ('daereungwon', 'daereungwon_1.jpg', DAD, (1030, 104)), ('cheomseongdae', 'cheomseongdae_1.jpg', '#D9A866', (1260, 128)),
         ('gyerim', 'gyerim_1.jpg', '#D9A866', (1440, 470)), ('donggung', 'donggung_night_1.jpg', KID1, (1640, 250))]
cfg = {'car': [[0, 9560], [3.2, WORLD['len'] - 20]], 'gold': [[0, 1]], 'goldAll': 1, 'night': 1, 'dim': [[0, 0.82], [T_CARD, 0.82], [T_CARD + 0.6, 0.42]], 'head': [[0, 0]],
       'pins': [{'pos': xz(k), 'color': col, 'at': T_TH + i * 0.4, 'r': 15, 'h': 70} for i, (k, f, col, xy) in enumerate(stops)],
       'labels': [{'sel': '#a1', 'pos': [dg[0] + 40, 120, dg[1] - 30]}],
       'cam': [{'t': 0, 'tx': dg[0], 'tz': dg[1] + 40, 'dist': 1150, 'pitch': 34, 'yaw': 20, 'fov': 30, 'sx': -290},
               {'t': T_OUT - 0.2, 'tx': dg[0], 'tz': dg[1] + 40, 'dist': 900, 'pitch': 30, 'yaw': 8, 'fov': 30, 'sx': -290}, dict(FAR6, t=T_OUT + 2.2), dict(FAR6, t=99)]}
cv_, tail = gl(cfg)
body = cv_ + chapter('도착하면')
body += l3('a1', '<span class="t"><i>도착</i>동궁과 월지</span>', 0.7, T_OUT - 0.3, cls='g')
body += inset(s6, T_OUT + 1.6, c0, t_in=0.3, t_out=T_OUT + 0.9)
lines = ''
for i, (k, f, col, (tx_, ty_)) in enumerate(stops):
    at = T_TH + i * 0.4
    px_, py_ = project(FAR6, (AN[k]['x'], 250, AN[k]['z']))
    lines += '<line x1="%.0f" y1="%.0f" x2="%.0f" y2="%.0f" style="%s"/>' % (px_, py_, tx_, ty_, anim('fadeIn .3s ease %.2fs both' % (at + 0.1), 'fadeOut .25s ease %.2fs forwards' % T_FLY))
    body += ('<div class="th" style="left:%dpx;top:%dpx;--dx:%dpx;--dy:%dpx;%s"><img src="@A@/photos/%s"></div>'
             % (tx_ - 60, ty_ - 40, 960 - tx_, 540 - ty_, anim('thIn .45s cubic-bezier(.2,.8,.2,1) %.2fs both' % at, 'thFly .55s cubic-bezier(.6,0,.4,1) %.2fs forwards' % (T_FLY + i * 0.05)), f))
    fx(s6 + at, 'pop', 0.2)
body += '<svg class="lead" viewBox="0 0 1920 1080">%s</svg>' % lines
body += ('<div class="reelcard" style="%s"><img src="@A@/reel_poster.jpg"></div>'
         % anim('rcIn .55s cubic-bezier(.2,.8,.2,1) %.2fs both' % T_CARD, 'rcFull .7s cubic-bezier(.6,0,.2,1) %.2fs forwards' % T_FULL))
body += ('<div class="ing" style="top:744px;%s">지나온 길 · 들른 곳 · 들은 설명 · 사진을 모아 <b>1분 여행 영상</b>으로</div>'
         % anim('subIn .4s ease %.2fs both' % (T_CARD + 0.4), 'fadeOut .25s ease %.2fs forwards' % (T_FULL - 0.1)))
body += sub(L(c_arrive) + 0.1, La9 + 0.1, '동궁과 월지에 도착했어요. 오늘 경주 여행은 여기까지예요')
body += sub(t10, L10 + 0.1, '오늘 여행을 1분 영상으로 만들었어요. 가족 휴대폰 네 대에도 보냈어요')
body += '<div class="credit3">%s</div>' % esc(credit(PHOTOS))
fx(s6, 'whoosh', 0.3)
fx(s6 + T_FLY, 'whoosh_s', 0.3)
fx(s6 + T_CARD + 0.1, 'chime', 0.22)
fx(s6 + T_FULL, 'whoosh', 0.4)
t = scene('s6a', s6, d6a, body, tail=tail)

# ---- S6b 여행 영상 (앱이 만든 영상을 그대로)
s6b = t
vids = ''
acc = s6b
for a_, b_ in SEGS:
    vids += video('reel.mp4', acc, b_ - a_, a_, 1.0, 'rv', '')
    acc += b_ - a_
d6b = acc - s6b
body = '<div class="reelfull">%s</div>' % vids + chapter('여행 영상', at=0.5, hold=3.0)
REEL_AT, REEL_LEN = s6b, d6b
t = scene('s6b', s6b, d6b, body)

# ---- S6c 가족 휴대폰으로: 영상 알림 → 여행 기록, 여행 티켓
s6c = t
d6c = 7.6
p1 = c_recv - P_OFF - 0.6
pv1, pw, ph = phone_card(400, s6c, 4.2, p1)
pv2, _, _ = phone_card(400, s6c + 4.2, d6c - 4.2, p_ticket - 0.4)
body = '<div class="studio"></div>' + chapter('가족 휴대폰으로', ink=True)
fam = [('아', '#0B4A8F', 470, 205, -9, 1.1), ('첫', '#1F7A5C', 1240, 190, 8, 1.35), ('둘', '#6B4FC8', 1430, 250, 15, 1.6)]
for (ini, col, fx_, fy, rot, at) in fam:
    body += ('<div class="fam" style="left:%dpx;top:%dpx;--r:%ddeg;--c:%s;%s"><img src="@A@/phone_record.jpg"><b>%s</b></div>'
             % (fx_, fy, rot, col, anim('famIn .7s cubic-bezier(.2,.8,.2,1) %.2fs both' % at), ini))
    fx(s6c + at + 0.1, 'ding', 0.16)
kcss, kst = cam('cam_s6c', d6c, [(0, 960, 540, 1.0), (d6c, 960, 520, 1.05)])
body += kcss
body += ('<div class="stage" style="' + kst + '"><div class="card phone center" style="left:760px;top:120px;width:%dpx;height:%.0fpx;%s"><div class="bezel">%s%s</div></div></div>'
         % (pw, ph, anim('phoneUp .8s cubic-bezier(.2,.8,.2,1) 0s both'), pv1, pv2))
fx(s6c + 0.6, 'ding', 0.32)
t = scene('s6c', s6c, d6c, body)

# ---- S6d 친구와 차 두 대로 갈 때: 앞 차가 정한 목적지를 뒤 차가 "응" 한 번으로 받음
#      기획서의 친구 여행 시나리오를 그린 구상 장면. 시제품 앱에는 아직 없어 화면에 그렇게 적음
s6d = t
lead0, vel, gap = 5000.0, 105.0, 330.0
t_ask = 1.3
Ld1 = say(s6d + t_ask, 'd01')
t_yes = t_ask + Ld1 + 0.35
Ld2 = say(s6d + t_yes, 'd02', 1.3)
t_set = t_yes + Ld2 + 0.35
Ld3 = say(s6d + t_set, 'd03')
d6d = t_set + Ld3 + 0.9
lead = lambda tt: lead0 + vel * tt
foll = lambda tt: lead0 - gap + vel * tt
dst = xz('daereungwon')
cfg = {'car': [[0, lead(0)], [d6d, lead(d6d)]], 'car2': [[0, foll(0)], [d6d, foll(d6d)]], 'car2Link': [[t_set - 0.2, 0], [t_set + 0.4, 1]],
       'gold': [[0, 0]], 'draw': [[0, AN['daereungwon']['s']]], 'head': [[0, 0]],
       'fromT': [[0, lead(0)], [t_set - 0.2, lead(t_set - 0.2)], [t_set + 0.5, foll(t_set + 0.5)], [d6d, foll(d6d)]],
       'pins': [{'pos': dst, 'color': DAD, 'at': 0.5}],
       'labels': [{'sel': '#f1', 'car': 1, 'y': 85}, {'sel': '#f2', 'car2': 1, 'y': 85}, {'sel': '#f3', 'car2': 1, 'y': 85}, {'sel': '#f4', 'car2': 1, 'y': 85},
                  {'sel': '#f5', 'pos': [dst[0], 190, dst[1]]}],
       'cam': [{'t': 0, 'follow': 1, 'tx': 0, 'tz': 60, 'dist': 1500, 'pitch': 44, 'yaw': -10, 'fov': 30},
               {'t': t_set - 0.2, 'follow': 1, 'tx': 0, 'tz': 60, 'dist': 1350, 'pitch': 42, 'yaw': -4, 'fov': 30},
               {'t': t_set + 1.8, 'follow': 0.62, 'tx': dst[0] * 0.38, 'tz': dst[1] * 0.38, 'dist': 2700, 'pitch': 50, 'yaw': 4, 'fov': 30},
               {'t': d6d, 'follow': 0.62, 'tx': dst[0] * 0.38, 'tz': dst[1] * 0.38, 'dist': 2600, 'pitch': 50, 'yaw': 6, 'fov': 30}]}
cv_, tail = gl(cfg)
body = cv_ + chapter('친구와 차 두 대로 · 구상 장면', at=0.4, hold=d6d)
body += l3('f1', '<span class="t sm"><i>앞 차</i></span>', 0.3)
body += l3('f2', '<span class="t sm">뒤 차</span>', 0.3, t_ask)
body += l3('f3', '<span class="t"><i>같이 갈까요?</i></span>', t_ask + 0.1, t_set)
body += l3('f4', '<span class="t"><i>같은 목적지</i>대릉원</span>', t_set + 0.3)
body += l3('f5', '<span class="t"><i>앞 차 목적지</i>대릉원</span>', 0.8)
body += sub(t_ask, Ld1 + 0.1, '앞 차가 대릉원으로 가요. 같이 갈까요?')
body += listen(t_yes - 0.2, Ld2 + 0.3)
body += sub(t_yes, Ld2 + 0.2, '응, 같이 가자', '친구')
body += sub(t_set, Ld3 + 0.1, '대릉원을 목적지로 설정했어요')
body += '<div class="note3">기획서의 친구 여행 시나리오를 그린 구상 장면 · 시제품에는 아직 넣지 않았습니다</div>'
fx(s6d, 'whoosh', 0.3)
fx(s6d + t_set + 0.3, 'ding', 0.26)
t = scene('s6d', s6d, d6d, body, tail=tail)

# ---- 끝
s7 = t
body = ('<div class="title-bg"></div><div class="end" style="%s">%s<h1>구르는 가이드</h1><p>내 차의 여행 모드</p>'
        '<div class="sdk">NaviHelper · FusedLocation · Vehicle · TextToSpeech · SpeechToText</div></div>'
        '<div class="foot" style="%s">차량·휴대폰 화면은 Pleos Connect 에뮬레이터와 휴대폰 웹 실제 실행 화면 · 지도 장면은 앱 지도로 만든 3D 그래픽<br>'
        '차 실내와 도로는 생성 이미지와 스톡 영상 · 목소리는 AI 합성 음성 · 친구 여행은 구상 장면</div>'
        % (anim('endIn 1s cubic-bezier(.2,.8,.2,1) .3s both'), MARK, anim('fadeIn .8s ease 1.4s both')))
t = scene('s7', s7, 5.6, body, 'dark')
TOTAL = t

# ------------------------------------------------------------------ HTML: 장면마다 한 파일 (영상 시각은 장면 시작 기준)
CSS = open(os.path.join(HERE, 'film.css'), encoding='utf-8').read()
COMP = os.path.join(HERE, 'compositions')
os.makedirs(COMP, exist_ok=True)
PAGE = ('<!doctype html>\n<html lang="ko">\n<head>\n<meta charset="utf-8">\n<meta name="viewport" content="width=1920, height=1080">\n'
        '<title>%s</title>\n<style>\n%s\n</style>\n</head>\n<body>\n'
        '<div id="root" data-composition-id="%s" data-start="0" data-duration="%.3f" data-width="1920" data-height="1080" data-no-timeline>\n%s\n</div>\n%s\n</body>\n</html>\n')
SNAP = '--snap' in sys.argv            # 장면별 스냅샷 확인용 폴더(_snap/<장면>/index.html)도 만듦
plan = []
for sid, start, d, body, cls, tail in SCENES:
    rel = re.sub(r'data-start="([0-9.]+)"', lambda m: 'data-start="%.3f"' % max(0.0, float(m.group(1)) - start), body)
    inner = '<div class="scene %s">\n%s\n</div>' % (cls, rel)
    page_s = PAGE % ('구르는 가이드 ' + sid, CSS.replace('url(assets/', 'url(@A@/'), sid, d, inner, tail)
    out_s = page_s.replace('@A@', '../assets')
    fp = os.path.join(COMP, sid + '.html')
    if not os.path.exists(fp) or open(fp, encoding='utf-8').read() != out_s:     # 바뀐 장면만 다시 렌더되게
        open(fp, 'w', encoding='utf-8').write(out_s)
    if SNAP:
        sd = os.path.join(HERE, '_snap', sid)
        os.makedirs(sd, exist_ok=True)
        if not os.path.exists(os.path.join(sd, 'assets')):
            os.symlink(A, os.path.join(sd, 'assets'))
        open(os.path.join(sd, 'index.html'), 'w', encoding='utf-8').write(page_s.replace('@A@', 'assets'))
    plan.append({'id': sid, 'start': round(start, 3), 'duration': round(d, 3)})
for f in os.listdir(COMP):
    if f.endswith('.html') and f[:-5] not in [p['id'] for p in plan]:
        os.remove(os.path.join(COMP, f))
mounts = '\n'.join('<div id="m_%s" class="clip" data-composition-id="%s" data-composition-src="compositions/%s.html" data-start="%.3f" data-duration="%.3f"></div>'
                   % (p['id'], p['id'], p['id'], p['start'], p['duration']) for p in plan)
open(os.path.join(HERE, 'index.html'), 'w', encoding='utf-8').write(PAGE % ('구르는 가이드 시연 필름', '#root{position:relative;width:1920px;height:1080px;background:#05080B}', 'main', TOTAL, mounts, ''))
json.dump({'total': TOTAL, 'scenes': plan}, open(os.path.join(HERE, 'scenes.json'), 'w'), ensure_ascii=False, indent=1)
print('길이 %.1f초, 장면 %d, 영상 조각 %d' % (TOTAL, len(SCENES), VID[0]))
for p in plan:
    print('  %-4s %6.2f ~ %6.2f' % (p['id'], p['start'], p['start'] + p['duration']))
if '--no-audio' in sys.argv:
    raise SystemExit(0)


# ------------------------------------------------------------------ 소리
def sfx(kind):
    p = os.path.join(A, 'sfx_%s.wav' % kind)
    if os.path.exists(p):
        return p
    f = {
        'whoosh': "anoisesrc=d=0.9:c=pink:a=0.6,highpass=f=300,lowpass=f=5000,afade=t=in:d=0.35,afade=t=out:st=0.4:d=0.5",
        'whoosh_s': "anoisesrc=d=0.6:c=pink:a=0.5,highpass=f=900,lowpass=f=7000,afade=t=in:d=0.2,afade=t=out:st=0.25:d=0.35",
        'pop': "sine=f=880:d=0.14,afade=t=out:st=0.02:d=0.12,volume=0.8",
        'tick': "aevalsrc='0.6*sin(2*PI*1600*t)*exp(-60*mod(t\\,0.5))':d=3",
        'keys': "aevalsrc='0.45*sin(2*PI*2300*t)*exp(-110*mod(t\\,0.11))':d=1",      # 화면 자판 두드리는 소리
        'chime': "aevalsrc='0.35*sin(2*PI*1046.5*t)*exp(-3*t)+0.25*sin(2*PI*1568*t)*exp(-4*t)':d=2",
        'ding': "aevalsrc='0.35*sin(2*PI*1318.5*t)*exp(-5*t)+0.2*sin(2*PI*1975.5*t)*exp(-6*t)':d=1.2",
        'shimmer': "aevalsrc='0.12*sin(2*PI*(2093+800*t)*t)*exp(-1.6*t)+0.1*sin(2*PI*2637*t)*exp(-2*t)':d=2.2",
        'road': "anoisesrc=d=14:c=brown:a=0.9,lowpass=f=170,highpass=f=28,afade=t=in:d=0.8,afade=t=out:st=12.8:d=1.2",        # 차 안에서 들리는 낮은 주행음
        'riser': "anoisesrc=d=1.6:c=pink:a=0.6,highpass=f=500,lowpass=f=7000,afade=t=in:d=1.5:curve=exp,afade=t=out:st=1.5:d=0.1",
        'boom': "aevalsrc='0.95*sin(2*PI*(44+34*exp(-8*t))*t)*exp(-2.2*t)':d=2.4",                                         # 요금소를 지나는 순간의 낮은 울림
    }[kind]
    run([FF, '-y', '-f', 'lavfi', '-i', f, '-ar', '48000', '-ac', '2', p])
    return p


def envelope(points):
    """(시각, 볼륨) 점을 잇는 ffmpeg 식"""
    expr = '%.3f' % points[-1][1]
    for (t0, v0), (t1, v1) in reversed(list(zip(points, points[1:]))):
        expr = 'if(lt(t,%.3f),%.3f+(%.3f)*(t-%.3f)/%.3f,%s)' % (t1, v0, v1 - v0, t0, max(0.001, t1 - t0), expr)
    return expr


def mix(out):
    speech, fxs, tunes = [], [], []           # 말, 효과음, 앱이 트는 연주(전주, 가야금)
    for (tt, src, vol, pre) in CUES:
        if src.startswith('sfx:'):
            src = sfx(src[4:])
        (speech if os.sep + 'voice' + os.sep in src else tunes if src.endswith('.ogg') else fxs).append((tt, src, vol, pre))
    music = MUSIC if MUSIC and os.path.exists(MUSIC) else os.path.join(ROOT, 'companion/web/audio/reel_music.ogg')
    # 배경 음악은 처음부터 끝까지 한 곡. 요금소 앞에서 거의 꺼졌다가 가야금이 끝나면 돌아오고, 여행 영상에서는 키움. 말소리가 나오면 자동으로 눌림
    reel_end = REEL_AT + REEL_LEN
    pts = [(0, 0.0), (0.8, 0.62), (14.4, 0.62), (15.6, 0.44), (S4 + 0.8, 0.44), (S4 + 3.2, 0.14), (GATE - 1.2, 0.10), (GATE - 0.2, 0.0), (GATE + 7.8, 0.0), (GATE + 10.4, 0.36),
           (REEL_AT - 1.0, 0.36), (REEL_AT + 0.5, 0.52), (reel_end - 0.3, 0.52), (reel_end + 1.0, 0.42), (TOTAL - 3.4, 0.42), (TOTAL, 0.0)]
    args = [FF, '-y']
    filt = []
    k = 0
    for group, tag in ((speech, 's'), (fxs, 'f'), (tunes, 'u')):
        for (tt, src, vol, pre) in group:
            args += ['-i', src]
            ms = int(max(0, tt) * 1000)
            filt.append('[%d:a]%saresample=48000,aformat=channel_layouts=stereo,volume=%.3f,adelay=%d|%d[%s%d]' % (k, pre, vol, ms, ms, tag, k))
            k += 1
    ns, nf, nu = len(speech), len(fxs), len(tunes)
    filt.append(''.join('[s%d]' % i for i in range(ns)) + 'amix=inputs=%d:normalize=0:duration=longest,asplit=2[sp][key0]' % ns)
    filt.append('[key0]apad=whole_dur=%.3f[key]' % TOTAL)          # 마지막 말 뒤에도 음악이 끝까지 이어지게
    filt.append(''.join('[f%d]' % i for i in range(ns, ns + nf)) + 'amix=inputs=%d:normalize=0:duration=longest[fx]' % nf)
    args += ['-i', music]
    filt.append("[%d:a]aresample=48000,aformat=channel_layouts=stereo,atrim=0:%.3f,volume='%s':eval=frame[bed0]" % (k, TOTAL, envelope(pts)))
    # 배경 음악과 앱 연주를 한데 묶어, 말이 나오면 함께 눌리게
    filt.append('[bed0]' + ''.join('[u%d]' % i for i in range(ns + nf, ns + nf + nu)) + 'amix=inputs=%d:normalize=0:duration=first[bedv]' % (nu + 1))
    filt.append('[bedv][key]sidechaincompress=threshold=0.015:ratio=7:attack=20:release=380:makeup=1[bed]')
    filt.append('[sp][fx][bed]amix=inputs=3:normalize=0:duration=longest,atrim=0:%.3f,volume=1.15,alimiter=limit=0.7:attack=2:release=80:level=0[m]' % TOTAL)
    run(args + ['-filter_complex', ';'.join(filt), '-map', '[m]', '-c:a', 'aac', '-b:a', '192k', out])


audio = os.path.join(A, 'audio.m4a')
mix(audio)
print('소리', audio)

