#!/bin/bash
# 安全重启：PGlite 是单进程独占，正常 stop 后仍会残留 postmaster.pid，
# 不清掉它下一次启动必定 "PGlite failed to initialize properly" 崩溃循环。
set -e
cd /opt/doujiajia-license-ota
systemctl stop doujiajia-license-ota || true
for i in $(seq 1 10); do
  ps -ef | grep -q "[n]ode src/server.js" || break
  sleep 1
done
if ps -ef | grep -q "[n]ode src/server.js"; then
  echo "!! 仍有 node 进程，放弃重启"
  exit 1
fi
rm -f data/pgdata/postmaster.pid
systemctl start doujiajia-license-ota

for i in $(seq 1 10); do
  sleep 2
  if curl -s -m 2 http://127.0.0.1:3101/health/live >/dev/null 2>&1; then
    echo "service: up"
    curl -s -m 3 -w " | HTTP %{http_code}\n" http://127.0.0.1:3101/health/live
    exit 0
  fi
  if [ "$(systemctl is-active doujiajia-license-ota)" != "active" ] && [ "$(systemctl is-active doujiajia-license-ota)" != "activating" ]; then
    echo "!! 启动失败：$(systemctl is-active doujiajia-license-ota)"
    journalctl -u doujiajia-license-ota --since "-1 min" --no-pager -o cat 2>/dev/null | grep -iE "^Error|SyntaxError|Cannot find" | head -5
    exit 1
  fi
done
echo "!! 超时未起来"
exit 1
