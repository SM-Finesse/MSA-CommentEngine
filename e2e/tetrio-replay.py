#!/usr/bin/env python3
"""TETR.IO 기록·재생 프록시 (모놀리식과 MSA 통계 비교용, compare-monolith 워크플로가 띄운다)

처음 받은 GET은 실제 TETR.IO(https://ch.tetr.io)로 보내고 응답을 기억한다. 같은 경로·쿼리가 다시 오면
기억한 응답을 그대로 돌려준다. 모놀리식을 먼저 부르고 MSA를 나중에 부르면, 두 쪽이 **같은 TETR.IO 데이터**를 받는다.

  - 기억하는 것: 2xx와 404 (다시 불러도 같은 답이어야 하는 것). 429·5xx·연결 실패는 기억하지 않는다
    (먼저 부른 쪽이 다시 시도할 때 실제 TETR.IO로 다시 가야 하므로)
  - 열쇠: 경로 + 쿼리 (받은 그대로). 헤더(X-Session-ID 등)는 열쇠에 넣지 않는다
  - GET /__stats : 지금까지의 FORWARD(실제 호출) · REPLAY(재생) 수와 기록

실행: python3 e2e/tetrio-replay.py 9500
앱 설정: tetrio.base-url, finesse.analytics.collector.base-url 를 http://<여기>:9500/api 로
"""
import json
import os
import sys
import threading
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

UPSTREAM = os.environ.get("TETRIO_UPSTREAM", "https://ch.tetr.io")  # 시험할 때만 바꾼다
USER_AGENT = "Finesse-MSA-compare/0.1 (+https://github.com/SM-Finesse/MSA-CommentEngine)"

store = {}  # 열쇠 -> (status, content_type, body)
counts = {"forward": 0, "replay": 0, "error": 0}
log = []    # [종류, 상태, 열쇠]
lock = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        if self.path == "/__stats":
            with lock:
                body = json.dumps({"counts": counts, "log": log}, ensure_ascii=False).encode()
            return self._send(200, "application/json", body)

        key = self.path
        with lock:
            hit = store.get(key)
        if hit is not None:
            with lock:
                counts["replay"] += 1
                log.append(["REPLAY", hit[0], key])
            print(f"REPLAY  {hit[0]} {key}", flush=True)
            return self._send(*hit)

        headers = {"Accept": self.headers.get("Accept") or "application/json", "User-Agent": USER_AGENT}
        if self.headers.get("X-Session-ID"):
            headers["X-Session-ID"] = self.headers["X-Session-ID"]
        try:
            with urllib.request.urlopen(urllib.request.Request(UPSTREAM + key, headers=headers), timeout=20) as r:
                got = (r.status, r.headers.get("Content-Type") or "application/json", r.read())
        except urllib.error.HTTPError as e:
            got = (e.code, e.headers.get("Content-Type") or "application/json", e.read())
        except Exception as e:  # 닿지 않음 — 기억하지 않는다
            with lock:
                counts["error"] += 1
                log.append(["ERROR", 0, f"{key} {e}"])
            print(f"ERROR   {key} {e}", flush=True)
            return self._send(502, "text/plain", str(e).encode())

        keep = 200 <= got[0] < 300 or got[0] == 404
        with lock:
            if keep:
                store[key] = got
            counts["forward"] += 1
            log.append(["FORWARD", got[0], key])
        print(f"FORWARD {got[0]} {key}{'' if keep else ' (기억하지 않음)'}", flush=True)
        self._send(*got)

    def _send(self, status, content_type, body):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):  # 기본 접근 기록은 끈다 (위에서 직접 찍음)
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 9500
    print(f"tetrio-replay: 0.0.0.0:{port} -> {UPSTREAM}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
