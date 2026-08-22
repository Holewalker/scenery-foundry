#!/usr/bin/env bash
# RED/GREEN evidence for the Caddyfile's routing and size-limit behavior (ADR-0008 threat
# matrix: "HTTP routing" row). Runs the REAL `Caddyfile` against a real `caddy:2-alpine`
# container on a throwaway Docker network — no mocked HTTP layer — with two body-consuming
# Python stub servers standing in for `backend`/`frontend` (their exact Compose network
# aliases, since `reverse_proxy backend:8080` / `frontend:80` resolve via Docker DNS).
#
# A stub built on `caddy respond` was tried first and rejected: it never reads the request
# body, so Caddy's `request_body { max_size }` — which only errors when something actually
# reads past the limit — never triggered, silently returning 200 for an oversized POST. The
# stub here explicitly drains `Content-Length` bytes before responding, matching what a real
# backend (Spring's request/multipart handling) does, so the size limit is genuinely exercised.
set -euo pipefail

# Windows/Git-Bash + Docker Desktop note (see scripts/backup/tests/_lib.sh): `docker run -v`
# needs a `cygpath -w`-converted host path with MSYS path conversion disabled, or MSYS mangles
# the argument. `hostpath()` is a no-op passthrough on Linux (the actual VPS/CI).
export MSYS_NO_PATHCONV=1
hostpath() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi
}

REPO_ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
SUFFIX="$$-$(date +%s)"
NET="sf-caddytest-net-$SUFFIX"
EDGE="sf-caddytest-edge-$SUFFIX"
WORKDIR="$(mktemp -d)"

fail() { echo "FAIL: $1" >&2; exit 1; }

cleanup() {
  docker rm -f "$EDGE" "sf-caddytest-backend-$SUFFIX" "sf-caddytest-frontend-$SUFFIX" >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
  rm -rf -- "${WORKDIR:?}"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# Body-consuming stub: responds with a fixed marker text ("backend-hit"/"frontend-hit") after
# fully draining the request body, so Caddy's max_size limit is actually exercised.
cat > "$WORKDIR/stub.py" <<'PY'
import http.server, socketserver, sys

class Handler(http.server.BaseHTTPRequestHandler):
    def _consume(self):
        remaining = int(self.headers.get("Content-Length", 0))
        while remaining > 0:
            chunk = self.rfile.read(min(65536, remaining))
            if not chunk:
                break
            remaining -= len(chunk)

    def _respond(self):
        body = sys.argv[1].encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/plain")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        self._respond()

    def do_POST(self):
        self._consume()
        self._respond()

    def log_message(self, *_args):
        pass

socketserver.TCPServer.allow_reuse_address = True
with socketserver.TCPServer(("", int(sys.argv[2])), Handler) as httpd:
    httpd.serve_forever()
PY

docker network create "$NET" >/dev/null

docker run -d --name "sf-caddytest-backend-$SUFFIX" --network "$NET" --network-alias backend \
  -v "$(hostpath "$WORKDIR/stub.py"):/stub.py:ro" \
  python:3-alpine python /stub.py backend-hit 8080 >/dev/null

docker run -d --name "sf-caddytest-frontend-$SUFFIX" --network "$NET" --network-alias frontend \
  -v "$(hostpath "$WORKDIR/stub.py"):/stub.py:ro" \
  python:3-alpine python /stub.py frontend-hit 80 >/dev/null

# `DOMAIN=localhost`: Caddy special-cases `localhost` with its internal CA (self-signed,
# no network calls), so the real Caddyfile's `{$DOMAIN}` automatic-HTTPS directive is exercised
# without needing real ACME/DNS — exactly what a shell test can run offline and repeatably.
docker run -d --name "$EDGE" --network "$NET" -e DOMAIN=localhost \
  -v "$(hostpath "$REPO_ROOT/Caddyfile"):/etc/caddy/Caddyfile:ro" \
  caddy:2-alpine >/dev/null

for _ in $(seq 1 30); do
  docker exec "$EDGE" curl -sk -o /dev/null https://localhost/ >/dev/null 2>&1 && break
  sleep 1
done

curl_edge() {
  # -k: the localhost cert is Caddy's internal CA, not a public one — expected in this test.
  docker exec "$EDGE" curl -sk "https://localhost$1"
}

# --- Routing ---
[ "$(curl_edge /)" = "frontend-hit" ] || fail "expected / to route to frontend, matching non-/api/* handling"
[ "$(curl_edge /api/whatever)" = "backend-hit" ] || fail "expected /api/* to route to backend"
# Path-traversal attempt at the edge: Caddy normalizes '..' segments before matching, so this
# must resolve like /actuator/metrics (not under /api/*) and land on frontend, never backend.
[ "$(curl_edge /api/../actuator/metrics)" = "frontend-hit" ] ||
  fail "expected /api/../actuator/metrics to be unroutable to backend (path traversal at the edge)"

# --- Size limit (must match spring.servlet.multipart.max-request-size: 210MB, which Spring's
# DataSize parses as BINARY = 220,200,960 bytes; see the Caddyfile's own MiB-vs-MB comment) ---
dd if=/dev/zero of="$WORKDIR/at-limit.bin" bs=1M count=205 >/dev/null 2>&1
dd if=/dev/zero of="$WORKDIR/over-limit.bin" bs=1M count=211 >/dev/null 2>&1
docker cp "$(hostpath "$WORKDIR/at-limit.bin")" "$EDGE:/tmp/at-limit.bin" >/dev/null
docker cp "$(hostpath "$WORKDIR/over-limit.bin")" "$EDGE:/tmp/over-limit.bin" >/dev/null

at_limit_status="$(docker exec "$EDGE" curl -sk -H 'Expect:' -o /dev/null -w '%{http_code}' \
  -X POST --data-binary @/tmp/at-limit.bin "https://localhost/api/upload")"
[ "$at_limit_status" = "200" ] ||
  fail "expected a 205 MiB upload (within the 210MB/binary limit) to succeed through Caddy, got status '$at_limit_status'"

over_limit_status="$(docker exec "$EDGE" curl -sk -H 'Expect:' -o /dev/null -w '%{http_code}' \
  -X POST --data-binary @/tmp/over-limit.bin "https://localhost/api/upload")"
[ "$over_limit_status" = "413" ] ||
  fail "expected a 211 MiB upload (over the 210MB limit) to be rejected 413 at the edge, got status '$over_limit_status'"

echo "PASS: Caddyfile routes /api/* to backend, everything else (incl. a traversal attempt) to frontend, accepts a 205 MiB body, and rejects a 211 MiB body with 413."
