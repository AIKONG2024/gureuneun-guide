#!/usr/bin/env python3
# 구르는 가이드 v2 오디오 합성 (인터넷 없이 numpy 로 직접 합성)
# 만드는 파일: companion/web/audio/{prelude,gyeongju_entry,reel_music}.{ogg,mp3}
#   prelude        2.8초  여행지가 가까워질 때 맑은 종소리 아르페지오
#   gyeongju_entry 10.6초 경주 요금소 통과 순간의 가야금 연주(두근거림 → 상승 → 선율, 끝 2초 페이드아웃)
#   reel_music     62.5초 여행 영상 배경음(가야금 선율 + 아주 약한 장구, 끝 3초 페이드아웃)
# 가야금 소리: 카플러스-스트롱 현 모델 + 몸통 공명 + 농현(시간에 따라 음높이를 바꾸는 재표본화)
# 음계: 평조 5음계 D G A C 계열, 음량: 통합 -16 LUFS, 트루 피크 -1.5 dBTP 이하
# 사용: python3 tools/assets/make_audio.py   (numpy, scipy, /opt/homebrew/bin/ffmpeg 필요)
import os, re, subprocess, tempfile
import numpy as np
from scipy.signal import lfilter, fftconvolve, butter, sosfilt, resample_poly
from scipy.ndimage import maximum_filter1d, minimum_filter1d
from scipy.io import wavfile

SR = 48000
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
OUT = os.path.join(ROOT, 'companion', 'web', 'audio')
FFMPEG = '/opt/homebrew/bin/ffmpeg'
TARGET_LUFS = -16.0
CEIL_DB = -1.5

def m2f(m): return 440.0 * 2 ** ((m - 69) / 12.0)
N = dict(D2=38, A2=45, D3=50, G3=55, A3=57, C4=60, D4=62, G4=67, A4=69, C5=72, D5=74, G5=79, A5=81, C6=84, D6=86)

# ---------- 기본 도구 ----------
def biquad_peak(f0, gain_db, q):
    A = 10 ** (gain_db / 40); w = 2 * np.pi * f0 / SR; al = np.sin(w) / (2 * q)
    b = [1 + al * A, -2 * np.cos(w), 1 - al * A]; a = [1 + al / A, -2 * np.cos(w), 1 - al / A]
    return np.array(b) / a[0], np.array(a) / a[0]

BODY = [biquad_peak(190, 5, 1.6), biquad_peak(430, 3.5, 2.0), biquad_peak(1150, 2.5, 2.5), biquad_peak(2600, -3, 1.0)]

def body(x):
    for b, a in BODY: x = lfilter(b, a, x)
    return x

def ks_string(f, length, t60, bright, rng, pos=0.18):
    """카플러스-스트롱. 정수 지연 길이로 만들고 실제 음높이를 함께 돌려줌"""
    n = max(4, int(SR / f - 0.5)); fa = SR / (n + 0.5)
    exc = rng.uniform(-1, 1, n)
    a = 1 - bright                                   # 들뜸 신호를 한 극 저역통과로 부드럽게
    exc = lfilter([1 - a], [1, -a], exc)
    p = max(1, int(n * pos)); exc = exc - np.roll(exc, p)   # 퉁기는 위치(콤 필터)
    exc -= exc.mean(); exc /= (np.abs(exc).max() + 1e-9)
    d = 10 ** (-3.0 / (fa * t60))
    nb = int(np.ceil(length / n)) + 2
    y = np.zeros(nb * n)
    y[:n] = exc
    for k in range(1, nb):
        s = k * n
        prev = y[s - n:s]
        prev1 = np.concatenate(([y[s - n - 1] if s - n - 1 >= 0 else 0.0], y[s - n:s - 1]))
        y[s:s + n] = d * 0.5 * (prev + prev1)
    return y[:length], fa

def pluck(midi, dur, vel=0.8, orn=None, bright=0.55, seed=0, t60=None):
    """가야금 한 음. orn: None | 'vib'(농현) | 'fall'(위에서 꺾어 내림) | 'bend'(밀어 올렸다 내림) | 'push'(살짝 밀어 올림)"""
    rng = np.random.default_rng(seed)
    f = m2f(midi)
    if t60 is None: t60 = 2.9 * (m2f(50) / f) ** 0.45
    L = int((dur + 0.1) * SR)
    t = np.arange(L) / SR
    cents = np.zeros(L)
    if orn == 'vib':
        depth = np.clip((t - 0.22) / 0.35, 0, 1) * 38          # 0.22초 뒤부터 천천히 깊어지는 농현
        cents = depth * np.sin(2 * np.pi * 4.6 * np.maximum(t - 0.22, 0))
    elif orn == 'fall':
        cents = 200 * (1 - np.clip((t - 0.12) / 0.16, 0, 1) ** 1.5)  # 한 음 위에서 시작해 꺾어 내림
    elif orn == 'bend':
        up = np.clip((t - 0.18) / 0.12, 0, 1); dn = np.clip((t - 0.55) / 0.15, 0, 1)
        cents = 200 * up * (1 - dn)
    elif orn == 'push':
        cents = 90 * np.clip((t - 0.2) / 0.25, 0, 1) * (1 - np.clip((t - 0.65) / 0.3, 0, 1))
    ratio = 2 ** (cents / 1200.0)
    raw_len = int(L * ratio.max() * 1.05) + 2000
    y, fa = ks_string(f, raw_len, t60, bright, rng)
    ph = np.cumsum(ratio * (f / fa)); ph -= ph[0]
    out = np.interp(ph, np.arange(len(y)), y)
    # 손톱 대신 손끝으로 뜯는 짧은 잡음
    click = rng.normal(0, 1, int(0.006 * SR)) * np.exp(-np.arange(int(0.006 * SR)) / (0.0015 * SR))
    b, a = butter(2, [900 / (SR / 2), 4200 / (SR / 2)], 'band'); click = lfilter(b, a, click)
    out[:len(click)] += 0.25 * click
    out = body(out)
    rel = int(0.08 * SR); env = np.ones(L); env[-rel:] = np.linspace(1, 0, rel) ** 2
    out = out * env
    out /= (np.abs(out).max() + 1e-9)
    return out * vel

def bell(midi, dur, vel=0.7, seed=0):
    rng = np.random.default_rng(seed)
    f = m2f(midi); L = int(dur * SR); t = np.arange(L) / SR
    parts = [(1.0, 1.0, 1.9), (2.0, 0.22, 1.1), (2.76, 0.34, 0.85), (5.40, 0.13, 0.45), (8.93, 0.05, 0.25)]
    y = np.zeros(L)
    for r, a, dcy in parts:
        if f * r > SR * 0.45: continue
        y += a * np.sin(2 * np.pi * f * r * t + rng.uniform(0, 6.28)) * np.exp(-t / dcy * 1.6)
    y *= np.minimum(1, t / 0.002)
    return y / (np.abs(y).max() + 1e-9) * vel

def drum(kind, vel=0.5, seed=0):
    """장구 비슷한 소리. kung(북편, 낮게) / deok(채편, 짧게) / deong(둘 다)"""
    rng = np.random.default_rng(seed)
    L = int(0.6 * SR); t = np.arange(L) / SR
    y = np.zeros(L)
    if kind in ('kung', 'deong', 'heart'):
        f0, f1 = (95, 58) if kind != 'heart' else (70, 46)
        fr = f1 + (f0 - f1) * np.exp(-t / 0.05)
        y += np.sin(2 * np.pi * np.cumsum(fr) / SR) * np.exp(-t / (0.22 if kind != 'heart' else 0.16))
        nz = rng.normal(0, 1, L) * np.exp(-t / 0.012)
        b, a = butter(2, 600 / (SR / 2)); y += 0.25 * lfilter(b, a, nz)
    if kind in ('deok', 'deong'):
        nz = rng.normal(0, 1, L) * np.exp(-t / 0.018)
        b, a = butter(2, [1200 / (SR / 2), 6000 / (SR / 2)], 'band')
        y += 0.55 * lfilter(b, a, nz) + 0.35 * np.sin(2 * np.pi * 420 * t) * np.exp(-t / 0.05)
    return y / (np.abs(y).max() + 1e-9) * vel

def drone(dur, notes, level, swell=2.5, seed=0):
    rng = np.random.default_rng(seed)
    L = int(dur * SR); t = np.arange(L) / SR
    y = np.zeros((L, 2))
    for i, m in enumerate(notes):
        f = m2f(m)
        for ch, det in enumerate((-0.6, 0.6)):
            lfo = 1 + 0.25 * np.sin(2 * np.pi * (0.13 + 0.05 * i) * t + rng.uniform(0, 6))
            y[:, ch] += np.sin(2 * np.pi * (f + det) * t + rng.uniform(0, 6)) * lfo / len(notes)
            y[:, ch] += 0.18 * np.sin(2 * np.pi * 2 * (f + det) * t) * lfo / len(notes)
    env = np.minimum(1, t / swell) ** 2
    return y * env[:, None] * level

def reverb_ir(sec=2.2, seed=7):
    rng = np.random.default_rng(seed); L = int(sec * SR); t = np.arange(L) / SR
    ir = np.zeros((L, 2))
    for ch in range(2):
        nz = rng.normal(0, 1, L)
        lo = sosfilt(butter(2, 2500 / (SR / 2), output='sos'), nz)
        ir[:, ch] = (lo * np.exp(-t / 0.38) + 0.35 * nz * np.exp(-t / 0.12))
    pre = int(0.018 * SR); ir = np.vstack([np.zeros((pre, 2)), ir])
    return ir / np.sqrt((ir ** 2).sum(axis=0))

class Mix:
    def __init__(self, dur): self.buf = np.zeros((int(dur * SR) + SR, 2)); self.dur = dur
    def add(self, t, sig, pan=0.0, gain=1.0):
        i = int(t * SR); s = sig[: max(0, len(self.buf) - i)]
        l = np.cos((pan + 1) * np.pi / 4); r = np.sin((pan + 1) * np.pi / 4)
        self.buf[i:i + len(s), 0] += s * l * gain; self.buf[i:i + len(s), 1] += s * r * gain
    def addst(self, t, sig2, gain=1.0):
        i = int(t * SR); s = sig2[: max(0, len(self.buf) - i)]
        self.buf[i:i + len(s)] += s * gain
    def render(self, wet=0.22, fade_in=0.0, fade_out=0.0):
        x = self.buf
        ir = reverb_ir()
        w = np.stack([fftconvolve(x[:, c], ir[:, c])[:len(x)] for c in range(2)], axis=1)
        y = x + wet * w
        y = y[: int(self.dur * SR)]
        L = len(y); t = np.arange(L) / SR
        env = np.ones(L)
        if fade_in > 0: env *= np.clip(t / fade_in, 0, 1) ** 1.5
        if fade_out > 0:
            k = np.clip((self.dur - t) / fade_out, 0, 1)
            env *= np.sin(k * np.pi / 2) ** 2
        y *= env[:, None]
        hp = butter(2, 35 / (SR / 2), 'high', output='sos')
        return sosfilt(hp, y, axis=0)

# ---------- 음량 맞춤 ----------
def measure(y):
    with tempfile.NamedTemporaryFile(suffix='.wav', delete=False) as f: p = f.name
    wavfile.write(p, SR, y.astype(np.float32))
    r = subprocess.run([FFMPEG, '-hide_banner', '-nostats', '-i', p, '-af', 'ebur128=peak=true', '-f', 'null', '-'], capture_output=True, text=True).stderr
    os.unlink(p)
    tail = r[r.rfind('Summary:'):]
    lufs = float(re.search(r'I:\s+(-?[\d.]+) LUFS', tail).group(1))
    tp = float(re.search(r'Peak:\s+(-?[\d.inf]+) dBFS', tail).group(1))
    return lufs, tp

def true_peak_env(y):
    """4배 오버샘플링으로 샘플 사이 피크까지 본 샘플별 피크"""
    up = resample_poly(y, 4, 1, axis=0)
    a = np.abs(up).max(axis=1)
    n = len(y); a = a[: n * 4].reshape(n, 4).max(axis=1)
    return a

def limiter(y, ceil_db):
    th = 10 ** (ceil_db / 20)
    pk = maximum_filter1d(true_peak_env(y), size=int(0.004 * SR))
    g = np.minimum(1.0, th / np.maximum(pk, 1e-9))
    g = minimum_filter1d(g, size=int(0.006 * SR))
    a = np.exp(-1 / (0.04 * SR))
    g = lfilter([1 - a], [1, -a], g[::-1])[::-1]          # 앞쪽으로 미리 줄어들게
    g = np.minimum(g, minimum_filter1d(np.minimum(1.0, th / np.maximum(pk, 1e-9)), size=int(0.004 * SR)))
    return y * g[:, None]

def loudness(y):
    y = y / (np.abs(y).max() + 1e-9) * 0.5
    for _ in range(6):
        lufs, tp = measure(y)
        if abs(lufs - TARGET_LUFS) < 0.2 and tp <= CEIL_DB: break
        y = y * 10 ** ((TARGET_LUFS - lufs) / 20)
        y = limiter(y, CEIL_DB - 0.5)
    return y, measure(y)

def save(name, y):
    y, (lufs, tp) = loudness(y)
    os.makedirs(OUT, exist_ok=True)
    wav = os.path.join(tempfile.gettempdir(), name + '.wav')
    wavfile.write(wav, SR, y.astype(np.float32))
    ogg = os.path.join(OUT, name + '.ogg'); mp3 = os.path.join(OUT, name + '.mp3')
    subprocess.run([FFMPEG, '-y', '-hide_banner', '-loglevel', 'error', '-i', wav, '-c:a', 'vorbis', '-strict', '-2', '-ac', '2', '-q:a', '8', ogg], check=True)
    subprocess.run([FFMPEG, '-y', '-hide_banner', '-loglevel', 'error', '-i', wav, '-c:a', 'libmp3lame', '-b:a', '192k', '-ar', '48000', mp3], check=True)
    print(f'{name}: {len(y)/SR:.2f}s  {lufs:.1f} LUFS  peak {tp:.1f} dBTP')
    return wav

# ---------- 곡 ----------
def make_prelude():
    m = Mix(2.8)
    seq = ['D5', 'G5', 'A5', 'C6', 'D6']
    for i, n in enumerate(seq):
        m.add(0.05 + i * 0.16, bell(N[n], 2.6, 0.55 + 0.06 * i, seed=i), pan=-0.5 + 0.25 * i)
    for j, n in enumerate(['G5', 'D6']):
        m.add(0.95 + j * 0.03, bell(N[n], 1.8, 0.35, seed=10 + j), pan=0.3 - 0.6 * j)
    m.addst(0.0, drone(2.8, [N['D3'], N['A3']], 0.04, swell=0.6))
    return m.render(wet=0.38, fade_out=1.0)

def make_entry():
    D = 10.6; m = Mix(D)
    m.addst(0.0, drone(D, [N['D2'], N['A2'], N['D3']], 0.07, swell=3.0, seed=3))
    beats = [(0.05, 0.55), (0.27, 0.38), (0.85, 0.62), (1.07, 0.44), (1.65, 0.70), (1.87, 0.50), (2.45, 0.78), (2.67, 0.58)]
    for i, (t, v) in enumerate(beats): m.add(t, drum('heart', v, seed=i), pan=0.0, gain=0.9)
    for i, (t, n) in enumerate([(0.05, 'D3'), (0.85, 'D3'), (1.65, 'G3'), (2.45, 'A3')]):
        m.add(t, pluck(N[n], 1.6, 0.42 + 0.06 * i, bright=0.45, seed=20 + i), pan=-0.35)
    run = ['D3', 'G3', 'A3', 'C4', 'D4', 'G4', 'A4', 'C5']
    for i, n in enumerate(run):
        m.add(3.10 + i * 0.075, pluck(N[n], 0.9, 0.38 + 0.04 * i, bright=0.6, seed=40 + i), pan=-0.4 + 0.1 * i)
    mel = [(3.75, 'D5', 1.0, 'vib', 0.95), (4.75, 'C5', 0.25, None, 0.6), (5.00, 'A4', 0.5, 'fall', 0.8), (5.50, 'G4', 0.5, None, 0.7),
           (6.00, 'A4', 0.5, 'vib', 0.75), (6.50, 'C5', 0.25, None, 0.6), (6.75, 'D5', 0.25, None, 0.7), (7.00, 'G5', 1.0, 'vib', 0.95),
           (8.00, 'D5', 0.5, 'bend', 0.75)]
    for i, (t, n, d, o, v) in enumerate(mel):
        m.add(t, pluck(N[n], d + 1.0, v, orn=o, bright=0.62, seed=60 + i), pan=0.25)
    for i, (t, n, v) in enumerate([(3.75, 'D3', 0.6), (4.75, 'A3', 0.45), (5.75, 'G3', 0.45), (6.75, 'A3', 0.45), (7.00, 'D3', 0.6)]):
        m.add(t, pluck(N[n], 1.4, v, bright=0.45, seed=80 + i), pan=-0.35)
    for i, n in enumerate(['D3', 'A3', 'D4', 'A4']):
        m.add(8.50 + i * 0.035, pluck(N[n], 2.2, 0.55, bright=0.5, seed=90 + i), pan=-0.4 + 0.25 * i)
    m.add(8.62, pluck(N['D5'], 2.0, 0.85, orn='vib', bright=0.6, seed=99), pan=0.25)
    for i, (t, k, v) in enumerate([(3.75, 'deong', 0.45), (4.75, 'kung', 0.3), (5.25, 'deok', 0.22), (5.75, 'kung', 0.3), (6.25, 'deok', 0.22), (7.00, 'deong', 0.45), (8.00, 'kung', 0.28), (8.50, 'deong', 0.4)]):
        m.add(t, drum(k, v, seed=100 + i), pan=0.1, gain=0.7)
    return m.render(wet=0.26, fade_out=2.0)

def make_reel():
    D = 62.5; B = 0.9; BAR = 4 * B; m = Mix(D)
    rng = np.random.default_rng(11)
    m.addst(0.0, drone(D, [N['D2'], N['A2']], 0.025, swell=4.0, seed=5))
    M = {
     1: [('D5', 1, 'vib'), ('C5', .5, None), ('A4', .5, None), ('G4', 1, 'fall'), ('A4', 1, None)],
     2: [('A4', .5, None), ('C5', .5, None), ('D5', 1, None), ('G5', 1.5, 'vib'), ('D5', .5, None)],
     3: [('C5', 1, None), ('A4', .5, None), ('G4', .5, None), ('D4', 1.5, 'vib'), ('G4', .5, None)],
     4: [('A4', 1, None), ('G4', .5, None), ('A4', .5, None), ('D4', 2, 'vib')],
     5: [('G5', 1, 'vib'), ('A5', .5, None), ('G5', .5, None), ('D5', 1, 'fall'), ('C5', 1, None)],
     6: [('D5', .5, None), ('C5', .5, None), ('A4', 1, None), ('C5', 1, 'push'), ('A4', 1, None)],
     7: [('D5', .5, None), ('G5', .5, None), ('D5', 1, 'vib'), ('C5', .5, None), ('A4', .5, None), ('G4', 1, None)],
    }
    roots = {1: ('D3', 'A3'), 2: ('A3', 'D4'), 3: ('G3', 'D4'), 4: ('D3', 'A3'), 5: ('G3', 'D4'), 6: ('A3', 'C4'), 7: ('D3', 'G3')}
    form = [1, 2, 3, 4, 5, 6, 2, 4, 1, 7, 3, 4, 5, 3, 4]       # 1~15마디
    for i, (t, n) in enumerate([(0.0, 'D3'), (1.8, 'A3'), (2.7, 'D4')]):
        m.add(0.4 + t, pluck(N[n], 2.5, 0.45, bright=0.42, seed=200 + i), pan=-0.3)
    seed = 300
    for bi, mo in enumerate(form):
        t0 = (bi + 1) * BAR
        bt = 0.0
        for n, d, o in M[mo]:
            seed += 1
            jit = rng.normal(0, 0.008)
            vel = 0.62 + 0.12 * (o == 'vib') + rng.normal(0, 0.04)
            if o is None and d <= 0.5 and rng.random() < 0.18 and N[n] < N['A5']:   # 가끔 위 이웃음에서 내려오는 꾸밈음
                up = [k for k in ['G4', 'A4', 'C5', 'D5', 'G5', 'A5'] if N[k] > N[n]][0]
                m.add(t0 + bt * B - 0.06, pluck(N[up], 0.3, vel * 0.45, bright=0.55, seed=seed + 5000), pan=0.25)
            m.add(t0 + bt * B + jit, pluck(N[n], d * B + 0.9, vel, orn=o, bright=0.5, seed=seed), pan=0.22)
            bt += d
        r1, r2 = roots[mo]
        m.add(t0 + 0.01, pluck(N[r1], 2.2, 0.48, bright=0.4, seed=seed + 1000), pan=-0.35)
        m.add(t0 + 2 * B, pluck(N[r2], 1.8, 0.36, bright=0.4, seed=seed + 2000), pan=-0.3)
        for k, (bb, kind, v) in enumerate([(0, 'deong', 0.22), (2, 'kung', 0.16), (3, 'deok', 0.11), (3.5, 'deok', 0.08)]):
            m.add(t0 + bb * B, drum(kind, v, seed=seed + 3000 + k), pan=0.05, gain=0.55)
    tf = 16 * BAR
    for i, n in enumerate(['D3', 'A3', 'D4', 'G4', 'A4']):
        m.add(tf + i * 0.04, pluck(N[n], 4.0, 0.5, bright=0.45, seed=900 + i), pan=-0.4 + 0.2 * i)
    m.add(tf + 0.25, pluck(N['D5'], 4.0, 0.7, orn='vib', bright=0.5, seed=999), pan=0.22)
    m.add(tf, drum('deong', 0.18, seed=998), gain=0.55)
    return m.render(wet=0.3, fade_in=0.5, fade_out=3.0)

if __name__ == '__main__':
    save('prelude', make_prelude())
    save('gyeongju_entry', make_entry())
    save('reel_music', make_reel())

