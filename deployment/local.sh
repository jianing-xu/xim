#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
: "${1:?Usage: deployment/local.sh /absolute/persistent-data-directory}"
case "$1" in /*) XIM_LOCAL_DATA=$1;; *) echo 'Use an absolute data directory' >&2; exit 1;; esac
umask 077
mkdir -p "$XIM_LOCAL_DATA"
if [ ! -f backend/target/release/xim.jar ]; then echo 'First run ./scripts/build-release.sh' >&2; exit 1; fi
if [ ! -f "$XIM_LOCAL_DATA/cert.pem" ]; then
    openssl req -x509 -newkey rsa:2048 -nodes -days 30 -subj /CN=localhost \
      -addext 'subjectAltName=DNS:localhost,IP:127.0.0.1' \
      -keyout "$XIM_LOCAL_DATA/key.pem" -out "$XIM_LOCAL_DATA/cert.pem"
fi
export XIM_RELEASE_DIR="$PWD/backend/target/release"
export XIM_DB_SQLITE_PATH="$XIM_LOCAL_DATA/xim.db"
export XIM_LOG_DIR="$XIM_LOCAL_DATA/logs"
export XIM_ALLOWED_ORIGINS="https://127.0.0.1:${XIM_STATIC_PORT:-7443}"
export XIM_COOKIE_SECURE=true
export XIM_AUTH_DEMO=false
export XIM_STATIC_PORT="${XIM_STATIC_PORT:-7443}"
export XIM_TLS_CERT="$XIM_LOCAL_DATA/cert.pem"
export XIM_TLS_KEY="$XIM_LOCAL_DATA/key.pem"
export XIM_BACKEND_URL="http://127.0.0.1:${XIM_WEB_PORT:-8080}"
deployment/start-backend.sh &
XIM_LOCAL_BACKEND_PID=$!
XIM_LOCAL_STATIC_PID=''
cleanup() { if [ -n "$XIM_LOCAL_STATIC_PID" ]; then kill "$XIM_LOCAL_STATIC_PID" 2>/dev/null || true; fi; kill "$XIM_LOCAL_BACKEND_PID" 2>/dev/null || true; wait "$XIM_LOCAL_BACKEND_PID" 2>/dev/null || true; }
trap cleanup EXIT
trap 'exit 0' INT TERM
for XIM_LOCAL_ATTEMPT in 1 2 3 4 5 6 7 8 9 10; do
    if ! kill -0 "$XIM_LOCAL_BACKEND_PID" 2>/dev/null; then exit 1; fi
    if curl --fail --silent "$XIM_BACKEND_URL/api/health" >/dev/null; then break; fi
    sleep 1
done
curl --fail --silent "$XIM_BACKEND_URL/api/health" >/dev/null
node deployment/static-server.mjs &
XIM_LOCAL_STATIC_PID=$!
printf '%s\n' "Open $XIM_ALLOWED_ORIGINS (local self-signed certificate; no system trust changed)"
wait "$XIM_LOCAL_STATIC_PID"
