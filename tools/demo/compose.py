#!/usr/bin/env python3
"""시연 영상 편집
차량 화면 캡처(car/*.png, shots.tsv), 휴대폰 녹화(phone.webm), 여행 영상 녹화(reel.webm)를 같은 시각에 맞춰 놓고
장면 설명, 차량 안내 음성, 연주곡을 입혀 1920x1080 MP4 로 만든다.

  python3 tools/demo/compose.py /tmp/rec apk/구르는가이드_시연영상.mp4

재료: EMU=1 EVERY=250 node tools/demo/record_all.mjs (차량 캡처, 휴대폰 녹화), node tools/demo/reel_rec.mjs (여행 영상)
에뮬레이터는 소리 없이 돌리므로 목소리는 macOS say(Yuna)로 합성해 이벤트 시각에 넣는다.
"""
import glob, json, math, os, shutil, subprocess, sys
from bisect import bisect_right
from multiprocessing import Pool
from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont, ImageStat

REC = sys.argv[1] if len(sys.argv) > 1 else '/tmp/rec'
OUT = sys.argv[2] if len(sys.argv) > 2 else os.path.join(REC, 'demo.mp4')
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
FONT = os.path.join(ROOT, 'app/src/main/res/font')
AUDIO = os.path.join(ROOT, 'companion/web/audio')
FF = shutil.which('ffmpeg') or '/opt/homebrew/bin/ffmpeg'
W, H, FPS = 1920, 1080, 30
PFPS, RFPS = 15, 30
WORK = os.path.join(REC, 'work3')

# ------------------------------------------------------------------ 배치
CAR_SRC = (853, 0, 2560, 1440)           # 에뮬레이터 화면에서 앱 쪽
SC = 860 / (CAR_SRC[3] - CAR_SRC[1])
CAR_W, CAR_H = round((CAR_SRC[2] - CAR_SRC[0]) * SC), 860
PH_H = 820
PH_W = round(780 * PH_H / 1688) // 2 * 2
GAP = 72
X0 = (W - (CAR_W + GAP + PH_W + 24)) // 2
CAR_XY = (X0, 160)
PH_XY = (X0 + CAR_W + GAP + 12, 160 + (CAR_H - PH_H) // 2)
REEL_SRC = (897, 213, 1817, 731)          # 차량 화면 속 여행 영상 자리(에뮬레이터 좌표)
SMALL = (CAR_XY[0] + (REEL_SRC[0] - CAR_SRC[0]) * SC, CAR_XY[1] + (REEL_SRC[1] - CAR_SRC[1]) * SC,
         (REEL_SRC[2] - REEL_SRC[0]) * SC, (REEL_SRC[3] - REEL_SRC[1]) * SC)
BIG = (240, 168, 1440, 810)               # 여행 영상을 크게 띄울 자리 x, y, w, h

CAP = {
    1: ('휴대폰에서 담은 장소가 차량 일정으로', '블로그, 지도, 인스타그램 링크를 붙여 넣으면 담은 사람 이름과 함께 차량 일정에 들어와요', ''),
    2: ('버튼 하나로 출발', '우리 여행 일정을 순정 내비게이션의 목적지와 경유지로 한 번에 넣어요', 'NaviHelper'),
    3: ('여행지에 들어서면 여행 모드', '요금소가 가까워지면 조명을 낮추고 전주를, 지나면 가야금 연주와 함께 조명과 화면을 경주 테마로', 'FusedLocation · Vehicle'),
    4: ('담아 둔 곳은 가까워지면 먼저 제안', '식당과 카페는 새로 추천하지 않고 일행이 담아 둔 곳만, "응" 한마디면 경유지로', 'TextToSpeech · SpeechToText · NaviHelper'),
    5: ('지나는 명소를 먼저 안내', '어느 쪽 창밖인지 알려 주고, 차로를 바꾸면 잠시 멈춰요. "저 숲은 뭐야?" 하고 물어도 답해요', 'Vehicle · TextToSpeech · SpeechToText'),
    6: ('도착하면 1분 여행 영상과 여행 티켓', '차량 화면에서 함께 보고, 가족 휴대폰으로 바로 받아요', ''),
}
REEL_NOTE = '이동 경로, 여행지에 들어서던 순간, 들른 곳, 들은 설명, 일행이 올린 사진으로 만든 1분 여행 영상 (일부 장면)'


def font(w, size):
    return ImageFont.truetype(os.path.join(FONT, 'asta_%s.otf' % w), size)


def run(cmd):
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        print(' '.join(map(str, cmd)))
        print(r.stderr[-2500:])
        raise SystemExit(1)
    return r


def ease(x):
    x = max(0.0, min(1.0, x))
    return x * x * (3 - 2 * x)


def rmask(w, h, r):
    m = Image.new('L', (w, h), 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, w - 1, h - 1), radius=r, fill=255)
    return m


def prep_car(job):
    src, dst = job
    if not os.path.exists(dst):
        Image.open(src).convert('RGB').crop(CAR_SRC).resize((CAR_W, CAR_H), Image.LANCZOS).save(dst, quality=92)
    return dst


# ------------------------------------------------------------------ 그림 재료
def make_bg():
    im = Image.new('RGB', (W, H), (12, 15, 18))
    g = Image.new('RGB', (W, H), (0, 0, 0))
    d = ImageDraw.Draw(g)
    d.ellipse((-300, 500, 900, 1500), fill=(0, 44, 95))
    d.ellipse((1300, -400, 2400, 500), fill=(0, 60, 90))
    g = g.filter(ImageFilter.GaussianBlur(220))
    im = Image.blend(im, g, 0.55)
    d = ImageDraw.Draw(im)
    x, y = PH_XY
    d.rounded_rectangle((x - 12, y - 12, x + PH_W + 12, y + PH_H + 12), radius=50, fill=(22, 26, 32), outline=(58, 64, 72), width=3)
    f = font('semibold', 21)
    d.text((CAR_XY[0] + 2, CAR_XY[1] + CAR_H + 16), '차량 화면  Pleos Connect 에뮬레이터 v2.1.0 · 2560×1440 실제 실행 화면', font=f, fill=(140, 150, 160))
    d.text((x - 6, y + PH_H + 28), '동승자 휴대폰  여행 링크(웹)', font=f, fill=(140, 150, 160))
    return im


def make_caption(k):
    im = Image.new('RGBA', (W, 160), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    title, sub, sdk = CAP[k]
    x = CAR_XY[0]
    d.rounded_rectangle((x, 50, x + 56, 106), radius=16, fill=(0, 170, 210, 255))
    fb = font('extrabold', 34)
    tw = d.textlength(str(k), font=fb)
    d.text((x + 28 - tw / 2, 56), str(k), font=fb, fill=(3, 34, 46, 255))
    d.text((x + 76, 47), title, font=font('bold', 44), fill=(242, 244, 246, 255))
    d.text((x + 78, 110), sub, font=font('medium', 25), fill=(170, 178, 186, 255))
    if sdk:
        f = font('semibold', 19)
        label = 'SDK  ' + sdk
        tw = d.textlength(label, font=f)
        xr = PH_XY[0] + PH_W + 12
        d.rounded_rectangle((xr - tw - 30, 58, xr, 96), radius=19, fill=(23, 27, 32, 255), outline=(52, 60, 70, 255), width=2)
        d.text((xr - tw - 15, 66), label, font=f, fill=(150, 200, 220, 255))
    return im


def make_card(lines, note=''):
    im = Image.new('RGB', (W, H), (12, 15, 18))
    g = Image.new('RGB', (W, H), (0, 0, 0))
    d = ImageDraw.Draw(g)
    d.ellipse((-200, 300, 1100, 1500), fill=(0, 44, 95))
    d.ellipse((1100, -500, 2300, 500), fill=(0, 70, 100))
    g = g.filter(ImageFilter.GaussianBlur(240))
    im = Image.blend(im, g, 0.6)
    d = ImageDraw.Draw(im)
    cx, cy = W // 2, 330
    d.rounded_rectangle((cx - 62, cy - 62, cx + 62, cy + 62), radius=34, fill=(11, 74, 143))
    for i, col in enumerate([(0, 170, 210), (191, 230, 242)]):
        yy = cy - 15 + i * 30
        pts = [(cx - 40 + j * 2, yy + 9 * math.sin(j / 40 * 2 * math.pi)) for j in range(41)]
        d.line(pts, fill=col, width=9, joint='curve')
    y = 450
    for txt, w, size, col in lines:
        f = font(w, size)
        tw = d.textlength(txt, font=f)
        d.text(((W - tw) / 2, y), txt, font=f, fill=col)
        y += size + 28
    if note:
        f = font('medium', 20)
        tw = d.textlength(note, font=f)
        d.text(((W - tw) / 2, H - 70), note, font=f, fill=(110, 120, 130))
    return im


def main():
    os.makedirs(WORK, exist_ok=True)
    T0 = int(open(os.path.join(REC, 'car_start.txt')).read().strip())
    PH0 = int(open(os.path.join(REC, 'phone_start.txt')).read().strip())
    events = []
    for line in open(os.path.join(REC, 'car', 'events.tsv'), encoding='utf-8'):
        p = line.rstrip('\n').split('\t')
        if len(p) >= 4:
            events.append(((int(p[3]) - T0) / 1000.0, p[2]))
    phone_ev = []
    for line in open(os.path.join(REC, 'phone_events.tsv'), encoding='utf-8'):
        p = line.rstrip('\n').split('\t')
        if len(p) >= 2 and p[0].isdigit():
            phone_ev.append(((int(p[0]) - T0) / 1000.0, p[1]))
    shots = []
    for line in open(os.path.join(REC, 'car', 'shots.tsv'), encoding='utf-8'):
        a, b, f = line.rstrip('\n').split('\t')
        path = os.path.join(REC, 'car', f)
        if os.path.exists(path) and os.path.getsize(path) > 120000:     # 검은 화면(약 7만 바이트)은 뺌
            shots.append(((int(a) - T0) / 1000.0 + 0.05, path))
    shots.sort()

    def ev(prefix, after=-1e9):
        hits = [t for t, e in events if e.startswith(prefix) and t >= after]
        return hits[0] if hits else None

    def pev(word):
        hits = [t for t, e in phone_ev if word in e]
        return hits[0] if hits else None

    t_add, t_start, t_appr, t_enter = ev('phone_add'), ev('navi:'), ev('approach'), ev('enter:')
    t_offer, t_reply = ev('offer:'), ev('reply:')
    t_sight = ev('sight:cheomseongdae')
    t_pause, t_resume = ev('pause:', t_sight), ev('resume', t_sight)
    t_ask, t_arrive, t_record = ev('ask:'), ev('arrive'), ev('record')
    t_recv, t_play = pev('여행 영상 받음'), pev('영상 재생 누름')
    t_end = shots[-1][0]
    print('events', dict(add=t_add, start=t_start, appr=t_appr, enter=t_enter, offer=t_offer, reply=t_reply, sight=t_sight,
                         pause=t_pause, resume=t_resume, ask=t_ask, arrive=t_arrive, record=t_record, recv=t_recv, play=t_play, end=t_end))

    # -------------------------------------------------------------- 차량 캡처 줄이기
    cdir = os.path.join(WORK, 'car_%d' % CAR_H)
    os.makedirs(cdir, exist_ok=True)
    jobs = [(p, os.path.join(cdir, os.path.basename(p).replace('.png', '.jpg'))) for _, p in shots]
    with Pool(8) as pool:
        outs = pool.map(prep_car, jobs, chunksize=8)
    car_t = [t for t, _ in shots]
    car_f = outs
    print('차량 캡처', len(car_f))

    # 휴대폰, 여행 영상 프레임
    phdir = os.path.join(WORK, 'ph_%d' % PH_H)
    if not os.path.exists(os.path.join(phdir, 'f_00001.jpg')):
        os.makedirs(phdir, exist_ok=True)
        run([FF, '-y', '-i', os.path.join(REC, 'phone.webm'), '-vf', 'fps=%d,scale=%d:%d:flags=lanczos' % (PFPS, PH_W, PH_H), '-q:v', '3', os.path.join(phdir, 'f_%05d.jpg')])
    ph_files = sorted(glob.glob(os.path.join(phdir, 'f_*.jpg')))
    rdir = os.path.join(WORK, 'reel')
    if not os.path.exists(os.path.join(rdir, 'r_00001.jpg')):
        os.makedirs(rdir, exist_ok=True)
        run([FF, '-y', '-i', os.path.join(REC, 'reel.webm'), '-vf', 'fps=%d,scale=%d:%d:flags=lanczos' % (RFPS, BIG[2], BIG[3]), '-q:v', '2', os.path.join(rdir, 'r_%05d.jpg')])
    reel_files = sorted(glob.glob(os.path.join(rdir, 'r_*.jpg')))

    cache = {}

    def load(path):
        im = cache.get(path)
        if im is None:
            im = Image.open(path).convert('RGB')
            if len(cache) > 60:
                cache.clear()
            cache[path] = im
        return im

    def car_at(t):
        i = max(0, bisect_right(car_t, t) - 1)
        cur = load(car_f[i])
        if i + 1 < len(car_t):
            nxt = car_t[i + 1]
            a = (t - (nxt - 0.12)) / 0.12
            if 0 < a < 1:
                return Image.blend(cur, load(car_f[i + 1]), a)
        return cur

    def phone_at(t):
        k = int(round((t + (T0 - PH0) / 1000.0) * PFPS))
        return load(ph_files[max(0, min(len(ph_files) - 1, k))])

    def reel_at(rt):
        k = int(round(rt * RFPS))
        return load(reel_files[max(0, min(len(reel_files) - 1, k))])

    # -------------------------------------------------------------- 여행 영상이 화면에 처음 뜬 시각
    # 에뮬레이터 WebView 는 애니메이션이 실제보다 느리게 돌아서(약 0.7배) 크게 보여 줄 때는 reel.webm(제 속도)을 씀.
    # 차량 화면과 reel.webm 모두 '준비 중' 화면(어두움) 다음에 첫 사진 장면이 밝게 뜨는 순간을 기준으로 맞춤
    bx = [(REEL_SRC[0] - CAR_SRC[0]) * SC + 6, (REEL_SRC[1] - CAR_SRC[1]) * SC + 6, (REEL_SRC[2] - CAR_SRC[0]) * SC - 6, (REEL_SRC[3] - CAR_SRC[1]) * SC - 6]
    bx = tuple(int(round(v)) for v in bx)

    def bright(im):
        return ImageStat.Stat(im.convert('L').resize((96, 54))).mean[0]

    def first_on(items):
        dark = False                # 녹화 첫 장은 흰 빈 페이지일 수 있어, 어두운 '준비 중' 화면을 지난 뒤에 밝아지는 순간을 찾음
        for t, im in items:
            b = bright(im)
            if b < 25:
                dark = True
            elif dark and b > 45:
                return t
        return None

    car_on = first_on((t, Image.open(f).crop(bx)) for t, f in zip(car_t, car_f) if t > t_record) or t_record + 5.0
    reel_on = first_on((k / RFPS, Image.open(reel_files[k])) for k in range(0, len(reel_files), 3)) or 2.0
    print('여행 영상 첫 장면: 차량 %.2f초, reel.webm %.2f초' % (car_on, reel_on))

    # -------------------------------------------------------------- 편집 목록
    TL = []

    def seg(a, b, speed, k):
        if a is None or b is None:
            return
        a, b = max(0.0, a), min(t_end, b)
        if b - a >= 0.2:
            TL.append(dict(kind='car', a=a, b=b, speed=speed, k=k, dur=(b - a) / speed))

    seg(max(t_add - 7.0, (PH0 - T0) / 1000.0 + 1.6), t_add + 4.0, 1.0, 1)     # 휴대폰 웹이 다 뜬 뒤부터
    seg(t_add + 4.0, t_start - 0.5, 3.0, 1)
    seg(t_start - 0.5, t_appr - 0.5, 1.0, 2)
    seg(t_appr - 0.5, t_enter - 0.4, 1.5, 3)
    seg(t_enter - 0.4, t_enter + 15.5, 1.0, 3)
    seg(t_enter + 15.5, t_offer - 1.0, 8.0, 3)
    seg(t_offer - 1.0, t_reply + 4.5, 1.0, 4)
    t_photo = ev('toast:엄마가 여행 사진', t_reply) or (t_reply + 10)
    seg(t_reply + 4.5, t_photo + 2.0, 2.0, 4)
    seg(t_photo + 2.0, t_sight - 1.0, 6.0, 4)
    seg(t_sight - 1.0, t_resume + 3.4, 1.0, 5)
    seg(t_resume + 3.4, t_ask - 1.8, 3.0, 5)
    seg(t_ask - 1.8, t_ask + 13.5, 1.0, 5)
    seg(t_ask + 13.5, t_arrive - 0.5, 6.0, 5)
    freeze = car_on + 2.6
    if freeze - t_arrive > 14:
        seg(t_arrive - 0.5, t_arrive + 7.0, 1.0, 6)
        seg(t_arrive + 7.0, freeze - 4.0, 2.0, 6)
        seg(freeze - 4.0, freeze, 1.0, 6)
    else:
        seg(t_arrive - 0.5, freeze, 1.0, 6)
    resume = max(freeze + 1.0, (t_recv or freeze + 20) - 1.0)
    r0 = reel_on + 1.9          # 차량 화면 속 영상이 느리게 돌아 2.6초 동안 제 속도 기준 약 1.9초 진행
    # 표지와 경로, 요금소 → 엄마가 담은 카페 → 첨성대와 계림 → 일행이 올린 사진 → 여행 티켓
    ranges = [(r0, r0 + 14.0), (r0 + 20.4, r0 + 23.4), (r0 + 35.2, r0 + 41.6), (r0 + 47.8, r0 + 50.4), (r0 + 52.8, r0 + 58.6)]
    pop_len = sum(b - a for a, b in ranges) - 0.4 * (len(ranges) - 1)
    TL.append(dict(kind='pop', k=6, freeze=freeze, resume=resume, ranges=ranges, dur=pop_len))
    t_close = pev('영상 닫음')
    seg(resume, min(t_end, (t_close or (t_play or resume) + 8.0) + 2.0), 1.0, 6)

    cursor = 5.0
    for s in TL:
        s['start'] = cursor
        cursor += s['dur']
    total = cursor + 5.0
    print('길이 %.1f초' % total)

    # -------------------------------------------------------------- 화면 그리기
    BG = make_bg()
    CAPS = {k: make_caption(k) for k in CAP}
    PH_MASK = rmask(PH_W, PH_H, 40)
    TITLE = make_card([('구르는 가이드', 'extrabold', 96, (242, 244, 246)), ('내 차의 여행 모드', 'semibold', 40, (127, 214, 234)),
                       ('서울에서 경주로 떠나는 가족 여행, 플레오스 커넥트 에뮬레이터 시연', 'medium', 28, (170, 178, 186))])
    OUTRO = make_card([('구르는 가이드', 'extrabold', 80, (242, 244, 246)), ('여행 준비부터 명소 안내, 여행 기록까지 차량에서', 'semibold', 36, (127, 214, 234)),
                       ('github.com/AIKONG2024/gureuneun-guide', 'medium', 28, (170, 178, 186))],
                      note='에뮬레이터는 소리 없이 실행해, 영상 속 안내 음성과 사람 목소리는 편집 때 합성해 넣었습니다')
    NAVI = None
    np_ = os.path.join(REC, 'navi_route.png')
    if os.path.exists(np_):
        n = Image.open(np_).convert('RGB').crop((853, 70, 2560, 1300)).resize((560, 404), Image.LANCZOS)
        card = Image.new('RGB', (580, 470), (30, 35, 41))
        card.paste(n, (10, 10))
        ImageDraw.Draw(card).text((18, 424), '순정 내비게이션에 목적지와 경유지 입력 (실제 화면)', font=font('semibold', 21), fill=(220, 226, 232))
        NAVI = card

    def side(t, k=None, speed=1.0):
        frame = BG.copy()
        frame.paste(car_at(t), CAR_XY)
        frame.paste(phone_at(t), PH_XY, PH_MASK)
        if NAVI is not None and k == 2 and t_start + 0.6 <= t <= t_start + 5.4:
            al = min(1.0, (t - t_start - 0.6) / 0.4, (t_start + 5.4 - t) / 0.4)
            x = CAR_XY[0] + CAR_W - NAVI.width - 26
            y = CAR_XY[1] + CAR_H - NAVI.height - 150
            region = frame.crop((x, y, x + NAVI.width, y + NAVI.height))
            frame.paste(Image.blend(region, NAVI, al), (x, y))
        if speed > 1.01:
            d = ImageDraw.Draw(frame)
            txt = '%s배속으로 건너뛰는 중' % ('%d' % round(speed) if abs(speed - round(speed)) < 0.05 else '%.1f' % speed)
            f = font('bold', 20)
            tw = d.textlength(txt, font=f)
            xr = CAR_XY[0] + CAR_W
            y0 = CAR_XY[1] + CAR_H + 10
            d.rounded_rectangle((xr - tw - 30, y0, xr, y0 + 36), radius=18, fill=(0, 170, 210))
            d.text((xr - tw - 15, y0 + 6), txt, font=f, fill=(3, 34, 46))
        return frame

    def dim(im):
        return Image.blend(im.filter(ImageFilter.GaussianBlur(18)), Image.new('RGB', (W, H), (6, 8, 10)), 0.72)

    pop = next(s for s in TL if s['kind'] == 'pop')
    S_f, S_r = side(pop['freeze']), side(pop['resume'])
    D_f, D_r = dim(S_f), dim(S_r)
    shadow = Image.new('L', (W, H), 0)
    ImageDraw.Draw(shadow).rounded_rectangle((BIG[0] + 6, BIG[1] + 18, BIG[0] + BIG[2] - 6, BIG[1] + BIG[3] + 26), radius=30, fill=170)
    shadow = shadow.filter(ImageFilter.GaussianBlur(26))
    note = Image.new('RGBA', (W, 60), (0, 0, 0, 0))
    dn = ImageDraw.Draw(note)
    fn = font('medium', 23)
    tw = dn.textlength(REEL_NOTE, font=fn)
    dn.text(((W - tw) / 2, 14), REEL_NOTE, font=fn, fill=(196, 204, 212, 255))
    big_mask = rmask(BIG[2], BIG[3], 28)
    # 이어 붙인 구간: 편집 안 시작 시각
    starts = []
    acc = 0.0
    for a, b in ranges:
        starts.append(acc)
        acc += (b - a) - 0.4

    def pop_reel(u):
        i = max(0, bisect_right(starts, u) - 1)
        a, b = ranges[i]
        im = reel_at(a + (u - starts[i]))
        if i > 0 and u - starts[i] < 0.4:
            pa, pb = ranges[i - 1]
            prev = reel_at(pa + (u - starts[i - 1]))
            im = Image.blend(prev, im, (u - starts[i]) / 0.4)
        return im

    def pop_frame(s, u):
        D = s['dur']
        e_in, e_out = ease(u / 0.8), ease((D - u) / 0.6)
        reveal = min(e_in, e_out)
        sharp, dark = (S_f, D_f) if u < D / 2 else (S_r, D_r)
        frame = Image.blend(sharp, dark, reveal)
        if e_in >= 1:
            frame.paste((0, 0, 0), (0, 0, W, H), shadow.point(lambda v: int(v * e_out)))
        x = SMALL[0] + (BIG[0] - SMALL[0]) * e_in
        y = SMALL[1] + (BIG[1] - SMALL[1]) * e_in
        w = int(round(SMALL[2] + (BIG[2] - SMALL[2]) * e_in))
        h = int(round(SMALL[3] + (BIG[3] - SMALL[3]) * e_in))
        r = pop_reel(u)
        if (w, h) != (BIG[2], BIG[3]):
            r = r.resize((w, h), Image.BILINEAR)
            m = rmask(w, h, int(18 + 10 * e_in))
        else:
            m = big_mask
        if e_out < 1:
            m = m.point(lambda v: int(v * e_out))
        frame.paste(r, (int(round(x)), int(round(y))), m)
        if reveal > 0.02:
            n2 = note.copy()
            n2.putalpha(note.getchannel('A').point(lambda v: int(v * reveal)))
            frame.paste(n2, (0, BIG[1] + BIG[3] + 14), n2)
        return frame

    video = os.path.join(WORK, 'video.mp4')
    enc = subprocess.Popen([FF, '-y', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-s', '%dx%d' % (W, H), '-r', str(FPS), '-i', '-',
                            '-c:v', 'libx264', '-preset', 'medium', '-crf', '18', '-pix_fmt', 'yuv420p', video], stdin=subprocess.PIPE, stderr=subprocess.DEVNULL)

    def fade(im, a):
        return Image.blend(Image.new('RGB', (W, H), (0, 0, 0)), im, max(0.0, min(1.0, a)))

    starts_tl = [s['start'] for s in TL]
    nframes = int(round(total * FPS))
    for fi in range(nframes):
        T = fi / FPS
        if T < 5.0:
            frame = fade(TITLE, min(1.0, T / 0.6, (5.0 - T) / 0.6))
        elif T >= total - 5.0:
            frame = fade(OUTRO, min(1.0, (T - (total - 5.0)) / 0.6, (total - T) / 0.6))
        else:
            s = TL[max(0, bisect_right(starts_tl, T) - 1)]
            u = T - s['start']
            if s['kind'] == 'pop':
                frame = pop_frame(s, u)
            else:
                frame = side(s['a'] + u * s['speed'], s['k'], s['speed'])
            cap = CAPS[s['k']]
            frame.paste(cap, (0, 0), cap)
        enc.stdin.write(frame.tobytes())
        if fi % 600 == 0:
            print('frame', fi, '/', nframes, flush=True)
    enc.stdin.close()
    enc.wait()

    # -------------------------------------------------------------- 소리
    def to_edit(t):
        for s in TL:
            if s['kind'] == 'car' and s['a'] <= t < s['b']:
                return s['start'] + (t - s['a']) / s['speed'], s['speed']
        return None, None

    def tts(text, name, rate=188, pitch=1.0):
        aiff = os.path.join(WORK, name + '.aiff')
        wav = os.path.join(WORK, name + '.wav')
        subprocess.run(['say', '-v', 'Yuna', '-r', str(rate), '-o', aiff, text], check=True)
        af = 'aresample=48000'
        if pitch != 1.0:
            af = 'asetrate=%d,aresample=48000,atempo=%f' % (int(22050 * pitch), 1 / pitch)
        run([FF, '-y', '-i', aiff, '-af', af, '-ac', '2', wav])
        return wav

    cues = [dict(t=0.0, path=os.path.join(AUDIO, 'reel_music.ogg'), vol=0.32, pre='atrim=0:6.5,afade=t=in:st=0:d=0.8,afade=t=out:st=5:d=1.5,')]
    n = 0
    for i, (t, e) in enumerate(events):
        kind, _, rest = e.partition(':')
        et, sp = to_edit(t)
        if et is None:
            continue
        if kind == 'music':
            cues.append(dict(t=et, path=os.path.join(AUDIO, os.path.basename(rest)), vol=0.85, pre=''))
        if sp > 1.05:
            continue
        if kind == 'speak':
            n += 1
            # 다음 말이나 멈춤이 오면 끊김 (차량도 새 안내가 오면 앞 안내를 끊음)
            nxt = [tt for tt, ee in events[i + 1:] if tt > t + 0.05 and ee.split(':')[0] in ('speak', 'pause', 'say', 'ask')]
            cut = (nxt[0] - t) if nxt else 30.0
            pre = 'atrim=0:%f,afade=t=out:st=%f:d=0.15,' % (cut, max(0.0, cut - 0.15)) if cut < 30 else ''
            cues.append(dict(t=et + 0.2, path=tts(rest, 'v%02d' % n), vol=1.0, pre=pre))
        elif kind == 'say':
            n += 1
            child = '뭐야' in rest
            cues.append(dict(t=et - 1.4, path=tts(rest, 'u%02d' % n, rate=175, pitch=1.18 if child else 0.88), vol=0.95, pre=''))
    et0, _ = to_edit(car_on - 0.2)
    if et0 is not None:
        tail = total - et0
        cues.append(dict(t=et0, path=os.path.join(AUDIO, 'reel_music.ogg'), vol=0.55, pre='afade=t=out:st=%f:d=2.5,' % max(1.0, tail - 2.6)))

    args = [FF, '-y']
    filt = []
    for i, c in enumerate(cues):
        args += ['-i', c['path']]
        ms = max(0, int(c['t'] * 1000))
        filt.append('[%d:a]%saresample=48000,aformat=channel_layouts=stereo,volume=%f,adelay=%d|%d[a%d]' % (i, c['pre'], c['vol'], ms, ms, i))
    filt.append(''.join('[a%d]' % i for i in range(len(cues))) + 'amix=inputs=%d:normalize=0:duration=longest,atrim=0:%f,alimiter=limit=0.89[m]' % (len(cues), total))
    audio = os.path.join(WORK, 'audio.m4a')
    run(args + ['-filter_complex', ';'.join(filt), '-map', '[m]', '-c:a', 'aac', '-b:a', '192k', audio])
    os.makedirs(os.path.dirname(os.path.abspath(OUT)), exist_ok=True)
    run([FF, '-y', '-i', video, '-i', audio, '-c:v', 'copy', '-c:a', 'aac', '-b:a', '192k', '-shortest', '-movflags', '+faststart', OUT])
    json.dump({'timeline': [{k: (round(v, 2) if isinstance(v, float) else v) for k, v in s.items()} for s in TL], 'total': total, 'car_on': car_on, 'reel_on': reel_on,
               'cues': [(round(c['t'], 2), os.path.basename(c['path'])) for c in cues]},
              open(os.path.join(WORK, 'edl.json'), 'w'), ensure_ascii=False, indent=1)
    print('완성', OUT, '%.1f초' % total)


if __name__ == '__main__':
    main()

