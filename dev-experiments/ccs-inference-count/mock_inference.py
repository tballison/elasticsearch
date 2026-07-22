"""
Mock HuggingFace-compatible embedding server.

Accepts POST to any path with body {"inputs": ["text1", ...]}.
Returns [[0.01] * 128, ...] (variant A: bare array of arrays).

Endpoints:
  POST /            — embed (any path works)
  GET  /health      — liveness check
  GET  /count       — return {"count": N} total infer calls since last reset
  POST /reset       — reset counter to 0
"""
from http.server import HTTPServer, BaseHTTPRequestHandler
import json
import threading

_lock = threading.Lock()
_count = 0
_inputs_total = 0
DIMS = 128
EMBEDDING = [0.01] * DIMS


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass  # silence access log

    def _send_json(self, status, obj):
        body = json.dumps(obj).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/health":
            self._send_json(200, {"ok": True})
        elif self.path == "/count":
            with _lock:
                self._send_json(200, {"count": _count, "inputs_total": _inputs_total})
        else:
            self._send_json(404, {"error": "not found"})

    def do_POST(self):
        global _count, _inputs_total
        if self.path == "/reset":
            with _lock:
                _count = 0
                _inputs_total = 0
            self._send_json(200, {"ok": True})
            return

        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length) if length else b"{}"
        try:
            data = json.loads(body)
            inputs = data.get("inputs", [])
        except Exception:
            inputs = []

        with _lock:
            _count += 1
            _inputs_total += len(inputs)
        print(f"inference call #{_count}: {len(inputs)} input(s): {inputs}", flush=True)

        self._send_json(200, [EMBEDDING for _ in inputs])


if __name__ == "__main__":
    server = HTTPServer(("0.0.0.0", 5000), Handler)
    print("mock-inference listening on :5000", flush=True)
    server.serve_forever()
