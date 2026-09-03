# EC2 배포

구성: Cloudflare Pages Function → `http://<EC2>:8080` (nginx) → `127.0.0.1:8000` (docker) → Spring Boot(컨테이너 내부 8080)

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

# 5) nginx 에 /bitcom/api 프록시 추가 (nginx 는 이미 설치되어 있다고 가정)
sudo ./deploy/ec2/install-nginx.sh
```

확인:

```bash
curl -i http://127.0.0.1:8000/bitcom/api/auth/me   # 컨테이너 직접: 401
curl -i http://127.0.0.1:8080/bitcom/api/auth/me   # nginx 경유: 401
```

Cloudflare Pages 환경변수 `BACKEND_ORIGIN=http://<EC2 퍼블릭 IP>:8080`.

## 재배포

```bash
git pull && sudo ./deploy/ec2/run.sh        # 이미지 재빌드 + 컨테이너 교체
sudo ./deploy/ec2/run.sh --logs             # 로그
```

## 보안그룹

| 포트 | 허용 대상 |
|---|---|
| 22 | 내 IP |
| 8080 | Cloudflare IP 대역 (https://www.cloudflare.com/ips/) |
| 8000 | 열지 않음 (127.0.0.1 바인딩) |

## 메모

- `run.sh` 는 컨테이너 포트를 `127.0.0.1:8000` 에만 바인딩한다. 외부에서 8000 으로 직접 접근 불가.
- 컨테이너 안 Spring Boot 는 `SERVER_PORT=8080`, `.env` 의 `COOKIE_SECURE=true`.
- nginx 의 `listen 8080` 은 기존 server 블록과 충돌하면 바꾸고, Pages 의 `BACKEND_ORIGIN` 포트도 같이 맞춘다.
- 이미지 빌드는 EC2 에서 Gradle 을 돌리므로 t3.small 기준 2~4분, 메모리 1GB 이상 권장. 부족하면 로컬에서 `./gradlew bootJar` 후 jar 만 올리는 방식으로 바꿀 수 있다.
