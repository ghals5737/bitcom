#!/usr/bin/env bash
# 기존 nginx 에 bitcom-api.conf 를 추가하고 검증 후 reload 한다.
#   sudo ./deploy/ec2/install-nginx.sh
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$HERE/nginx/bitcom-api.conf"
DST="/etc/nginx/conf.d/bitcom-api.conf"

[[ -d /etc/nginx/conf.d ]] || { echo "ERROR: /etc/nginx/conf.d 가 없습니다. nginx.conf 의 include 경로를 확인하세요."; exit 1; }
if [[ -f "$DST" ]]; then cp "$DST" "$DST.bak.$(date +%Y%m%d%H%M%S)"; echo "기존 설정 백업: $DST.bak.*"; fi
cp "$SRC" "$DST"
nginx -t
systemctl reload nginx
echo "OK: $DST 적용. 확인: curl -i http://127.0.0.1:8080/bitcom/api/auth/me  (401 이면 정상)"
