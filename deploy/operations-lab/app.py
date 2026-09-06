"""An internal, self-contained HTTP fault laboratory. No production control access."""

import hmac
import json
import os
import time
from http.server import BaseHTTPRequestHandler, HTTPServer


TOKEN = os.environ.get("OPS_OPERATIONS_LAB_TOKEN", "")
if len(TOKEN) < 32:
    raise SystemExit("OPS_OPERATIONS_LAB_TOKEN must contain at least 32 characters")

FAULT_TTL_SECONDS = 45
fault_until = 0.0


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.0"

    def setup(self):
        super().setup()
        self.connection.settimeout(3)

    def reply(self, status_code, status, **extra):
        body = json.dumps({"scope": "ISOLATED_LAB", "status": status, **extra}).encode()
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path != "/health":
            return self.reply(404, "NOT_FOUND")
        remaining = max(0, fault_until - time.monotonic())
        self.reply(503 if remaining else 200, "DOWN" if remaining else "UP",
                   faultActive=bool(remaining), autoRecoverSeconds=round(remaining, 1))

    def do_POST(self):
        global fault_until
        if not hmac.compare_digest(self.headers.get("X-Lab-Token", ""), TOKEN):
            return self.reply(403, "FORBIDDEN")
        if self.path == "/fault":
            fault_until = time.monotonic() + FAULT_TTL_SECONDS
            return self.reply(200, "DOWN", autoRecoverSeconds=FAULT_TTL_SECONDS)
        if self.path == "/recover":
            fault_until = 0.0
            return self.reply(200, "UP")
        self.reply(404, "NOT_FOUND")

    def log_message(self, _format, *_args):
        # Never log authorization headers, requests, or host addresses.
        return


if __name__ == "__main__":
    HTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
