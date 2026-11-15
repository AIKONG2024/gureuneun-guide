#!/usr/bin/env python3
"""장면별 렌더(HyperFrames) → 이어 붙이기 → 소리 합치기
  python3 tools/film/render.py apk/구르는가이드_시연영상.mp4
HyperFrames CLI 위치는 HYPERFRAMES 환경 변수로 바꿀 수 있음(기본: npx hyperframes)
"""
import json, os, shlex, subprocess, sys
from concurrent.futures import ThreadPoolExecutor

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, 'renders', 'film.mp4')
HF = shlex.split(os.environ.get('HYPERFRAMES', 'npx hyperframes'))
FF = os.environ.get('FFMPEG', 'ffmpeg')
JOBS = int(os.environ.get('JOBS', '2'))
plan = json.load(open(os.path.join(HERE, 'scenes.json')))
os.makedirs(os.path.join(HERE, 'renders'), exist_ok=True)


def render(s):
    src = os.path.join(HERE, 'compositions', s['id'] + '.html')
    out = os.path.join(HERE, 'renders', s['id'] + '.mp4')
    if os.path.exists(out) and os.path.getmtime(out) > os.path.getmtime(src):
        return out
    r = subprocess.run(HF + ['render', '-c', 'compositions/%s.html' % s['id'], '-o', out, '--video-frame-format', 'png', '-q', 'delivery', '-w', '3'],
                       cwd=HERE, capture_output=True, text=True)
    if r.returncode or not os.path.exists(out):
        print(r.stdout[-1500:], r.stderr[-1500:])
        raise SystemExit('render failed: ' + s['id'])
    print('장면', s['id'], '%.1f초' % s['duration'], flush=True)
    return out


with ThreadPoolExecutor(JOBS) as ex:
    parts = list(ex.map(render, plan['scenes']))
lst = os.path.join(HERE, 'renders', 'parts.txt')
open(lst, 'w').write(''.join("file '%s'\n" % p for p in parts))
video = os.path.join(HERE, 'renders', 'video.mp4')
subprocess.run([FF, '-y', '-v', 'error', '-f', 'concat', '-safe', '0', '-i', lst, '-c', 'copy', video], check=True)
os.makedirs(os.path.dirname(os.path.abspath(OUT)), exist_ok=True)
# 저장소에 올릴 수 있게 다시 압축 (장면 렌더는 고화질이라 2분에 190MB 안팎)
subprocess.run([FF, '-y', '-v', 'error', '-i', video, '-i', os.path.join(HERE, 'assets', 'audio.m4a'), '-map', '0:v', '-map', '1:a',
                '-c:v', 'libx264', '-preset', 'slow', '-crf', '21', '-maxrate', '4200k', '-bufsize', '8400k', '-pix_fmt', 'yuv420p',
                '-c:a', 'copy', '-shortest', '-movflags', '+faststart', OUT], check=True)
print('완성', OUT)

