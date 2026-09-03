# EC2 배포

구성: Cloudflare Pages Function → `http://<EC2>:<기존 nginx listen 포트>` → (기존 server 블록의 `location /bitcom/api/`) → `127.0.0.1:8000` (docker) → Spring Boot(컨테이너 내부 8000)

8080 은 쓰지 않는다. nginx 는 이미 실행 중인 server 블록을 그대로 쓰고 location 만 include 한다.

## 최초 1회

```bash
# 1) 코드
git clone https://github.com/ghals5737/bitcom.git && cd bitcom

# 2) 환경변수 (EC2 에서 직접 생성, 커밋 금지)
cp backend/.env.example backend/.env && vi backend/.env

# 3) docker (Amazon Linux 2023 기준)
sudo dnf install -y docker && sudo systemctl enable --now docker

# 4) 백엔드 컨테이너
sudo ./deploy/ec2/run.sh

# 5) nginx: upstream + location 스니펫 설치 (nginx 는 이미 실행 중)
sudo ./deploy/ec2/install-nginx.sh
#    → 실행 중인 server 블록 안에 아래 한 줄을 추가하고 한 번 더 실행
#      include /etc/nginx/snippets/bitcom-api.location;
```

확인:

```bash
curl -i http://127.0.0.1:8000/bitcom/api/auth/me            # 컨테이너 직접: 401
curl -i http://127.0.0.1:<nginx 포트>/bitcom/api/auth/me     # nginx 경유: 401
```

Cloudflare Pages 환경변수 `BACKEND_ORIGIN`:

- **IP 주소를 직접 쓰면 안 된다.** Pages Functions 의 `fetch` 는 호스트명 없는 IP 로의 요청을 차단한다 (Cloudflare error 1003 "Direct IP access not allowed").
- 도메인이 없으면 sslip.io 로 IP 를 호스트명으로 감싼다: `BACKEND_ORIGIN=http://15.165.171.81.sslip.io` (포트가 80 이 아니면 `:포트` 추가).
- 환경변수를 바꾼 뒤에는 Pages 를 다시 배포해야 반영된다 (Deployments → Retry deployment).

## 재배포

```bash
git pull && sudo ./deploy/ec2/run.sh        # 이미지 재빌드 + 컨테이너 교체
sudo ./deploy/ec2/run.sh --logs             # 로그
```

## 보안그룹

| 포트 | 허용 대상 |
|---|---|
| 22 | 내 IP |
| nginx listen 포트 | Cloudflare IP 대역 (https://www.cloudflare.com/ips/) |
| 8000 | 열지 않음 (127.0.0.1 바인딩) |

## 메모

- `run.sh` 는 컨테이너 포트를 `127.0.0.1:8000` 에만 바인딩한다. 외부에서 8000 으로 직접 접근 불가.
- 컨테이너 안 Spring Boot 도 `SERVER_PORT=8000`, `.env` 의 `COOKIE_SECURE=true`.
- nginx 파일 2개: `conf.d/bitcom-api.conf`(upstream) + `snippets/bitcom-api.location`(location). server 블록은 기존 것을 include 로 재사용.
- 이미지 빌드는 EC2 에서 Gradle 을 돌리므로 t3.small 기준 2~4분, 메모리 1GB 이상 권장. 부족하면 로컬에서 `./gradlew bootJar` 후 jar 만 올리는 방식으로 바꿀 수 있다.
