#!/usr/bin/env bash
# 기존 nginx 에 upstream(conf.d) + location 스니펫(snippets) 을 설치하고 검증 후 reload 한다.
# server 블록은 새로 만들지 않는다. 실행 중인 server 블록에 include 한 줄을 넣는 것은 수동(아래 안내).
#   sudo ./deploy/ec2/install-nginx.sh
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

[[ -d /etc/nginx/conf.d ]] || { echo "ERROR: /etc/nginx/conf.d 가 없습니다. nginx.conf 의 include 경로를 확인하세요."; exit 1; }
mkdir -p /etc/nginx/snippets

cp "$HERE/nginx/bitcom-api.conf" /etc/nginx/conf.d/bitcom-api.conf
cp "$HERE/nginx/bitcom-api.location" /etc/nginx/snippets/bitcom-api.location

if ! grep -rq "snippets/bitcom-api.location" /etc/nginx/ --include='*.conf' 2>/dev/null; then
  cat <<'MSG'

아직 어떤 server 블록에도 include 되어 있지 않습니다. 실행 중인 server 블록(예: /etc/nginx/conf.d/default.conf 또는
/etc/nginx/nginx.conf) 안에 아래 한 줄을 추가한 뒤 다시 이 스크립트를 실행하세요:

    include /etc/nginx/snippets/bitcom-api.location;

MSG
fi

nginx -t
systemctl reload nginx
echo "OK. 확인: curl -i http://127.0.0.1:<기존 server 의 listen 포트>/bitcom/api/auth/me  (401 이면 정상)"
