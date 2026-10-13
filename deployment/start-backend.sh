#!/usr/bin/env sh
set -eu
# Run a built bundle; never compiles or installs dependencies at service startup.
: "${XIM_RELEASE_DIR:?Set absolute XIM_RELEASE_DIR}"
: "${XIM_DB_SQLITE_PATH:?Set absolute persistent database path}"
exec java -Dxim.host=127.0.0.1 -Dxim.port="${XIM_TCP_PORT:-8000}" -Dxim.web.port="${XIM_WEB_PORT:-8080}" \
  -Dxim.web.cookieSecure="${XIM_COOKIE_SECURE:-true}" -Dxim.web.allowedOrigins="${XIM_ALLOWED_ORIGINS:-https://chat.example.com}" \
  -Dxim.log.dir="${XIM_LOG_DIR:-/var/lib/xim/logs}" -cp "$XIM_RELEASE_DIR/xim.jar:$XIM_RELEASE_DIR/lib/*" cn.xjn.xim.server.NettyServer
