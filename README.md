# 비트컴퓨터 사내 직원 포털 — 과제 제출

## 제출물 1. 동작하는 애플리케이션

| 항목 | 값 |
|---|---|
| 배포 URL | https://bitcom.pages.dev |
| 관리자 계정 | `ADMIN-001` / `admin1234!` |
| 직원 계정 | `EMP-001` / `emp1234!` |
| 소스 | https://github.com/ghals5737/bitcom |

그 외 시드 직원(EMP-002 ~ EMP-010)은 임시 비밀번호 `Temp-EMP-00N!1` 상태이며 첫 로그인 시 비밀번호 변경을 강제한다.

## 제출물 2 · 3 · 4

- [MEASUREMENTS.md](MEASUREMENTS.md) — 외부 API 실측 결과와 그로부터 정한 타임아웃·재시도·폴링 값
- [DECISIONS.md](DECISIONS.md) — 명세가 정하지 않은 4개 항목의 설계 판단
- [AI_LOG.md](AI_LOG.md) — AI 협업 기록 (A/B/C). 대화 전문은 `log/conversation.md`

## 구성

```
브라우저 ── https://bitcom.pages.dev (Cloudflare Pages, Next.js 정적 export)
              └─ /bitcom/api/*  → Pages Function 프록시 (functions/bitcom/api/[[path]].ts)
                                   → EC2 nginx (:80, location /bitcom/api/)
                                     → docker 127.0.0.1:8000  Spring Boot 3.5 / Java 17
                                       ├─ RDS PostgreSQL 17 (Flyway 스키마)
                                       └─ 외부 Background Check API (서버만 호출, 5초 주기 폴링)
```

| 디렉터리 | 내용 |
|---|---|
| `frontend/` | Next.js 16 + shadcn/ui. 화면 6개 (로그인, 비밀번호 변경, 내 정보, 직원 목록, 계정 생성, 직원 상세) |
| `backend/` | Spring Boot. 도메인 `employee`, `bgcheck` 아래 Controller / Service / Repository. 단위 테스트 116건 |
| `functions/` | Cloudflare Pages Function (API 프록시) |
| `deploy/ec2/` | Dockerfile 실행 스크립트, nginx 설정 |
| `measure/` | 외부 API 실측 스크립트와 결과 원자료 |
| `docs/` | 기획 결정 기록(`planning.md`), 구현 계획, AI 로그 메모 |
| `log/` | AI 대화 로그 (자동 갱신) |

로컬 실행은 `frontend/README.md`, `backend/README.md`, 배포는 `deploy/ec2/README.md` 참고.

## 구현 범위

### 넣은 것

- 로그인(사번 + 비밀번호), 서버 세션(DB 테이블) + HttpOnly 쿠키, 미사용 30분 만료, 5회 실패 잠금 → 관리자 재발급으로만 해제, 임시 비밀번호 첫 로그인 변경 강제
- 직원: 내 정보 조회, 연락처·주소 즉시 수정 + 변경 이력
- 관리자: 직원 목록(재직/퇴사 필터, 검색), 계정 생성(사번 자동 채번, 임시 비밀번호 1회 표시), 상세 조회·전 항목 수정, 퇴사 처리(즉시 차단 + 세션 삭제 + Background Check 결과 삭제), 임시 비밀번호 재발급
- Background Check: 관리자 수동 요청, 서버 백그라운드 폴링, 즉시 완료/pending/실패/시간 초과 상태, TIMEOUT 건 GET 재확인, 상세 열람 시 감사 로그, 직원 상세의 이력 탭(변경 이력 + 감사 로그)
- 시드 10명 그대로 적재 (EMP-007 생년월일 없음 → Background Check 요청 불가로 표시)
- 요청 DTO validation (사번 형식, 한글 성명, 허용 문자, 길이, 알 수 없는 필드 400)
- 서비스 단위 테스트 116건 (정상 21 + 경계·실패 95)

### 뺀 것과 이유

| 뺀 것 | 이유 |
|---|---|
| 정보 수정 승인 워크플로우 | 즉시 반영 + 이력으로 대체 (DECISIONS 4). 승인 테이블·화면이 예산을 넘음 |
| Background Check 결과 필드 암호화 | 저장소 암호화(RDS) + 로그 미기록 + 퇴사 시 삭제로 대체 (DECISIONS 3). 키 관리를 설명할 수 없다고 판단 |
| 트랜잭션 경계·보안 필터 통합 테스트 | 테스트용 DB 의존성이 필요해 보류. 대신 curl 스모크 테스트로 확인했고, 이 공백 때문에 놓친 버그(잠금 롤백)를 AI_LOG B-1 에 기록 |
| 퇴사 처리 되돌리기 | DECISIONS 1·3 의 "틀리는 상황" 으로 기록만 |
| 세션 저장소 Redis 이원화 | 서버 1대라 RDB 테이블로 충분. 저장소 인터페이스만 분리 |
| EC2 → 외부 API, Cloudflare → EC2 구간 HTTPS | 도메인 없음. Cloudflare 구간은 보안그룹으로 접근 제한 |

## 알려진 제약

- 프론트와 백엔드가 다른 오리진이라 Pages Function 프록시로 단일 오리진을 만들었다. Function 은 IP 로 fetch 할 수 없어 `BACKEND_ORIGIN` 은 sslip.io 호스트명이다.
- 외부 API 폴링 값(읽기 타임아웃 35s, POST 재시도 3회/1s, 폴링 5s, 상한 240s 또는 40회)은 2026-09-03 실측 기준이다. 근거는 MEASUREMENTS.md 7절.
- 평가용 DB 에는 개발 중 스모크 테스트로 만든 EMP-011 과 EMP-010 퇴사 처리, Background Check 이력 몇 건이 남아 있다.
