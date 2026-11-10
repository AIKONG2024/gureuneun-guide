#!/usr/bin/env python3
"""구르는 가이드 시연 영상 합치기

record.mjs 가 남긴 timeline.json 과 장면별 녹화 파일(차 화면, 차 소리, 휴대폰 화면, Virtual Console)을 읽어
1920x1080 영상 한 편으로 만든다. 자막은 Pillow 로 그린 PNG 를 겹친다(drawtext 가 없는 ffmpeg 에서도 동작).

사용: python3 compose.py <녹화 폴더> <결과.mp4> [cuts.json]
  cuts.json: 장면별로 남길 구간(초). 예 {"gyeongju1": [[0, 31], [40, 75]]}. 적지 않은 장면은 전체를 쓴다
  FORCE=1 을 주면 장면 영상을 처음부터 다시 만든다
"""
import json
import os
import re
import subprocess
import sys
import tempfile

from PIL import Image, ImageDraw, ImageFont

FONT = '/System/Library/Fonts/AppleSDGothicNeo.ttc'
REG, MED, SEMI, BOLD = 0, 2, 4, 6
W, H, FPS = 1920, 1080, 30
EMU = (48, 96, 1280, 720)       # 차 화면 (x, y, 너비, 높이)
PHONE = (1441, 96, 431, 920)    # 휴대폰
VCP = (48, 848, 256, 224)       # Virtual Console 에서 차 평면도(실내 조명 색, 창문) 부분
VC_CROP = (696, 282, 256, 224)  # 960x540 Virtual Console 녹화에서 잘라 낼 자리
CAP = (324, 848, 1004, 209)     # 자막 상자
# 앱과 같은 색: 바탕 #0E1015, 나눔선, 보조 글자 #9AA0AA, 강조는 현대자동차 CI 의 액티브 블루 #00AAD2
BG = (14, 16, 21)
LINE = (44, 49, 59)
SUB = (154, 160, 170)
ACCENT = (0, 170, 210)
CARD = (23, 26, 32, 255)
BRAND = (0, 44, 95, 255)
REPO = 'github.com/AIKONG2024/gureuneun-guide'

ORDER = ['gyeongju1', 'gyeongju2', 'jeju', 'record', 'seoul', 'care']
TITLES = {
    'gyeongju1': '장면 1. 경주, 가족 여행',
    'gyeongju2': '장면 1. 경주, 가족 여행',
    'jeju': '장면 2. 제주, 친구들의 렌터카 두 대',
    'record': '장면 2. 여행이 끝나면 남는 기록',
    'seoul': '장면 3. 서울, 외국인 손님을 태운 택시',
    'care': '장면 4. 먼 길에서 차가 먼저 챙기는 것',
}


def font(size, idx=REG):
    return ImageFont.truetype(FONT, size, index=idx)


def wrap(draw, text, fnt, width):
    """단어 단위로 줄을 나누고, 한 단어가 너무 길면 글자 단위로 자른다"""
    lines, cur = [], ''
    for word in text.split(' '):
        trial = (cur + ' ' + word).strip()
        if draw.textlength(trial, font=fnt) <= width:
            cur = trial
            continue
        if cur:
            lines.append(cur)
        cur = word
        while draw.textlength(cur, font=fnt) > width:
            i = len(cur)
            while i > 1 and draw.textlength(cur[:i], font=fnt) > width:
                i -= 1
            lines.append(cur[:i])
            cur = cur[i:]
    if cur:
        lines.append(cur)
    return lines


def base_image(title, path):
    im = Image.new('RGB', (W, H), BG)
    d = ImageDraw.Draw(im)
    tf = font(32, BOLD)
    d.text((48, 24), '구르는 가이드', font=tf, fill=(255, 255, 255))
    d.text((48 + d.textlength('구르는 가이드', font=tf) + 16, 33), '시연 영상', font=font(22, MED), fill=SUB)
    sf = font(30, SEMI)
    d.text((W - 48 - d.textlength(title, font=sf), 26), title, font=sf, fill=ACCENT)
    d.text((EMU[0], 64), '차 화면 (Pleos Connect 에뮬레이터)', font=font(21, MED), fill=SUB)
    d.text((PHONE[0], 64), '동승자 휴대폰 (그룹 페이지)', font=font(21, MED), fill=SUB)
    d.text((VCP[0], 822), '차량 상태 (Virtual Console)', font=font(18, MED), fill=SUB)
    for x, y, w, h in (EMU, PHONE, VCP):
        d.rounded_rectangle((x - 3, y - 3, x + w + 2, y + h + 2), radius=8, outline=LINE, width=3)
    im.save(path)


def caption_image(title, body, path):
    im = Image.new('RGBA', (CAP[2], CAP[3]), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle((0, 0, CAP[2] - 1, CAP[3] - 1), radius=14, fill=CARD, outline=LINE, width=2)
    d.rounded_rectangle((0, 0, 9, CAP[3] - 1), radius=4, fill=ACCENT)
    tag = None
    m = re.search(r'\s*\(([A-Za-z][^()]*)\)\s*$', body)
    if m:
        tag, body = m.group(1), body[:m.start()]
    x0, maxw = 34, CAP[2] - 34 - 28
    title_w = maxw
    if tag:
        cf = font(19, SEMI)
        cw = d.textlength(tag, font=cf) + 28
        d.rounded_rectangle((CAP[2] - 24 - cw, 22, CAP[2] - 24, 54), radius=16, fill=BRAND, outline=ACCENT, width=1)
        d.text((CAP[2] - 24 - cw + 14, 25), tag, font=cf, fill=ACCENT)
        title_w = maxw - cw - 16
    tf, bf = font(36, BOLD), font(26, REG)
    tl = wrap(d, title, tf, title_w)[:2]
    y = 20
    for line in tl:
        d.text((x0, y), line, font=tf, fill=(255, 255, 255))
        y += 48
    y += 8
    for line in wrap(d, body, bf, maxw)[:3 if len(tl) == 1 else 2]:
        d.text((x0, y), line, font=bf, fill=(199, 201, 206))
        y += 37
    im.save(path)


def card_image(path, lines):
    """가운데 정렬 문구 카드. lines: [(글, 크기, 굵기, 색)]"""
    im = Image.new('RGB', (W, H), BG)
    d = ImageDraw.Draw(im)
    total = sum(int(s * 1.6) for _, s, _, _ in lines)
    y = (H - total) // 2
    for txt, size, idx, color in lines:
        f = font(size, idx)
        d.text(((W - d.textlength(txt, font=f)) / 2, y), txt, font=f, fill=color)
        y += int(size * 1.6)
    im.save(path)


def run(args):
    subprocess.run(args, check=True)


def duration(path):
    out = subprocess.run(['ffprobe', '-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', path],
                         capture_output=True, text=True, check=True).stdout
    return float(out.strip())


ENC = ['-r', str(FPS), '-c:v', 'libx264', '-preset', 'medium', '-crf', '18', '-pix_fmt', 'yuv420p',
       '-c:a', 'aac', '-b:a', '192k', '-ar', '48000']


def encode_card(img, secs, out):
    run(['ffmpeg', '-y', '-loglevel', 'error', '-loop', '1', '-framerate', str(FPS), '-i', img,
         '-f', 'lavfi', '-i', 'anullsrc=r=48000:cl=stereo',
         '-vf', 'fade=t=in:st=0:d=0.5,fade=t=out:st=%.2f:d=0.5' % (secs - 0.5),
         '-t', '%.2f' % secs] + ENC + [out])


def encode_scene(sc, tmp, out):
    name = sc['name']
    dur = (sc['end'] - sc['start']) / 1000.0
    base = os.path.join(tmp, 'base_%s.png' % name)
    base_image(TITLES.get(name, name), base)
    args = ['ffmpeg', '-y', '-loglevel', 'error', '-loop', '1', '-framerate', str(FPS), '-i', base]
    fc = []
    state = {'n': 1, 'last': '0:v'}

    def add_video(path, start_ms, box, label, crop=None):
        if not path or not os.path.exists(path):
            print('  없음:', label)
            return
        off = max(0.0, (sc['start'] - start_ms) / 1000.0)
        args.extend(['-ss', '%.3f' % off, '-i', path])
        x, y, w, h = box
        pre = 'crop=%d:%d:%d:%d,' % (crop[2], crop[3], crop[0], crop[1]) if crop else ''
        fc.append('[%d:v]setpts=PTS-STARTPTS,fps=%d,%sscale=%d:%d[%s]' % (state['n'], FPS, pre, w, h, label))
        fc.append('[%s][%s]overlay=%d:%d:eof_action=repeat[%so]' % (state['last'], label, x, y, label))
        state['last'] = label + 'o'
        state['n'] += 1

    add_video(sc.get('emuFile'), sc['emuStart'], EMU, 'emu')
    add_video(sc.get('phoneFile'), sc.get('phoneStart', 0), PHONE, 'ph')
    add_video(sc.get('vcFile'), sc.get('vcStart', 0), VCP, 'vc', VC_CROP)

    caps = sc.get('captions', [])
    for i, c in enumerate(caps):
        p = os.path.join(tmp, 'cap_%s_%d.png' % (name, i))
        caption_image(c['title'], c.get('body', ''), p)
        a = c['at'] / 1000.0
        b = caps[i + 1]['at'] / 1000.0 if i + 1 < len(caps) else dur + 1
        args.extend(['-loop', '1', '-framerate', str(FPS), '-i', p])
        fc.append("[%s][%d:v]overlay=%d:%d:enable='between(t,%.2f,%.2f)'[c%d]" % (state['last'], state['n'], CAP[0], CAP[1], a, b, i))
        state['last'] = 'c%d' % i
        state['n'] += 1

    audio = sc.get('audioFile')
    # 말로 묻기 장면의 질문 목소리 (에뮬레이터에는 마이크가 없어 음성 인식 SDK 에 넣은 녹음을 영상 소리에도 겹침)
    overlays = []
    for o in sc.get('overlays', []):
        if os.path.exists(o['file']):
            overlays.append(o)
        else:
            print('  질문 목소리 파일 없음:', o['file'])
    if audio and os.path.exists(audio):
        args.extend(['-i', audio])
        base_idx = state['n']
        state['n'] += 1
        if overlays:
            fc.append('[%d:a]aresample=48000,aformat=channel_layouts=stereo[abase]' % base_idx)
            labels = ['[abase]']
            for k, o in enumerate(overlays):
                args.extend(['-i', o['file']])
                ms = max(0, int(o['at']))
                fc.append('[%d:a]aresample=48000,aformat=channel_layouts=stereo,volume=%.2f,adelay=%d|%d[aov%d]' % (state['n'], o.get('gain', 1.0), ms, ms, k))
                labels.append('[aov%d]' % k)
                state['n'] += 1
            fc.append('%samix=inputs=%d:normalize=0:duration=first[amix]' % (''.join(labels), len(labels)))
            fc.append('[amix]loudnorm=I=-16:TP=-1.5:LRA=11,aresample=48000[aout]')
        else:
            fc.append('[%d:a]loudnorm=I=-16:TP=-1.5:LRA=11,aresample=48000[aout]' % base_idx)
    else:
        args.extend(['-f', 'lavfi', '-i', 'anullsrc=r=48000:cl=stereo'])
        fc.append('[%d:a]anull[aout]' % state['n'])
    args.extend(['-filter_complex', ';'.join(fc), '-map', '[%s]' % state['last'], '-map', '[aout]',
                 '-t', '%.3f' % dur] + ENC + [out])
    run(args)


def concat(parts, out):
    """parts: [(파일, 시작초, 끝초 또는 None)]. 구간마다 짧게 어두워졌다 밝아지며 이어 붙인다"""
    args = ['ffmpeg', '-y', '-loglevel', 'error']
    fc, labels = [], []
    for i, (path, a, b) in enumerate(parts):
        args.extend(['-i', path])
        end = duration(path) if b is None else min(b, duration(path))
        ln = end - a
        fc.append('[%d:v]trim=start=%.3f:end=%.3f,setpts=PTS-STARTPTS,fade=t=in:st=0:d=0.2,fade=t=out:st=%.3f:d=0.2[v%d]'
                  % (i, a, end, max(0, ln - 0.2), i))
        fc.append('[%d:a]atrim=start=%.3f:end=%.3f,asetpts=PTS-STARTPTS,afade=t=in:st=0:d=0.15,afade=t=out:st=%.3f:d=0.2[a%d]'
                  % (i, a, end, max(0, ln - 0.2), i))
        labels.append('[v%d][a%d]' % (i, i))
    fc.append('%sconcat=n=%d:v=1:a=1[v][a]' % (''.join(labels), len(parts)))
    run(args + ['-filter_complex', ';'.join(fc), '-map', '[v]', '-map', '[a]',
                '-r', str(FPS), '-c:v', 'libx264', '-preset', 'medium', '-crf', '20', '-pix_fmt', 'yuv420p',
                '-c:a', 'aac', '-b:a', '160k', '-ar', '48000', '-movflags', '+faststart', out])


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    src, out = sys.argv[1], sys.argv[2]
    cuts = {}
    if len(sys.argv) > 3:
        with open(sys.argv[3], encoding='utf-8') as f:
            cuts = json.load(f)
    with open(os.path.join(src, 'timeline.json'), encoding='utf-8') as f:
        timeline = json.load(f)
    scenes = {s['name']: s for s in timeline['scenes']}
    tmp = tempfile.mkdtemp(prefix='gg-compose-')
    work = os.path.join(src, 'compose')
    os.makedirs(work, exist_ok=True)
    force = os.environ.get('FORCE') == '1'

    intro_png, outro_png = os.path.join(tmp, 'intro.png'), os.path.join(tmp, 'outro.png')
    card_image(intro_png, [
        ('구르는 가이드', 84, BOLD, (255, 255, 255)),
        ('버튼 하나면 차가 통째로 여행 어드벤처가 됩니다', 42, SEMI, ACCENT),
        ('', 20, REG, SUB),
        ('Pleos Connect 에뮬레이터에서 실제로 돌아가는 화면이에요', 28, REG, SUB),
        ('들리는 목소리도 에뮬레이터 속 차가 낸 안내 음성 그대로예요', 28, REG, SUB),
    ])
    card_image(outro_png, [
        ('구르는 가이드', 72, BOLD, (255, 255, 255)),
        ('소스 코드와 APK   ' + REPO, 34, MED, ACCENT),
        ('', 16, REG, SUB),
        ('사용한 Pleos SDK   Vehicle, NaviHelper, FusedLocation, TextToSpeech, SpeechToText', 26, REG, SUB),
        ('현대자동차그룹 차량용 앱 개발 공모전 출품작', 26, REG, SUB),
    ])
    intro, outro = os.path.join(work, 'intro.mp4'), os.path.join(work, 'outro.mp4')
    encode_card(intro_png, 6, intro)
    encode_card(outro_png, 6, outro)

    parts = [(intro, 0, None)]
    for name in ORDER:
        sc = scenes.get(name)
        if not sc:
            continue
        mp4 = os.path.join(work, name + '.mp4')
        # 그 장면의 녹화 파일이 장면 영상보다 새로우면 다시 만듦 (다시 녹화한 장면만)
        inputs = [sc.get(k) for k in ('emuFile', 'phoneFile', 'vcFile', 'audioFile')] + [o.get('file') for o in sc.get('overlays', [])]
        newest = max([os.path.getmtime(p) for p in inputs if p and os.path.exists(p)] + [0])
        if force or not os.path.exists(mp4) or os.path.getmtime(mp4) < newest:
            print('장면 합치기', name, '%.0f초' % ((sc['end'] - sc['start']) / 1000.0))
            encode_scene(sc, tmp, mp4)
        for a, b in cuts.get(name, [[0, None]]):
            parts.append((mp4, a, b))
    parts.append((outro, 0, None))
    print('이어 붙이기', len(parts), '구간')
    concat(parts, out)
    print('완성', out, '%.0f초' % duration(out))


if __name__ == '__main__':
    main()

