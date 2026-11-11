#!/usr/bin/env python3
"""구르는 가이드 시연 영상 편집

record.mjs 가 남긴 녹화(차 화면, 차 소리, 휴대폰 화면, Virtual Console)와 timeline.json 의 시각 표시(marks)를 읽어
edit.json 에 적은 순서대로 1920x1080 영상 한 편을 만든다. 화면은 한 장씩 Pillow 로 짜서 ffmpeg 에 넘긴다.

사용: python3 compose.py <녹화 폴더> <edit.json> <결과.mp4>
edit.json 의 clips 한 줄: {"scene": "gyeongju", "from": "ask-0.5", "to": "answer+6", "layout": "car", "caption": "창밖이 궁금하면, {물어보세요}"}
  from, to   장면 시작부터 초. "표시이름+초"로도 적음
  layout     car(차 화면만) | phone(차 화면과 휴대폰) | vc:gear, vc:signal, vc:light(차 화면과 Virtual Console 일부)
  phone      휴대폰 화면에서 보여 줄 부분 [x, y, 너비, 높이] (824x1760 기준). 적지 않으면 전체
  caption    아래 자막. {} 안은 강조색
  say        말풍선 [{"text": "응", "at": "yes+0.9", "dur": 1.6}]
  dim        "car" 면 차 화면을 어둡게 해 옆 칸으로 눈이 가게 함
"""
import json
import os
import re
import subprocess
import sys
import wave

import numpy as np
from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

W, H, FPS = 1920, 1080, 30
SR = 44100
FONT = '/System/Library/Fonts/AppleSDGothicNeo.ttc'
REG, MED, SEMI, BOLD = 0, 2, 4, 6
BG = (10, 12, 16)
ACCENT = (0, 170, 210)
SUB = (139, 146, 158)
HERE = os.path.dirname(os.path.abspath(__file__))

# 1920x1080 으로 녹화한 에뮬레이터 화면에서 앱이 뜨는 자리 (넓은 창)
APP = (646, 72, 1250, 900)
CAR = (1195, 860)
CAR_Y = 34
CAR_X_SOLO = (W - CAR[0]) // 2
CAR_X_DUO = 44
SIDE_X = CAR_X_DUO + CAR[0] + 40
SIDE_W = W - SIDE_X - 44
PHONE = (400, 854)
# Virtual Console(1280x720) 에서 보여 줄 부분: (x, y, 너비, 높이, 키우는 배율)
VC = {
    'gear': (1087, 133, 175, 122, 3.2),
    'signal': (319, 112, 419, 142, 1.36),
    'light': (241, 581, 308, 108, 1.85),
}


def font(size, idx=REG):
    return ImageFont.truetype(FONT, size, index=idx)


def ease(x):
    x = min(1.0, max(0.0, x))
    return 1 - (1 - x) ** 3


def lerp(a, b, k):
    return a + (b - a) * k


_masks = {}


def mask(size, radius):
    key = (size, radius)
    if key not in _masks:
        m = Image.new('L', size, 0)
        ImageDraw.Draw(m).rounded_rectangle((0, 0, size[0] - 1, size[1] - 1), radius=radius, fill=255)
        _masks[key] = m
    return _masks[key]


_shadows = {}


def shadow(size, radius):
    key = (size, radius)
    if key not in _shadows:
        pad = 60
        im = Image.new('L', (size[0] + pad * 2, size[1] + pad * 2), 0)
        ImageDraw.Draw(im).rounded_rectangle((pad, pad + 14, pad + size[0], pad + size[1] + 14), radius=radius, fill=150)
        _shadows[key] = (im.filter(ImageFilter.GaussianBlur(26)), pad)
    return _shadows[key]


def panel(canvas, im, xy, radius, alpha=1.0, border=None):
    """둥근 모서리와 그림자를 붙여 화면 한 칸을 올림"""
    x, y = int(xy[0]), int(xy[1])
    sh, pad = shadow(im.size, radius)
    black = Image.new('RGB', sh.size, (0, 0, 0))
    canvas.paste(black, (x - pad, y - pad), sh if alpha >= 1 else sh.point(lambda v: int(v * alpha)))
    m = mask(im.size, radius)
    if alpha < 1:
        m = m.point(lambda v: int(v * alpha))
    canvas.paste(im, (x, y), m)
    if border and alpha >= 1:
        ImageDraw.Draw(canvas).rounded_rectangle((x - 2, y - 2, x + im.size[0] + 1, y + im.size[1] + 1), radius=radius + 2, outline=border, width=3)


def background():
    y, x = np.mgrid[0:H, 0:W]
    d = np.sqrt(((x - W / 2) / (W / 2)) ** 2 + ((y - H * 0.45) / (H / 2)) ** 2)
    g = np.clip(1 - d * 0.55, 0, 1)[..., None]
    arr = np.array(BG)[None, None, :] + g * np.array([10, 14, 22])[None, None, :]
    return Image.fromarray(arr.astype(np.uint8), 'RGB')


def text_image(text, size, weight=BOLD, color=(255, 255, 255), accent=ACCENT, shadow_on=True, gap=14):
    """{} 로 감싼 부분만 강조색으로 그린 글. 줄바꿈은 \n"""
    f = font(size, weight)
    probe = ImageDraw.Draw(Image.new('RGB', (8, 8)))
    lines = []
    for line in text.split('\n'):
        parts = [p for p in re.split(r'(\{[^}]*\})', line) if p]
        runs = [(p[1:-1], accent) if p.startswith('{') else (p, color) for p in parts]
        lines.append((runs, sum(probe.textlength(t, font=f) for t, _ in runs)))
    lh = int(size * 1.28)
    w = int(max(l[1] for l in lines)) + 24
    h = lh * len(lines) + gap * (len(lines) - 1) + 16
    im = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    for i, (runs, lw) in enumerate(lines):
        x = (w - lw) / 2
        y = 6 + i * (lh + gap)
        for t, c in runs:
            if shadow_on:
                d.text((x + 2, y + 3), t, font=f, fill=(0, 0, 0, 170))
            d.text((x, y), t, font=f, fill=c + (255,))
            x += probe.textlength(t, font=f)
    return im


def bubble_image(text):
    f = font(46, SEMI)
    probe = ImageDraw.Draw(Image.new('RGB', (8, 8)))
    tw = int(probe.textlength('“' + text + '”', font=f))
    w, h = tw + 84, 104
    im = Image.new('RGBA', (w, h + 26), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle((0, 0, w - 1, h - 1), radius=h // 2, fill=(255, 255, 255, 244))
    d.polygon([(64, h - 4), (104, h - 4), (70, h + 24)], fill=(255, 255, 255, 244))
    d.text((42, 22), '“' + text + '”', font=f, fill=(14, 18, 24, 255))
    return im


def pill_image(text, size=26, fg=(214, 219, 226), outline=(60, 67, 80)):
    f = font(size, MED)
    probe = ImageDraw.Draw(Image.new('RGB', (8, 8)))
    tw = int(probe.textlength(text, font=f))
    w, h = tw + 44, size + 26
    im = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle((1, 1, w - 2, h - 2), radius=h // 2, fill=(20, 24, 31, 230), outline=outline, width=2)
    d.text((22, 11), text, font=f, fill=fg + (255,))
    return im


def over(canvas, im, xy, alpha=1.0):
    if alpha <= 0:
        return
    if alpha < 1:
        im = im.copy()
        im.putalpha(im.getchannel('A').point(lambda v: int(v * alpha)))
    canvas.paste(im, (int(xy[0]), int(xy[1])), im)


class Pipe:
    """ffmpeg 로 영상의 한 구간을 FPS 에 맞춰 한 장씩 읽음"""

    def __init__(self, path, ss, vf, size, speed=1.0):
        self.size = size
        chain = (f'setpts=PTS/{speed},' if speed != 1 else '') + vf + f',fps={FPS}'
        self.p = subprocess.Popen(['ffmpeg', '-loglevel', 'error', '-ss', f'{max(0, ss):.3f}', '-i', path, '-vf', chain, '-an', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-'], stdout=subprocess.PIPE)
        self.last = Image.new('RGB', size, (0, 0, 0))

    def read(self):
        n = self.size[0] * self.size[1] * 3
        buf = self.p.stdout.read(n)
        if len(buf) == n:
            self.last = Image.frombuffer('RGB', self.size, buf, 'raw', 'RGB', 0, 1).copy()
        return self.last

    def close(self):
        self.p.kill()
        self.p.wait()


def when(expr, marks):
    """ "ask-0.5" 같은 표시를 초로 """
    if isinstance(expr, (int, float)):
        return float(expr)
    m = re.match(r'^\s*([a-z_0-9]+?)?\s*([+-]\s*[\d.]+)?\s*$', expr)
    name, off = m.group(1), m.group(2)
    if name and re.fullmatch(r'[\d.]+', name):
        return float(name)
    base = marks[name] if name else 0.0
    return base + (float(off.replace(' ', '')) if off else 0.0)


def calibrate(sc):
    """차 화면 녹화가 실제로 시작된 때를 맞춤: [여행 시작]을 누른 순간 화면이 크게 바뀌는 곳을 찾음"""
    base = (sc['start'] - sc['emuStart']) / 1000.0
    t = sc['marks'].get('started')
    if t is None:
        return base
    w, h = 320, 180
    ss = max(0, base + t - 3)
    p = subprocess.run(['ffmpeg', '-loglevel', 'error', '-ss', f'{ss:.3f}', '-i', sc['emuFile'], '-t', '6', '-vf', f'fps=30,scale={w}:{h}', '-f', 'rawvideo', '-pix_fmt', 'gray', '-'], capture_output=True)
    a = np.frombuffer(p.stdout, dtype=np.uint8)
    n = len(a) // (w * h)
    fr = a[:n * w * h].reshape(n, h, w).astype(np.int16)
    for i in range(1, n):
        if np.abs(fr[i] - fr[i - 1])[:, 110:].mean() > 18:
            fix = (ss + i / 30.0) - (t + 0.08)
            print(f'  {sc["name"]}: 차 화면 시작 보정 {fix - base:+.2f}s')
            return fix
    return base


def read_audio(path):
    p = subprocess.run(['ffmpeg', '-loglevel', 'error', '-i', path, '-ac', '2', '-ar', str(SR), '-f', 'f32le', '-'], capture_output=True)
    return np.frombuffer(p.stdout, dtype=np.float32).reshape(-1, 2).copy()


def put(dst, src, at, gain=1.0):
    i = int(round(at * SR))
    if i < 0:
        src, i = src[-i:], 0
    n = min(len(src), len(dst) - i)
    if n > 0:
        dst[i:i + n] += src[:n] * gain


def leveled(a, target_db=-21.0):
    """말소리 크기를 맞춤. 에뮬레이터가 내보낸 차 소리는 아주 작고(영어 음성은 더 작음) 질문 목소리는 커서 그대로 섞으면 들쭉날쭉함"""
    n = int(0.05 * SR)
    m = len(a) // n
    if m == 0:
        return a
    rms = np.sqrt((a[:m * n].reshape(m, n, 2) ** 2).mean(axis=(1, 2)))
    loud = rms[rms > 10 ** (-58 / 20)]
    if len(loud) == 0:
        return a
    gain = min(60.0, 10 ** (target_db / 20) / float(np.sqrt((loud ** 2).mean())))
    return np.tanh(a * gain * 1.2) / 1.2


def fade(a, sec=0.02):
    n = min(int(sec * SR), len(a) // 2)
    if n > 0:
        r = np.linspace(0, 1, n, dtype=np.float32)[:, None]
        a[:n] *= r
        a[-n:] *= r[::-1]
    return a


def card(kind, icon):
    im = Image.new('RGB', (W, H), BG)
    im.paste(background())
    ic = icon.resize((196, 196), Image.LANCZOS)
    if kind == 'title':
        im.paste(ic, ((W - 196) // 2, 300), ic)
        t = text_image('구르는 가이드', 104, BOLD, shadow_on=False)
        im.paste(t, ((W - t.size[0]) // 2, 540), t)
        s = text_image('지나는 곳마다 이야기를 들려주는 차 안의 여행 가이드', 44, REG, color=(190, 196, 206), shadow_on=False)
        im.paste(s, ((W - s.size[0]) // 2, 706), s)
    else:
        im.paste(ic, ((W - 196) // 2, 214), ic)
        t = text_image('구르는 가이드', 92, BOLD, shadow_on=False)
        im.paste(t, ((W - t.size[0]) // 2, 446), t)
        s = text_image('Pleos Connect SDK 다섯 가지로 만들었습니다', 42, MED, color=(214, 219, 226), shadow_on=False)
        im.paste(s, ((W - s.size[0]) // 2, 606), s)
        k = text_image('Vehicle   NaviHelper   FusedLocation   TextToSpeech   SpeechToText', 32, REG, color=ACCENT, shadow_on=False)
        im.paste(k, ((W - k.size[0]) // 2, 684), k)
        g = text_image('github.com/AIKONG2024/gureuneun-guide', 34, REG, color=SUB, shadow_on=False)
        im.paste(g, ((W - g.size[0]) // 2, 812), g)
    return im


def main():
    rec, edit_path, out = sys.argv[1], sys.argv[2], sys.argv[3]
    T = json.load(open(os.path.join(rec, 'timeline.json')))
    E = json.load(open(edit_path))
    scenes = {s['name']: s for s in T['scenes']}
    for s in scenes.values():
        s['emuFix'] = calibrate(s)
        s['vcFix'] = (s['start'] - s['vcStart']) / 1000.0 + E.get('vcShift', 0.0)
    bg = background()
    icon = Image.open(os.path.join(HERE, 'assets', 'icon.png')).convert('RGBA')
    notice = text_image(E.get('notice', ''), 24, REG, color=(112, 119, 131), shadow_on=False) if E.get('notice') else None
    labels = {k: text_image(v, 24, MED, color=SUB, shadow_on=False) for k, v in {'phone': '동승자 휴대폰', 'vc': '차 신호 (Virtual Console)'}.items()}

    clips = E['clips']
    for c in clips:
        if 'card' in c:
            c['dur'] = float(c['dur'])
        else:
            m = scenes[c['scene']]['marks']
            c['t0'], c['t1'] = when(c['from'], m), when(c['to'], m)
            c['dur'] = (c['t1'] - c['t0']) / c.get('speed', 1)
        c['n'] = int(round(c['dur'] * FPS))
    total = sum(c['n'] for c in clips) / FPS
    print(f'장면 {len(clips)}개, {total:.1f}초')

    tmp_audio = out + '.wav'
    enc = subprocess.Popen(['ffmpeg', '-y', '-loglevel', 'error', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-s', f'{W}x{H}', '-r', str(FPS), '-i', '-',
                            '-c:v', 'libx264', '-preset', 'medium', '-crf', '19', '-pix_fmt', 'yuv420p', '-an', out + '.video.mp4'], stdin=subprocess.PIPE)

    audio = np.zeros((int(total * SR) + SR, 2), dtype=np.float32)
    level = np.zeros(int(total * SR) + SR, dtype=np.float32)   # 음악 크기 (장면마다)
    scene_audio = {}
    prev_canvas, prev_layout, prev_caption, car_x, prev_phone = None, None, None, CAR_X_SOLO, None
    cap_cache = {}
    at = 0.0
    for ci, c in enumerate(clips):
        nxt = clips[ci + 1] if ci + 1 < len(clips) else {}
        n = c['n']
        i0 = int(at * SR)
        level[i0:i0 + int(c['dur'] * SR) + 1] = c.get('music', E.get('musicLevel', {}).get('card' if 'card' in c else 'scene', 0.1))
        moved = 'card' not in c and prev_layout is not None and (c.get('layout', 'car') == 'car') != (prev_layout == 'car')
        xf = int(round(c.get('fade', 0.0 if moved or prev_canvas is None else 0.2) * FPS))
        if 'card' in c:
            still = card(c['card'], icon)
            for i in range(n):
                t = i / FPS
                k = ease(t / 0.5) * ease((c['dur'] - t) / 0.4)
                frame = Image.blend(Image.new('RGB', (W, H), BG), still, k)
                if prev_canvas is not None and i < xf:
                    frame = Image.blend(prev_canvas, frame, (i + 1) / (xf + 1))
                enc.stdin.write(frame.tobytes())
            prev_canvas, prev_layout, prev_caption = frame, None, None
            car_x = CAR_X_SOLO
            at += n / FPS
            print(f'  {ci + 1:2d} 카드 {c["card"]} {c["dur"]:.1f}s')
            continue

        sc = scenes[c['scene']]
        marks = sc['marks']
        layout = c.get('layout', 'car')
        side = None if layout == 'car' else ('phone' if layout == 'phone' else 'vc')
        speed = c.get('speed', 1)
        zoom = c.get('zoom')
        if zoom:
            car = Pipe(sc['emuFile'], sc['emuFix'] + c['t0'], 'null', (1920, 1080), speed)
        else:
            car = Pipe(sc['emuFile'], sc['emuFix'] + c['t0'], f'crop={APP[2]}:{APP[3]}:{APP[0]}:{APP[1]},scale={CAR[0]}:{CAR[1]}:flags=lanczos', CAR, speed)
        vcp = None
        if side == 'vc':
            x, y, w, h, k = VC[layout.split(':')[1]]
            vsize = (int(w * k) // 2 * 2, int(h * k) // 2 * 2)
            vcp = Pipe(sc['vcFile'], sc['vcFix'] + c['t0'], f'crop={w}:{h}:{x}:{y},scale={vsize[0]}:{vsize[1]}:flags=lanczos', vsize, speed)
        frames = sc['phoneFrames']
        pi, pname, pimg = 0, None, None
        target_x = CAR_X_SOLO if side is None else CAR_X_DUO
        start_x = car_x
        same_side = prev_layout == layout and prev_phone == c.get('phone')
        cap = c.get('caption')
        if cap and cap not in cap_cache:
            cap_cache[cap] = text_image(cap, 58, BOLD)
        says = [(when(s['at'], marks), s.get('dur', 2.0), bubble_image(s['text'])) for s in c.get('say', [])]
        chip = pill_image(c['chip']) if c.get('chip') else None
        for i in range(n):
            t = i / FPS
            ts = c['t0'] + t * speed
            canvas = bg.copy()
            cx = lerp(start_x, target_x, ease(t / 0.45))
            cim = car.read()
            if zoom:
                k = ease((t - zoom.get('at', 0.6)) / zoom.get('dur', 0.9))
                sx, sy, sw, sh = (lerp(a, b, k) for a, b in zip((0, 0, 1920, 1080), APP))
                dw, dh = lerp(1528, CAR[0], k), CAR[1]
                cim = cim.crop((int(sx), int(sy), int(sx + sw), int(sy + sh))).resize((int(dw), int(dh)), Image.BILINEAR)
                cx = (W - dw) / 2
            if c.get('dim') == 'car':
                cim = Image.eval(cim, lambda v: int(v * 0.45))
            panel(canvas, cim, (cx, CAR_Y), 26)
            if side:
                a = 1.0 if same_side else ease((t - 0.1) / 0.35)
                dx = 0 if same_side else (1 - a) * 70
                if side == 'phone':
                    while pi + 1 < len(frames) and frames[pi + 1][0] <= ts:
                        pi += 1
                    if frames and frames[pi][1] != pname:
                        pname = frames[pi][1]
                        pimg = Image.open(os.path.join(sc['phoneDir'], pname)).convert('RGB')
                        crop = c.get('phone')
                        if crop:
                            # 휴대폰 화면의 한 부분만 키워서 (퀴즈처럼 글자를 읽어야 할 때)
                            pw = 540
                            pimg = pimg.crop((crop[0], crop[1], crop[0] + crop[2], crop[1] + crop[3])).resize((pw, int(pw * crop[3] / crop[2])), Image.LANCZOS)
                        else:
                            pimg = pimg.resize(PHONE, Image.LANCZOS)
                    if pimg is not None:
                        px = SIDE_X + (SIDE_W - pimg.size[0]) // 2 + dx
                        py = CAR_Y + 3 + (PHONE[1] - pimg.size[1]) // 2
                        panel(canvas, pimg, (px, py), 40 if not c.get('phone') else 28, a, border=(52, 58, 70))
                        if not same_side or c.get('phone'):
                            over(canvas, labels['phone'], (px + (pimg.size[0] - labels['phone'].size[0]) // 2, py + pimg.size[1] + 14), a * (1.0 if c.get('phone') else ease((3.2 - t) / 0.4)))
                else:
                    vim = vcp.read()
                    vx = SIDE_X + (SIDE_W - vim.size[0]) // 2 + dx
                    vy = CAR_Y + (CAR[1] - vim.size[1]) // 2 - 20
                    panel(canvas, vim, (vx, vy), 22, a, border=(52, 58, 70))
                    over(canvas, labels['vc'], (vx + (vim.size[0] - labels['vc'].size[0]) // 2, vy + vim.size[1] + 16), a)
            for (st, sd, bim) in says:
                a = ease((ts - st) / 0.18) * ease((st + sd - ts) / 0.25)
                over(canvas, bim, (cx + 56, CAR_Y + 46 - (1 - a) * 12), a)
            if cap:
                a = 1.0
                if prev_caption != cap:
                    a *= ease((t - 0.1) / 0.3)
                if nxt.get('caption') != cap:
                    a *= ease((c['dur'] - t) / 0.25)
                cim2 = cap_cache[cap]
                over(canvas, cim2, ((W - cim2.size[0]) // 2, 916 + (140 - cim2.size[1]) // 2), a)
            if chip:
                over(canvas, chip, (CAR_X_DUO, 958))
            if notice:
                over(canvas, notice, (W - 44 - notice.size[0], 1030))
            if prev_canvas is not None and i < xf:
                canvas = Image.blend(prev_canvas, canvas, (i + 1) / (xf + 1))
            enc.stdin.write(canvas.tobytes())
        car.close()
        if vcp:
            vcp.close()
        prev_canvas, prev_layout, prev_caption, car_x, prev_phone = canvas, layout, cap, target_x, c.get('phone')

        # 소리: 그 구간의 차 소리와 질문 목소리
        if speed == 1 and not c.get('mute'):
            if sc['name'] not in scene_audio:
                scene_audio[sc['name']] = leveled(read_audio(sc['audioFile']))
            sa = scene_audio[sc['name']]
            seg = sa[int(c['t0'] * SR):int(c['t1'] * SR)].copy()
            put(audio, fade(seg), at, c.get('gain', 1.0))
            for o in sc.get('overlays', []):
                # 질문 목소리는 이 구간에 걸치는 부분만
                v = leveled(read_audio(o['file']))
                a0, a1 = max(c['t0'], o['at']), min(c['t1'], o['at'] + len(v) / SR)
                if a1 - a0 > 0.05:
                    part = v[int((a0 - o['at']) * SR):int((a1 - o['at']) * SR)].copy()
                    put(audio, fade(part), at + (a0 - c['t0']))
        at += n / FPS
        print(f'  {ci + 1:2d} {sc["name"]} {c["t0"]:.1f}~{c["t1"]:.1f} ({c["dur"]:.1f}s) {layout} {cap or ""}')

    enc.stdin.close()
    enc.wait()

    # 음악: 차가 말하는 장면에서는 작게, 카드에서는 크게. 바뀌는 곳은 0.6초에 걸쳐 부드럽게
    mus = E.get('music')
    if mus and os.path.exists(os.path.join(os.path.dirname(edit_path), mus['file'])):
        m = read_audio(os.path.join(os.path.dirname(edit_path), mus['file']))
        m = m[int(mus.get('start', 0) * SR):]
        n = min(len(m), int(total * SR))
        k = int(0.6 * SR)
        env = np.convolve(level[:n], np.ones(k, dtype=np.float32) / k, mode='same')
        env[:int(0.3 * SR)] *= np.linspace(0, 1, int(0.3 * SR))
        tail = int(2.2 * SR)
        env[n - tail:n] *= np.linspace(1, 0, tail)
        audio[:n] += m[:n] * env[:, None] * mus.get('gain', 1.0)
    audio = audio[:int(total * SR)]
    peak = float(np.abs(audio).max()) or 1.0
    if peak > 0.89:
        audio *= 0.89 / peak
    with wave.open(tmp_audio, 'wb') as wf:
        wf.setnchannels(2)
        wf.setsampwidth(2)
        wf.setframerate(SR)
        wf.writeframes((audio * 32767).astype(np.int16).tobytes())
    subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-i', out + '.video.mp4', '-i', tmp_audio, '-c:v', 'copy', '-c:a', 'aac', '-b:a', '192k', '-movflags', '+faststart', out], check=True)
    os.remove(out + '.video.mp4')
    os.remove(tmp_audio)
    print('완성', out, f'{total:.1f}초')


if __name__ == '__main__':
    main()
