#!/usr/bin/env python3
"""시연 필름 재료: 녹화(/tmp/rec)를 HyperFrames 에서 쓰기 좋은 30fps MP4 로 바꿈
  python3 tools/film/media.py /tmp/rec tools/film/assets
  car.mp4   차량 화면 캡처(초당 4장 안팎)를 차량 시각 그대로 1920x1080 30fps 로 이음 (사이는 프레임 섞기)
  phone.mp4 휴대폰 녹화. 0초 = phone_start.txt
  reel.mp4  여행 영상 녹화
  times.json 차량 녹화 기준 시각(T0)과 휴대폰 시작 차이
"""
import json, os, shutil, subprocess, sys

REC = sys.argv[1] if len(sys.argv) > 1 else '/tmp/rec'
OUT = sys.argv[2] if len(sys.argv) > 2 else os.path.join(os.path.dirname(os.path.abspath(__file__)), 'assets')
FF = shutil.which('ffmpeg') or '/opt/homebrew/bin/ffmpeg'
os.makedirs(OUT, exist_ok=True)

T0 = int(open(os.path.join(REC, 'car_start.txt')).read().strip())
PH0 = int(open(os.path.join(REC, 'phone_start.txt')).read().strip())
shots = []
for line in open(os.path.join(REC, 'car', 'shots.tsv'), encoding='utf-8'):
    a, b, f = line.rstrip('\n').split('\t')
    p = os.path.join(REC, 'car', f)
    if os.path.exists(p) and os.path.getsize(p) > 120000:      # 검은 화면은 뺌
        shots.append(((int(a) - T0) / 1000.0 + 0.05, p))
shots.sort()
lst = os.path.join(OUT, 'car_concat.txt')
with open(lst, 'w') as fh:
    prev_t, prev_p = 0.0, shots[0][1]
    for t, p in shots:
        fh.write("file '%s'\nduration %.3f\n" % (prev_p, max(0.02, t - prev_t)))
        prev_t, prev_p = t, p
    fh.write("file '%s'\nduration 2.0\nfile '%s'\n" % (prev_p, prev_p))


def run(cmd):
    print(' '.join(cmd[:6]), '...', flush=True)
    subprocess.run(cmd, check=True)


run([FF, '-y', '-v', 'error', '-f', 'concat', '-safe', '0', '-i', lst, '-vf', 'scale=1920:1080:flags=lanczos,framerate=fps=30',
     '-c:v', 'libx264', '-preset', 'fast', '-crf', '15', '-pix_fmt', 'yuv420p', os.path.join(OUT, 'car.mp4')])
run([FF, '-y', '-v', 'error', '-i', os.path.join(REC, 'phone.webm'), '-vf', 'fps=30', '-c:v', 'libx264', '-preset', 'fast', '-crf', '16',
     '-pix_fmt', 'yuv420p', os.path.join(OUT, 'phone.mp4')])
run([FF, '-y', '-v', 'error', '-i', os.path.join(REC, 'reel.webm'), '-vf', 'fps=30', '-c:v', 'libx264', '-preset', 'fast', '-crf', '14',
     '-pix_fmt', 'yuv420p', os.path.join(OUT, 'reel.mp4')])
json.dump({'T0': T0, 'phone_offset': (PH0 - T0) / 1000.0, 'car_end': shots[-1][0]}, open(os.path.join(OUT, 'times.json'), 'w'))
print('done', OUT)

