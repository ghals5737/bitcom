#!/usr/bin/env bash
# EC2 에서 백엔드 컨테이너를 빌드·(재)기동한다.
#   sudo ./deploy/ec2/run.sh            # 이미지 빌드 + 컨테이너 교체
#   sudo ./deploy/ec2/run.sh --no-build # 기존 이미지로 재기동만
#   sudo ./deploy/ec2/run.sh --logs     # 로그 따라가기
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BACKEND="$ROOT/backend"
IMAGE="bitcom-portal:latest"
NAME="bitcom-portal"
HOST_PORT="${HOST_PORT:-8000}"      # nginx 가 프록시하는 호스트 포트
CONTAINER_PORT=8000                  # 컨테이너 안 Spring Boot 포트 (SERVER_PORT)
ENV_FILE="$BACKEND/.env"

if [[ "${1:-}" == "--logs" ]]; then
  exec docker logs -f --tail 200 "$NAME"
fi

[[ -f "$ENV_FILE" ]] || { echo "ERROR: $ENV_FILE 이 없습니다. backend/.env.example 을 복사해 값을 채우세요."; exit 1; }
command -v docker >/dev/null || { echo "ERROR: docker 가 설치되어 있지 않습니다."; exit 1; }

if [[ "${1:-}" != "--no-build" ]]; then
  echo "==> 이미지 빌드 ($IMAGE)"
  docker build -t "$IMAGE" "$BACKEND"
fi

if docker ps -a --format '{{.Names}}' | grep -qx "$NAME"; then
  echo "==> 기존 컨테이너 중지/삭제"
  docker rm -f "$NAME" >/dev/null
fi

echo "==> 컨테이너 기동 (host ${HOST_PORT} -> container ${CONTAINER_PORT})"
docker run -d \
  --name "$NAME" \
  --restart unless-stopped \
  --env-file "$ENV_FILE" \
  -e SERVER_PORT="$CONTAINER_PORT" \
  -p "127.0.0.1:${HOST_PORT}:${CONTAINER_PORT}" \
  --log-opt max-size=20m --log-opt max-file=5 \
  "$IMAGE" >/dev/null

echo "==> 기동 대기"
for i in $(seq 1 60); do
  code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:${HOST_PORT}/bitcom/api/auth/me" || true)
  if [[ "$code" == "401" || "$code" == "200" ]]; then
    echo "OK: 백엔드 응답 HTTP $code (${i}x2s)"
    docker image prune -f >/dev/null
    exit 0
  fi
  sleep 2
done
echo "ERROR: 60초 안에 기동하지 않았습니다. 로그:"
docker logs --tail 80 "$NAME"
exit 1
