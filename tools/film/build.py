#!/usr/bin/env python3
"""구르는 가이드 시연 필름 (HyperFrames)
  python3 tools/film/media.py /tmp/rec tools/film/assets     # 녹화 → MP4 재료
  python3 tools/film/build.py /tmp/rec                        # index.html, assets/audio.m4a
  hyperframes render tools/film -o /tmp/film_video.mp4        # 화면
  python3 tools/film/build.py /tmp/rec --mux /tmp/film_video.mp4 apk/구르는가이드_시연영상.mp4

장면마다 설명 문구를 붙이지 않고, 차와 사람이 하는 말(자막), 창밖 라벨, 실내 빛, 화면 자체로 보여 준다.
차량 화면은 Pleos Connect 에뮬레이터 실제 실행 녹화, 차 실내 두 장은 생성 이미지, 목소리는 macOS say(Yuna) 합성.
"""
import hashlib, html, json, math, os, shutil, subprocess, sys
from PIL import Image, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
REC = sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith('--') else '/tmp/rec'
A = os.path.join(HERE, 'assets')
FF = shutil.which('ffmpeg') or '/opt/homebrew/bin/ffmpeg'
FP = FF.replace('ffmpeg', 'ffprobe')
PLATES = os.environ.get('PLATES', os.path.join(HERE, 'plates'))     # 차 실내 생성 이미지 두 장 (ChatGPT 이미지 생성)
MUSIC = os.environ.get('MUSIC', os.path.join(A, 'music.mp3'))      # Pixabay 'Emotive Inspiring Background Acoustic Music' (저장소에는 넣지 않음)
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


c_add, c_start, c_appr, c_enter = ev('phone_add'), ev('navi:'), ev('approach'), ev('enter:')
c_welcome = ev('speak:경주에 들어왔어요')
c_offer, c_reply = ev('offer:'), ev('reply:')
c_sight = ev('sight:cheomseongdae')
c_pause, c_resume = ev('pause:', c_sight), ev('resume', c_sight)
c_ask = ev('ask:')
c_arrive, c_record = ev('arrive'), ev('record')
c_recv, c_close = pev('알림 뜸'), pev('영상 닫음')
p_tap = pev('우리 여행에 담기 누름') - P_OFF        # 휴대폰 영상 시각
p_ticket = pev('여행 티켓 보이게') - P_OFF

# ------------------------------------------------------------------ 재료
os.makedirs(os.path.join(A, 'photos'), exist_ok=True)
os.makedirs(os.path.join(A, 'fonts'), exist_ok=True)
os.makedirs(os.path.join(A, 'tts'), exist_ok=True)
for w in ['regular', 'medium', 'semibold', 'bold', 'extrabold']:
    shutil.copy(os.path.join(ROOT, 'app/src/main/res/font/asta_%s.otf' % w), os.path.join(A, 'fonts'))
for k in ['a', 'b']:
    im = Image.open(os.path.join(PLATES, 'plate_%s.jpg' % k)).convert('RGB').resize((W, 1081), Image.LANCZOS)
    im.filter(ImageFilter.UnsharpMask(radius=1.2, percent=40, threshold=2)).save(os.path.join(A, 'plate_%s.jpg' % k), quality=93)
CREDITS = {c['file']: c for c in json.load(open(os.path.join(ROOT, 'companion/web/photos/credits.json'), encoding='utf-8'))}
for f in ['cheomseongdae_1.jpg', 'gyerim_1.jpg']:
    shutil.copy(os.path.join(ROOT, 'companion/web/photos', f), os.path.join(A, 'photos', f))
navi = Image.open(os.path.join(REC, 'navi_route.png')).convert('RGB').crop((853, 90, 2535, 1300)).resize((840, 604), Image.LANCZOS)
navi.save(os.path.join(A, 'navi.jpg'), quality=92)
rec_shot = os.path.join(A, 'phone_record.jpg')          # 가족 휴대폰 뒤 배경: 휴대폰 '여행 기록' 화면 한 장
if not os.path.exists(rec_shot):
    tt_ = pev('여행 티켓 보이게') - P_OFF + 0.5
    run([FF, '-y', '-ss', '%.2f' % tt_, '-i', os.path.join(REC, 'phone.webm'), '-frames:v', '1', '-vf', 'scale=600:-2', rec_shot])


def credit(f):
    c = CREDITS.get(f) or {}
    return '사진 %s · %s' % (c.get('author', ''), c.get('license', ''))


# ------------------------------------------------------------------ 목소리 (차: Yuna, 사람: 같은 목소리를 높낮이만 바꿈)
def tts(text, who='car'):
    key = hashlib.md5((who + text).encode()).hexdigest()[:10]
    wav = os.path.join(A, 'tts', key + '.wav')
    if not os.path.exists(wav):
        aiff = wav.replace('.wav', '.aiff')
        rate = {'car': 186, 'dad': 170, 'kid': 182}[who]
        subprocess.run(['say', '-v', 'Yuna', '-r', str(rate), '-o', aiff, text], check=True)
        af = 'aresample=48000'
        if who == 'dad':
            af = 'asetrate=%d,aresample=48000,atempo=%f' % (int(22050 * 0.86), 1 / 0.86)
        if who == 'kid':
            af = 'asetrate=%d,aresample=48000,atempo=%f' % (int(22050 * 1.22), 1 / 1.22)
        run([FF, '-y', '-i', aiff, '-af', af + ',silenceremove=start_periods=1:start_threshold=-50dB', '-ac', '2', wav])
    return wav, dur(wav)


CUES = []          # (시각, 파일, 볼륨, 앞쪽 필터)


def say(t, text, who='car', cut=None):
    wav, d = tts(text, who)
    if cut is not None and cut < d:
        CUES.append((t, wav, 1.0, 'atrim=0:%.3f,afade=t=out:st=%.3f:d=0.12,' % (cut, max(0, cut - 0.12))))
        d = cut
    else:
        CUES.append((t, wav, 1.0 if who == 'car' else 0.95, ''))
    return d


# ------------------------------------------------------------------ HTML 조각
def esc(s):
    return html.escape(s, quote=False)


VID = [0]


def video(src, start, d, media, rate=1.0, cls='', style=''):
    VID[0] += 1
    extra = (' data-playback-rate="%g"' % rate) if abs(rate - 1) > 1e-3 else ''
    return ('<video id="v%02d" class="clip %s" src="assets/%s" data-start="%.3f" data-duration="%.3f" data-media-start="%.3f"%s muted playsinline style="%s"></video>'
            % (VID[0], cls, src, start, d, media, extra, style))


def anim(*parts):
    return 'animation:' + ', '.join(parts) + ';'


INK = [False]      # 밝은 스튜디오 장면이면 자막과 장 제목을 어두운 글씨로


def sub(t, d, text, who=None, s0=0.0, ink=None):
    """자막: t 는 장면 안 시각. who=None 이면 차가 하는 말(아래 얇은 빛 선), 아니면 사람 이름을 앞에 흐리게"""
    ink = INK[0] if ink is None else ink
    cls = ('car' if who is None else 'person') + (' ink' if ink else '')
    lead = '' if who is None else '<em>%s</em>' % esc(who)
    return ('<div class="sub %s" style="%s"><span>%s%s</span></div>'
            % (cls, anim('subIn .3s ease %.3fs both' % (t - s0), 'fadeOut .25s ease %.3fs forwards' % (t - s0 + d)), lead, esc(text)))


def glow(t, d, kind='speak', s0=0.0):
    if kind != 'listen':
        return ''      # 차가 말할 때의 빛은 자막 아래 선으로 그림
    return '<div class="listen" style="%s"></div>' % anim('listenIn .35s ease %.3fs both' % (t - s0), 'fadeOut .3s ease %.3fs forwards' % (t - s0 + d))


def cam(name, d, stops, k=0.55):
    """화면을 띄운 장면의 카메라. stops=[(시각, 초점x, 초점y, 배율)] — 초점이 화면 가운데 쪽으로 k 만큼 오도록 당기고 확대"""
    rules = []
    for tt, fx, fy, s in stops:
        tx = fx + k * (960 - fx) - s * fx
        ty = fy + k * (540 - fy) - s * fy
        rules.append('%.2f%%{transform:translate(%.1fpx,%.1fpx) scale(%.4f);animation-timing-function:cubic-bezier(.45,0,.25,1)}' % (min(100.0, tt / d * 100), tx, ty, s))
    return '<style>@keyframes %s{%s}</style>' % (name, ''.join(rules)), 'transform-origin:0 0;animation:%s %.3fs linear 0s both' % (name, d)


def chapter(num, title, s0=0.0, at=0.35, hold=3.4, ink=None):
    ink = INK[0] if ink is None else ink
    return ('<div class="chap%s" style="%s">%s</div>'
            % (' ink' if ink else '', anim('chapIn .6s cubic-bezier(.2,.8,.2,1) %.2fs both' % at, 'fadeOut .5s ease %.2fs forwards' % (at + hold)), esc(title)))


# 차량 앱 영역(에뮬레이터 853,90 ~ 2535,1300)을 카드 폭에 맞춰 잘라 보이기: car.mp4 는 2560→1920 축소본
def car_card(cw, start, d, media, rate=1.0):
    s = cw / 1261.5
    st = 'width:%.1fpx;height:%.1fpx;left:%.1fpx;top:%.1fpx' % (1920 * s, 1080 * s, -639.75 * s, -67.5 * s)
    return video('car.mp4', start, d, media, rate, 'cv', st), cw, 907.5 * s


def phone_card(pw, start, d, media):
    return video('phone.mp4', start, d, media, 1.0, 'pv', 'width:%dpx;height:%.1fpx' % (pw, pw * 1688 / 780)), pw, pw * 1688 / 780


# 실내 이미지 속 화면 자리 (plate 1672x941 → 1920x1081 배율 1.1483)
PS = 1920 / 1672
SCR = (728 * PS, 397 * PS, (1118 - 728) * PS, (581 - 397) * PS)


def screen_video(start, d, media, rate=1.0):
    # 에뮬레이터 화면 위 상태 막대와 아래 독을 뺀 (0,90)~(2560,1297) 을 화면 자리에 맞춤
    s = SCR[2] / 1920
    st = 'width:%.1fpx;height:%.1fpx;left:0;top:%.1fpx' % (1920 * s, 1080 * s, -67.5 * s)
    return video('car.mp4', start, d, media, rate, 'sv', st)


SCENES = []


def scene(sid, start, d, body, cls=''):
    d = round(d * 30) / 30.0                 # 장면 길이를 프레임(1/30초) 단위로 맞춰 이어 붙여도 소리와 어긋나지 않게
    SCENES.append((sid, start, d, body, cls))
    return round((start + d) * 30) / 30.0


def plate(kind, body_inside, push, origin=(1060, 560)):
    return ('<div class="cam" style="transform-origin:%dpx %dpx;%s"><img class="plate" src="assets/plate_%s.jpg">%s</div>'
            % (origin[0], origin[1], push, kind, body_inside))


def strips(cls, style=''):
    # 대시보드 무드등 (왼쪽은 살짝 기울어짐)
    return ('<div class="strip %s" style="left:%dpx;top:%dpx;width:%dpx;transform:rotate(-0.8deg);%s"></div>'
            '<div class="strip %s" style="left:%dpx;top:%dpx;width:%dpx;%s"></div>'
            % (cls, 247, 584, 584, style, cls, 1288, 579, 412, style))


# ================================================================== 장면
t = 0.0
# ---- S1 시작: 노을 진 경주 길, 차가 먼저 말을 건다 → 첨성대
d1 = 7.6
L1 = say(t + 0.9, '곧 오른쪽에 첨성대가 보여요.')
L2 = say(t + 4.55, '신라 선덕여왕 때 세운 천문대예요.')
inside = ('<div class="scr" style="left:%.1fpx;top:%.1fpx;width:%.1fpx;height:%.1fpx">%s<i class="gl"></i></div>'
          % (SCR[0], SCR[1], SCR[2], SCR[3], screen_video(t, 4.4, c_sight - 0.2))) + strips('warmw')
body = plate('a', inside, anim('push 7.6s linear 0s both'))
body += ('<div class="ar" style="left:1290px;top:258px;%s"><span class="t"><i>→</i>오른쪽 · 첨성대<small>240m</small></span><span class="ln"></span><span class="ring"></span></div>'
         % anim('arIn .6s cubic-bezier(.2,.8,.2,1) .75s both', 'fadeOut .3s ease 3.8s forwards'))
body += glow(t + 0.9, L1 + 0.2) + sub(t + 0.9, L1 + 0.15, '곧 오른쪽에 첨성대가 보여요')
body += ('<div class="photo" style="%s"><img src="assets/photos/cheomseongdae_1.jpg" style="%s"><div class="vign"></div><div class="credit">%s</div></div>'
         % (anim('photoIn .55s cubic-bezier(.6,0,.2,1) 3.95s both'), anim('kb1 4.2s linear 3.95s both'), esc(credit('cheomseongdae_1.jpg'))))
body += glow(t + 4.55, L2 + 0.2) + sub(t + 4.55, L2 + 0.2, '신라 선덕여왕 때 세운 천문대예요')
CUES.append((3.85, 'sfx:whoosh', 0.5, ''))
CUES.append((0.75, 'sfx:pop', 0.35, ''))
t = scene('s1', t, d1, body)

# ---- S2 제목: 손글씨처럼 그려지는 한 줄 → 앱 이름
d2 = 5.2
body = ('<div class="title-bg"></div>'
        '<div class="ask" style="%s">어디로 떠날까요?</div>'
        '<div class="lock" style="%s">%s<div><h1>구르는 가이드</h1><p>내 차의 여행 모드</p></div></div>'
        % (anim('askIn .9s cubic-bezier(.2,.8,.2,1) .3s both', 'fadeOut .5s ease 2.5s forwards'),
           anim('lockIn .8s cubic-bezier(.2,.8,.2,1) 3.0s both'),
           '<svg class="mark" viewBox="0 0 120 120"><rect width="120" height="120" rx="34" fill="#0B4A8F"/>'
           '<path d="M24 52 q9 -10 18 0 t18 0 t18 0 t18 0" stroke="#00AAD2" stroke-width="9" fill="none" stroke-linecap="round"/>'
           '<path d="M24 76 q9 -10 18 0 t18 0 t18 0 t18 0" stroke="#BFE6F2" stroke-width="9" fill="none" stroke-linecap="round"/></svg>'))
CUES.append((t + 3.0, 'sfx:chime', 0.28, ''))
t = scene('s2', t, d2, body, 'dark')

# ---- S3 떠나기 전: 휴대폰에서 담으면 차 일정으로 → 버튼 하나로 출발
s3 = t
c0 = 4.6                                 # 이 장면 0초의 차량 시각
C = lambda c: s3 + (c - c0)
d3 = 21.6 - c0
pv, pw, ph = phone_card(300, s3, 9.4, c0 - P_OFF)
cv, cw, ch = car_card(960, s3, d3, c0)
INK[0] = True
body = '<div class="studio"></div>'
body += chapter('01', '떠나기 전')
kcss, kst = cam('cam_s3', d3, [(0, 960, 540, 1.0), (8.6, 1000, 560, 1.04), (10.2, 960, 540, 1.0), (d3, 980, 560, 1.05)])
body += kcss
body += ('<div class="stage" style="%s"><div class="card phone" style="left:300px;top:168px;width:%dpx;height:%.0fpx;%s"><div class="bezel">%s</div></div>'
         % (kst, pw, ph, anim('phoneIn .8s cubic-bezier(.2,.8,.2,1) .1s both', 'phoneOut .7s cubic-bezier(.6,0,.4,1) 8.9s forwards'), pv))
body += ('<div class="card car" style="left:760px;top:150px;width:%dpx;height:%.0fpx;%s">%s<i class="sheen"></i></div></div>'
         % (cw, ch, anim('carIn .9s cubic-bezier(.2,.8,.2,1) .25s both', 'carCenter .9s cubic-bezier(.6,0,.2,1) 9.0s forwards'), cv))
fly = C(p_tap + P_OFF) - s3
CUES.append((s3 + fly, 'sfx:pop', 0.22, ''))
CUES.append((C(c_add), 'sfx:pop', 0.32, ''))
Ls = say(C(c_start) + 0.3, '대릉원으로 안내를 시작할게요. 점심 식당을 경유지로 넣었어요.')
body += glow(C(c_start) + 0.3, Ls + 0.2, s0=s3) + sub(C(c_start) + 0.3, Ls + 0.1, '대릉원으로 안내를 시작할게요. 점심 식당을 경유지로 넣었어요', s0=s3)
nv0 = C(c_start) + 1.4 - s3
body += ('<div class="navi" style="%s"><div class="card"><img src="assets/navi.jpg"></div><div class="lab">순정 내비게이션</div></div>'
         % anim('naviIn .7s cubic-bezier(.2,.8,.2,1) %.2fs both' % nv0, 'naviOut .5s ease %.2fs forwards' % (nv0 + 4.6)))
t = scene('s3', s3, d3, body, 'dark')
INK[0] = False

# ---- S4 여행지에 들어서면: 조명이 낮아지고 전주, 요금소를 지나면 금빛과 가야금 → 화면도 경주 테마
s4 = t
fast_end = 8.8                           # 접근 구간 2배속
cA = 21.6
gate = s4 + (c_enter - cA) / 2.0
if gate > s4 + fast_end:
    fast_end = gate - s4 + 0.4
cB = cA + fast_end * 2
inside = ('<div class="scr" style="left:%.1fpx;top:%.1fpx;width:%.1fpx;height:%.1fpx">%s%s<i class="gl"></i></div>'
          % (SCR[0], SCR[1], SCR[2], SCR[3], screen_video(s4, fast_end, cA, 2.0), screen_video(s4 + fast_end, 3.4, cB)))
g = gate - s4
inside += strips('cool', anim('dimDown 3.4s ease 0.6s both', 'goldOn 1.4s ease %.2fs forwards' % g))
inside += '<div class="dimmer" style="%s"></div>' % anim('dimIn 3.6s ease .5s both', 'dimOut 1.6s ease %.2fs forwards' % g)
inside += '<div class="warm" style="%s"></div>' % anim('warmIn 1.8s ease %.2fs both' % (g + 0.1))
body = plate('a', inside, anim('push2 13.6s linear 0s both'))
body += '<div class="sweep" style="%s"></div>' % anim('sweep 1.5s cubic-bezier(.4,0,.2,1) %.2fs both' % (g - 0.1))
body += '<div class="flash" style="%s"></div>' % anim('flash 1.1s ease-out %.2fs both' % g)
body += chapter('02', '여행지에 들어서면', at=0.4, hold=3.2)
d4a = fast_end + 3.4
CUES.append((s4 + (c_appr - cA) / 2.0, os.path.join(ROOT, 'companion/web/audio/prelude.ogg'), 0.8, ''))
CUES.append((gate, os.path.join(ROOT, 'companion/web/audio/gyeongju_entry.ogg'), 0.95, ''))
CUES.append((gate - 0.1, 'sfx:shimmer', 0.30, ''))
t = scene('s4', s4, d4a, body)
S4, GATE = s4, gate

s4b = t
cC = cB + 3.4
C2 = lambda c: s4b + (c - cC)
Lw = say(C2(c_welcome) + 0.1, '경주에 들어왔어요. 지금부터 지나는 곳을 소개해 드릴게요.')
d4b = C2(c_welcome) + 0.1 + Lw + 0.7 - s4b
cv, cw, ch = car_card(920, s4b, d4b, cC)
pv, pw, ph = phone_card(296, s4b, d4b, cC - P_OFF)
INK[0] = True
body = '<div class="studio sand"></div>'
kcss, kst = cam('cam_s4b', d4b, [(0, 960, 540, 1.0), (1.6, 960, 540, 1.0), (d4b - 1.0, 900, 420, 1.12), (d4b, 900, 420, 1.12)])
body += kcss
body += ('<div class="stage" style="' + kst + '"><div class="card car goldrim" style="left:250px;top:140px;width:%dpx;height:%.0fpx;%s">%s<i class="sheen"></i></div>'
         '<div class="card phone" style="left:1340px;top:150px;width:%dpx;height:%.0fpx;%s"><div class="bezel">%s</div></div></div>'
         % (cw, ch, anim('carL .8s cubic-bezier(.2,.8,.2,1) 0s both'), cv, pw, ph, anim('phoneR .8s cubic-bezier(.2,.8,.2,1) .1s both'), pv))
body += glow(C2(c_welcome) + 0.1, Lw + 0.2, s0=s4b) + sub(C2(c_welcome) + 0.1, Lw + 0.1, '경주에 들어왔어요. 지금부터 지나는 곳을 소개해 드릴게요', s0=s4b)
CUES.append((s4b, 'sfx:whoosh', 0.35, ''))
t = scene('s4b', s4b, d4b, body, 'dark')

# ---- S5 달리는 동안: 담아 둔 곳 제안 → 지나는 명소, 차로 변경 멈춤 → "저 숲은 뭐야?"
s5 = t
ca0 = c_offer - 0.9
C3 = lambda c: s5 + (c - ca0)
Lo = say(C3(c_offer), '엄마가 담은 황리단길 카페가 3분 거리예요. 들를까요?')
yes_t = C3(c_reply) - 0.9
Ly = say(yes_t, '응', 'dad')
Lr = say(C3(c_reply) + 0.3, '황리단길 카페를 경유지로 넣었어요.')
d5a = C3(c_reply) + 0.3 + Lr + 0.6 - s5
cv, cw, ch = car_card(960, s5, d5a, ca0)
pv, pw, ph = phone_card(296, s5, d5a, ca0 - P_OFF)
body = '<div class="studio"></div>'
body += chapter('03', '달리는 동안')
o1 = C3(c_offer) - s5
r1 = C3(c_reply) - s5
kcss, kst = cam('cam_s5a', d5a, [(0, 960, 540, 1.0), (o1 + 0.2, 960, 540, 1.0), (o1 + 1.6, 980, 470, 1.16), (r1 + 0.2, 980, 470, 1.16), (r1 + 1.6, 520, 560, 1.08), (d5a, 520, 560, 1.08)])
body += kcss
body += ('<div class="stage" style="' + kst + '"><div class="card car" style="left:230px;top:132px;width:%dpx;height:%.0fpx;%s">%s<i class="sheen"></i></div>'
         '<div class="card phone" style="left:1370px;top:150px;width:%dpx;height:%.0fpx;%s"><div class="bezel">%s</div></div></div>'
         % (cw, ch, anim('carL .8s cubic-bezier(.2,.8,.2,1) 0s both'), cv, pw, ph, anim('phoneR .8s cubic-bezier(.2,.8,.2,1) .1s both'), pv))
body += glow(C3(c_offer), Lo + 0.2, s0=s5) + sub(C3(c_offer), Lo + 0.1, '엄마가 담은 황리단길 카페가 3분 거리예요. 들를까요?', s0=s5)
body += glow(C3(c_offer) + Lo + 0.3, yes_t + Ly - (C3(c_offer) + Lo + 0.3) + 0.2, 'listen', s0=s5)
body += sub(yes_t, Ly + 0.9, '응', '아빠', s0=s5)
body += glow(C3(c_reply) + 0.3, Lr + 0.2, s0=s5) + sub(C3(c_reply) + 0.3, Lr + 0.1, '황리단길 카페를 경유지로 넣었어요', s0=s5)
CUES.append((s5, 'sfx:whoosh', 0.35, ''))
t = scene('s5a', s5, d5a, body, 'dark')

s5b = t
cb0 = c_sight - 0.6
C4 = lambda c: s5b + (c - cb0)
Ls1 = say(C4(c_sight), '곧 오른쪽에 첨성대가 보여요. 신라 선덕여왕 때', cut=c_pause - c_sight)
Ls2 = say(C4(c_resume) + 0.1, '신라 선덕여왕 때 세운 천문대예요.')
d5b = C4(c_resume) + 0.1 + Ls2 + 0.6 - s5b
cv, cw, ch = car_card(960, s5b, d5b, cb0)
pv, pw, ph = phone_card(296, s5b, d5b, cb0 - P_OFF)
body = '<div class="studio"></div>'
q0, q1, q2 = C4(c_sight) - s5b, C4(c_pause) - s5b, C4(c_resume) - s5b
kcss, kst = cam('cam_s5b', d5b, [(0, 960, 540, 1.0), (q0 + 0.2, 960, 540, 1.0), (q0 + 1.5, 980, 440, 1.12), (q1 - 0.1, 980, 440, 1.12), (q1 + 0.7, 990, 510, 1.22), (q2 + 0.2, 990, 510, 1.22), (q2 + 1.4, 960, 540, 1.04), (d5b, 960, 540, 1.04)])
body += kcss
body += ('<div class="stage" style="' + kst + '"><div class="card car" style="left:230px;top:132px;width:%dpx;height:%.0fpx;%s">%s<i class="sheen"></i></div>'
         '<div class="card phone" style="left:1370px;top:150px;width:%dpx;height:%.0fpx;%s"><div class="bezel">%s</div></div></div>'
         % (cw, ch, anim('carL2 .5s ease 0s both'), cv, pw, ph, anim('phoneR2 .5s ease 0s both'), pv))
CUES.append((s5b, 'sfx:whoosh_s', 0.3, ''))
sp = C4(c_pause) - s5b
blink = C4(c_resume) - C4(c_pause)
body += ('<div class="signal" style="%s"><svg viewBox="0 0 64 64"><path d="M14 22h22V10l22 22-22 22V42H14z" fill="#2FD27A"/></svg></div>'
         % anim('blink .6s linear %.2fs %d forwards' % (sp, max(1, int(blink / 0.6))), 'fadeOut .2s ease %.2fs forwards' % (sp + blink)))
body += glow(C4(c_sight), Ls1, s0=s5b) + sub(C4(c_sight), Ls1, '곧 오른쪽에 첨성대가 보여요', s0=s5b)
body += glow(C4(c_resume) + 0.1, Ls2 + 0.2, s0=s5b) + sub(C4(c_resume) + 0.1, Ls2 + 0.1, '신라 선덕여왕 때 세운 천문대예요', s0=s5b)
CUES.append((C4(c_sight) + 0.2, 'sfx:pop', 0.3, ''))
CUES.append((C4(c_pause), 'sfx:tick', 0.5, 'atrim=0:%.2f,' % blink))
t = scene('s5b', s5b, d5b, body, 'dark')

s5c = t
cc0 = c_ask - 1.7
C5 = lambda c: s5c + (c - cc0)
kid_t = C5(c_ask) - 1.1
Lk = say(kid_t, '저 숲은 뭐야?', 'kid')
parts = ['첨성대 뒤쪽 숲은 계림이에요.', '경주 김씨의 시조 김알지가 이 숲의 금궤에서 태어났다는 이야기가 전해져요.', '느티나무와 왕버들이 우거져 있어요.']
tt = C5(c_ask) + 0.25
body = ''
seq = []
for ptxt in parts:
    dd = say(tt, ptxt)
    seq.append((tt, dd, ptxt))
    tt += dd + 0.25
d5c = tt + 0.6 - s5c
cv, cw, ch = car_card(960, s5c, d5c, cc0)
ph0 = seq[0][0] - s5c - 0.2
body += '<div class="studio"></div>'
body += ('<div class="photo" style="%s"><img src="assets/photos/gyerim_1.jpg" style="%s"><div class="vign"></div><div class="credit">%s</div></div>'
         % (anim('photoIn .6s cubic-bezier(.6,0,.2,1) %.2fs both' % ph0), anim('kb2 %.1fs linear %.2fs both' % (d5c - ph0, ph0)), esc(credit('gyerim_1.jpg'))))
# 앞 장면과 같은 자리에서 이어 받고, 사진이 들어오면 왼쪽 아래로 줄어듦
body += '<style>@keyframes pipShrink{from{opacity:1;transform:rotateY(6deg) rotateX(1.5deg)}to{opacity:1;transform:translate(-160px,520px) scale(.36)}}</style>'
body += ('<div class="stage" style="z-index:30"><div class="card car" style="left:230px;top:132px;width:%dpx;height:%.0fpx;transform-origin:0 0;%s">%s<i class="sheen"></i></div></div>'
         % (cw, ch, anim('carL2 .25s ease 0s both', 'pipShrink .8s cubic-bezier(.6,0,.2,1) %.2fs forwards' % ph0), cv))
body += sub(kid_t, Lk + 0.9, '저 숲은 뭐야?', '둘째', s0=s5c, ink=True)
for (a, dd, ptxt) in seq:
    body += sub(a, dd + 0.1, ptxt.rstrip('.'), s0=s5c, ink=False)
body += glow(seq[0][0], seq[-1][0] + seq[-1][1] - seq[0][0] + 0.2, s0=s5c)
CUES.append((s5c + ph0, 'sfx:whoosh', 0.4, ''))
t = scene('s5c', s5c, d5c, body, 'dark')

# ---- S6 도착하면: 해 진 뒤 동궁과 월지, P단 → 1분 여행 영상이 화면 가득 → 가족 휴대폰으로
s6 = t
INK[0] = False
cd0 = c_arrive - 0.6
C6 = lambda c: s6 + (c - cd0)
La = say(C6(c_arrive) + 0.1, '동궁과 월지에 도착했어요. 오늘 경주 여행은 여기까지예요.')
Lr2 = say(C6(c_record), '오늘 여행을 1분 영상으로 만들었어요. 가족 휴대폰 4대에도 보냈어요.')
pop = C6(c_record) + Lr2 + 0.3 - s6
segs = [(4.0, 13.4), (15.0, 17.4), (24.6, 26.8), (39.4, 41.2), (42.3, 44.4), (51.9, 53.5), (57.0, 61.8)]
reel_len = sum(b - a for a, b in segs)
d6a = pop + reel_len
PUSH6 = 1.035
ox, oy = 1060, 560
sx = ox + (SCR[0] - ox) * PUSH6
sy = oy + (SCR[1] - oy) * PUSH6
inside = ('<div class="scr" style="left:%.1fpx;top:%.1fpx;width:%.1fpx;height:%.1fpx">%s<i class="gl"></i></div>'
          % (SCR[0], SCR[1], SCR[2], SCR[3], screen_video(s6, pop + 0.5, cd0))) + strips('goldw')
body = plate('b', inside, anim('push6 %.2fs ease-out 0s both' % (pop - 0.2)))
body += chapter('04', '도착하면', at=0.4, hold=3.0)
body += glow(C6(c_arrive) + 0.1, La + 0.2, s0=s6) + sub(C6(c_arrive) + 0.1, La + 0.1, '동궁과 월지에 도착했어요. 오늘 경주 여행은 여기까지예요', s0=s6)
body += glow(C6(c_record), Lr2 + 0.2, s0=s6) + sub(C6(c_record), Lr2 + 0.1, '오늘 여행을 1분 영상으로 만들었어요', s0=s6)
vids = ''
acc = s6 + pop
for a, b in segs:
    vids += video('reel.mp4', acc, b - a, a, 1.0, 'rv', '')
    acc += b - a
body += ('<div class="reelwrap" style="%s"><div class="reelpop" style="--x:%.1fpx;--y:%.1fpx;--w:%.1fpx;--h:%.1fpx;%s">%s</div></div>'
         % (anim('reveal .01s linear %.2fs forwards' % pop), sx, sy, SCR[2] * PUSH6, SCR[3] * PUSH6, anim('reelpop .95s cubic-bezier(.65,0,.2,1) %.2fs both' % pop), vids))
CUES.append((s6, 'sfx:whoosh', 0.35, ''))
CUES.append((s6 + pop, 'sfx:whoosh', 0.45, ''))
REEL_AT = s6 + pop
REEL_LEN = reel_len
t = scene('s6', s6, d6a, body)

s6b = t
p1 = c_recv - P_OFF - 0.6
pv1, pw, ph = phone_card(400, s6b, 4.2, p1)
pv2, _, _ = phone_card(400, s6b + 4.2, 3.4, p_ticket - 0.4)
d6b = 7.6
INK[0] = True
body = '<div class="studio"></div>'
fam = [('아', '#0B4A8F', 470, 205, -9, 1.1), ('첫', '#1F7A5C', 1240, 190, 8, 1.35), ('둘', '#6B4FC8', 1430, 250, 15, 1.6)]
for (ini, col, fx, fy, rot, at) in fam:
    body += ('<div class="fam" style="left:%dpx;top:%dpx;--r:%ddeg;--c:%s;%s"><img src="assets/phone_record.jpg"><b>%s</b></div>'
             % (fx, fy, rot, col, anim('famIn .7s cubic-bezier(.2,.8,.2,1) %.2fs both' % at), ini))
    CUES.append((s6b + at + 0.1, 'sfx:ding', 0.16, ''))
kcss, kst = cam('cam_s6b', d6b, [(0, 960, 540, 1.0), (d6b, 960, 520, 1.05)])
body += kcss
body += ('<div class="stage" style="' + kst + '"><div class="card phone center" style="left:760px;top:120px;width:%dpx;height:%.0fpx;%s"><div class="bezel">%s%s</div></div></div>'
         % (pw, ph, anim('phoneUp .8s cubic-bezier(.2,.8,.2,1) 0s both'), pv1, pv2))
CUES.append((s6b + 0.6, 'sfx:ding', 0.35, ''))
t = scene('s6b', s6b, d6b, body, 'dark')

# ---- 끝
s7 = t
d7 = 6.0
INK[0] = False
body = ('<div class="title-bg"></div><div class="end" style="%s">'
        '<svg class="mark" viewBox="0 0 120 120"><rect width="120" height="120" rx="34" fill="#0B4A8F"/>'
        '<path d="M24 52 q9 -10 18 0 t18 0 t18 0 t18 0" stroke="#00AAD2" stroke-width="9" fill="none" stroke-linecap="round"/>'
        '<path d="M24 76 q9 -10 18 0 t18 0 t18 0 t18 0" stroke="#BFE6F2" stroke-width="9" fill="none" stroke-linecap="round"/></svg>'
        '<h1>구르는 가이드</h1><p>내 차의 여행 모드</p>'
        '<div class="sdk">NaviHelper · FusedLocation · Vehicle · TextToSpeech · SpeechToText</div></div>'
        '<div class="foot" style="%s">Pleos Connect 에뮬레이터 실제 실행 화면 · 차 실내 두 장면은 생성 이미지 · 목소리는 편집 때 합성</div>'
        % (anim('endIn 1s cubic-bezier(.2,.8,.2,1) .3s both'), anim('fadeIn .8s ease 1.4s both')))
t = scene('s7', s7, d7, body, 'dark')
TOTAL = t

# ------------------------------------------------------------------ HTML: 장면마다 한 파일 (영상 시각은 장면 시작 기준)
import re
CSS = open(os.path.join(HERE, 'film.css'), encoding='utf-8').read()
COMP = os.path.join(HERE, 'compositions')
os.makedirs(COMP, exist_ok=True)
PAGE = ('<!doctype html>\n<html lang="ko">\n<head>\n<meta charset="utf-8">\n<meta name="viewport" content="width=1920, height=1080">\n'
        '<title>%s</title>\n<style>\n%s\n</style>\n</head>\n<body>\n'
        '<div id="root" data-composition-id="%s" data-start="0" data-duration="%.3f" data-width="1920" data-height="1080" data-no-timeline>\n%s\n</div>\n</body>\n</html>\n')
plan = []
for sid, start, d, body, cls in SCENES:
    rel = re.sub(r'data-start="([0-9.]+)"', lambda m: 'data-start="%.3f"' % max(0.0, float(m.group(1)) - start), body)
    rel = rel.replace('src="assets/', 'src="../assets/')
    inner = '<div class="scene %s">\n%s\n</div>' % (cls, rel)
    css = CSS.replace('url(assets/', 'url(../assets/')
    page_s = PAGE % ('구르는 가이드 ' + sid, css, sid, d, inner)
    fp = os.path.join(COMP, sid + '.html')
    if not os.path.exists(fp) or open(fp, encoding='utf-8').read() != page_s:     # 바뀐 장면만 다시 렌더되게
        open(fp, 'w', encoding='utf-8').write(page_s)
    plan.append({'id': sid, 'start': round(start, 3), 'duration': round(d, 3)})
mounts = '\n'.join('<div id="m_%s" class="clip" data-composition-id="%s" data-composition-src="compositions/%s.html" data-start="%.3f" data-duration="%.3f"></div>'
                   % (p['id'], p['id'], p['id'], p['start'], p['duration']) for p in plan)
open(os.path.join(HERE, 'index.html'), 'w', encoding='utf-8').write(PAGE % ('구르는 가이드 시연 필름', '#root{position:relative;width:1920px;height:1080px;background:#05080B}', 'main', TOTAL, mounts))
json.dump({'total': TOTAL, 'scenes': plan}, open(os.path.join(HERE, 'scenes.json'), 'w'), ensure_ascii=False, indent=1)
print('길이 %.1f초, 장면 %d, 영상 조각 %d' % (TOTAL, len(SCENES), VID[0]))


# ------------------------------------------------------------------ 소리
def sfx(kind):
    p = os.path.join(A, 'sfx_%s.wav' % kind)
    if os.path.exists(p):
        return p
    if kind == 'whoosh':
        f = "anoisesrc=d=0.9:c=pink:a=0.6,highpass=f=300,lowpass=f=5000,afade=t=in:d=0.35,afade=t=out:st=0.4:d=0.5"
    elif kind == 'whoosh_s':
        f = "anoisesrc=d=0.6:c=pink:a=0.5,highpass=f=900,lowpass=f=7000,afade=t=in:d=0.2,afade=t=out:st=0.25:d=0.35"
    elif kind == 'pop':
        f = "sine=f=880:d=0.14,afade=t=out:st=0.02:d=0.12,volume=0.8"
    elif kind == 'tick':
        f = "aevalsrc='0.6*sin(2*PI*1600*t)*exp(-60*mod(t\\,0.5))':d=3"
    elif kind == 'chime':
        f = "aevalsrc='0.35*sin(2*PI*1046.5*t)*exp(-3*t)+0.25*sin(2*PI*1568*t)*exp(-4*t)':d=2"
    elif kind == 'ding':
        f = "aevalsrc='0.35*sin(2*PI*1318.5*t)*exp(-5*t)+0.2*sin(2*PI*1975.5*t)*exp(-6*t)':d=1.2"
    elif kind == 'shimmer':
        f = "aevalsrc='0.12*sin(2*PI*(2093+800*t)*t)*exp(-1.6*t)+0.1*sin(2*PI*2637*t)*exp(-2*t)':d=2.2"
    run([FF, '-y', '-f', 'lavfi', '-i', f, '-ar', '48000', '-ac', '2', p])
    return p


def envelope(points):
    """(시각, 볼륨) 점을 잇는 ffmpeg 식"""
    expr = '%.3f' % points[-1][1]
    for (t0, v0), (t1, v1) in reversed(list(zip(points, points[1:]))):
        expr = 'if(lt(t,%.3f),%.3f+(%.3f)*(t-%.3f)/%.3f,%s)' % (t1, v0, v1 - v0, t0, max(0.001, t1 - t0), expr)
    return expr


def mix(out):
    speech, fx = [], []
    for (tt, src, vol, pre) in CUES:
        if src.startswith('sfx:'):
            src = sfx(src[4:])
        (speech if '/tts/' in src else fx).append((tt, src, vol, pre))
    music = MUSIC if MUSIC and os.path.exists(MUSIC) else os.path.join(ROOT, 'companion/web/audio/reel_music.ogg')
    # 배경 음악은 처음부터 끝까지 한 곡. 요금소 가야금 때는 낮추고, 여행 영상에서는 키움. 말소리가 나오면 자동으로 눌림(사이드체인)
    reel_end = REEL_AT + REEL_LEN
    pts = [(0, 0.0), (1.2, 0.22), (7.4, 0.22), (8.4, 0.34), (S4 - 0.6, 0.34), (S4 + 0.6, 0.14), (GATE - 0.2, 0.14), (GATE + 0.4, 0.06),
           (GATE + 9.6, 0.06), (GATE + 11.2, 0.32), (REEL_AT - 1.0, 0.32), (REEL_AT + 0.6, 0.55), (reel_end, 0.55), (reel_end + 1.2, 0.34),
           (TOTAL - 3.2, 0.34), (TOTAL, 0.0)]
    args = [FF, '-y']
    filt = []
    k = 0
    for group, tag in ((speech, 's'), (fx, 'f')):
        for (tt, src, vol, pre) in group:
            args += ['-i', src]
            ms = int(max(0, tt) * 1000)
            filt.append('[%d:a]%saresample=48000,aformat=channel_layouts=stereo,volume=%.3f,adelay=%d|%d[%s%d]' % (k, pre, vol, ms, ms, tag, k))
            k += 1
    ns, nf = len(speech), len(fx)
    filt.append(''.join('[s%d]' % i for i in range(ns)) + 'amix=inputs=%d:normalize=0:duration=longest,asplit=2[sp][key0]' % ns)
    filt.append('[key0]apad=whole_dur=%.3f[key]' % TOTAL)          # 마지막 말 뒤에도 음악이 끝까지 이어지게
    filt.append(''.join('[f%d]' % i for i in range(ns, ns + nf)) + 'amix=inputs=%d:normalize=0:duration=longest[fx]' % nf)
    args += ['-stream_loop', '-1', '-i', music]
    filt.append("[%d:a]aresample=48000,aformat=channel_layouts=stereo,atrim=0:%.3f,volume='%s':eval=frame[bedv]" % (k, TOTAL, envelope(pts)))
    filt.append('[bedv][key]sidechaincompress=threshold=0.015:ratio=7:attack=20:release=380:makeup=1[bed]')
    filt.append('[sp][fx][bed]amix=inputs=3:normalize=0:duration=longest,atrim=0:%.3f,alimiter=limit=0.89[m]' % TOTAL)
    run(args + ['-filter_complex', ';'.join(filt), '-map', '[m]', '-c:a', 'aac', '-b:a', '192k', out])


audio = os.path.join(A, 'audio.m4a')
mix(audio)
print('소리', audio)
if '--mux' in sys.argv:
    i = sys.argv.index('--mux')
    src, dst = sys.argv[i + 1], sys.argv[i + 2]
    os.makedirs(os.path.dirname(os.path.abspath(dst)), exist_ok=True)
    run([FF, '-y', '-i', src, '-i', audio, '-map', '0:v', '-map', '1:a', '-c:v', 'copy', '-c:a', 'copy', '-shortest', '-movflags', '+faststart', dst])
    print('완성', dst)

