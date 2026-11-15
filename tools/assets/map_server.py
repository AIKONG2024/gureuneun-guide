#!/usr/bin/env python3
# 지도 렌더용 로컬 수신 서버 (127.0.0.1:8792)
# - GET  /...           tools/assets/maprender/ 정적 파일(렌더 페이지, 로컬 maplibre)
# - GET  /repo/...      저장소 파일 읽기 전용(경로 데이터 등)
# - POST /save?path=..  본문(이미지·JSON)을 저장. 저장소 안의 허용 경로 또는 /tmp/rg-v2/ 아래만
# - POST /log           렌더 페이지 로그를 출력하고 /tmp/rg-v2/maplog.txt 에 덧붙임
# 인터넷 요청은 하지 않음. 지도 타일·글꼴은 Aside 브라우저가 직접 받음
import http.server, os, sys, urllib.parse, json, time

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
STATIC = os.path.join(ROOT, 'tools', 'assets', 'maprender')
TMP = '/tmp/rg-v2'
ALLOW = ['app/src/main/assets/tiles/', 'companion/web/map/', 'companion/web/data/approach.json']
os.makedirs(TMP, exist_ok=True)

class H(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *a, **k): super().__init__(*a, directory=STATIC, **k)
    def end_headers(self):
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Cache-Control', 'no-store')
        super().end_headers()
    def translate_path(self, path):
        p = urllib.parse.urlparse(path).path
        if p.startswith('/repo/'):
            full = os.path.abspath(os.path.join(ROOT, urllib.parse.unquote(p[6:])))
            return full if full.startswith(ROOT) else STATIC
        return super().translate_path(path)
    def do_OPTIONS(self):
        self.send_response(204); self.send_header('Access-Control-Allow-Methods', 'GET,POST'); self.send_header('Access-Control-Allow-Headers', '*'); self.end_headers()
    def do_POST(self):
        u = urllib.parse.urlparse(self.path); q = urllib.parse.parse_qs(u.query)
        body = self.rfile.read(int(self.headers.get('Content-Length', '0')))
        if u.path == '/log':
            line = time.strftime('%H:%M:%S ') + body.decode('utf-8', 'replace')
            print(line, flush=True)
            with open(os.path.join(TMP, 'maplog.txt'), 'a') as f: f.write(line + '\n')
            return self._ok({'ok': True})
        if u.path == '/save':
            rel = q.get('path', [''])[0]
            if rel.startswith('tmp/'):
                full = os.path.abspath(os.path.join(TMP, rel[4:]))
                okp = full.startswith(TMP)
            else:
                full = os.path.abspath(os.path.join(ROOT, rel))
                okp = full.startswith(ROOT) and any(rel.startswith(a) for a in ALLOW)
            if not okp: return self._ok({'ok': False, 'err': 'path not allowed'}, 403)
            os.makedirs(os.path.dirname(full), exist_ok=True)
            with open(full, 'wb') as f: f.write(body)
            return self._ok({'ok': True, 'bytes': len(body)})
        self._ok({'ok': False}, 404)
    def _ok(self, obj, code=200):
        b = json.dumps(obj).encode(); self.send_response(code)
        self.send_header('Content-Type', 'application/json'); self.send_header('Content-Length', str(len(b))); self.end_headers(); self.wfile.write(b)
    def log_message(self, fmt, *a):
        if '/save' in (a[0] if a else ''): return
        sys.stderr.write('%s\n' % (fmt % a))

if __name__ == '__main__':
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8792
    http.server.ThreadingHTTPServer(('127.0.0.1', port), H).serve_forever()

