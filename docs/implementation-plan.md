# 구현 계획

기준 문서: [planning.md](planning.md) (기능 F0~F9, 비기능 N1~N5 확정 사항)
작업 예산: 6시간. 측정 대기 시간 제외.

## 1. 아키텍처

```
브라우저
  │ https://<project>.pages.dev            (Cloudflare Pages, 정적 Next.js)
  │   /            → 정적 파일
  │   /bitcom/api/* → Pages Function 프록시 → http://<EC2>:8080/bitcom/api/*
  ▼
Spring Boot (EC2, 8080)
  ├─ Spring Security + 세션 테이블(RDB)        N1
  ├─ REST API /bitcom/api/**                          아래 4절
  ├─ @Scheduled 폴링 워커 (Background Check)   N3
  └─ RDS PostgreSQL                            N4/N5
         ▲
외부 Background Check API (ap-northeast-2) ◄── 서버만 호출
```

### 도메인 없음 → 오리진 문제 해결 (결정)
- Cloudflare Pages Functions(저장소 루트 `functions/bitcom/api/[[path]].ts`)가 `/bitcom/api/*`를 EC2로 프록시.
- 브라우저는 pages.dev 단일 오리진만 봄 → 세션 쿠키 `SameSite=Lax; Secure; HttpOnly` 그대로 사용. Safari 서드파티 쿠키 차단 영향 없음.
- Cloudflare → EC2 구간은 HTTP. EC2 보안그룹 인바운드 8080을 Cloudflare IP 대역으로만 허용. SSH는 내 IP만.
- 반대 선택(sslip.io + Let's Encrypt로 EC2 HTTPS + SameSite=None): 구간 암호화는 되지만 Safari/ITP에서 로그인 실패 위험. DECISIONS 재료.
- 로컬 개발: Next.js `rewrites`로 `/bitcom/api/*` → `localhost:8080` (프록시와 동일한 형태).

## 2. 저장소 구조

```
bitcom/
├── backend/            Spring Boot 3, Java 17, Gradle
│   └── src/main/java/com/bitcom/portal/
│       ├── auth/       로그인, 세션, 비밀번호, 잠금
│       ├── employee/   직원 CRUD, 변경 이력, 퇴사
│       ├── bgcheck/    외부 API 클라이언트, 폴링 워커, 이력
│       ├── audit/      감사 로그
│       └── common/     예외, 응답 포맷, 시드 로더
├── frontend/           Next.js (App Router, output: 'export')
│   ├── app/            login, me, change-password, admin/*
├── functions/bitcom/api/[[path]].ts   Cloudflare Pages 프록시 (저장소 루트 = Pages 프로젝트 루트)
├── measure/            API 실측 스크립트 + 결과
├── docs/               planning.md, implementation-plan.md
├── log/                AI 대화 로그
├── MEASUREMENTS.md  DECISIONS.md  AI_LOG.md  README.md
```

## 3. DB 스키마 (PostgreSQL)

### employees
| 컬럼 | 타입 | 비고 |
|---|---|---|
| employee_id | varchar PK | EMP-001, ADMIN-001 |
| name | varchar | 성명 통째 |
| last_name / first_name | varchar | 생성 시 파싱(첫 글자 / 나머지) |
| birth_date | date null | EMP-007 null |
| phone, address | varchar null | 직원 자가수정 가능 |
| department, position | varchar null | |
| hire_date | date null | |
| role | varchar | ADMIN / EMPLOYEE |
| status | varchar | ACTIVE / RESIGNED |
| resigned_at | date null | |
| password_hash | varchar | bcrypt |
| must_change_password | boolean | 임시 비밀번호 상태 |
| failed_login_count | int | |
| locked | boolean | |
| created_at, updated_at | timestamptz | |

### sessions
| session_id (PK, 랜덤 32바이트 hex) | employee_id FK | created_at | last_accessed_at | expires_at |

- 미사용 30분: `last_accessed_at + 30m`을 요청마다 갱신. 절대 상한 8시간(`created_at + 8h`).
- 퇴사 처리 / 잠금 / 비밀번호 재발급 시 해당 employee_id 세션 전부 삭제.

### employee_change_logs (F3 변경 이력)
| id | employee_id | changed_by | field | old_value | new_value | changed_at |

### background_checks (F7/F8)
| 컬럼 | 비고 |
|---|---|
| id PK, employee_id FK | |
| check_id | 외부 checkId (null 가능: POST 실패 시) |
| status | PENDING / CLEAR / FLAGGED / FAILED / TIMEOUT |
| criminal_record, education_verified, employment_verified, credit_score | 완료 시만, 로그 미기록 |
| requested_by, requested_at | 관리자 |
| completed_at | 외부 completedAt |
| last_polled_at, poll_count | 워커용 |
| failure_reason | HTTP 코드/에러 요약 |
| request_payload | 보낸 firstName/lastName/dob (디버그·설명용) |

- 퇴사 처리 시 해당 employee_id 행 전부 삭제 (F8).

### audit_logs (N4)
| id | actor_id | action | target_employee_id | detail(json, 민감값 제외) | created_at |

action: `EMPLOYEE_UPDATED`, `BGCHECK_REQUESTED`, `BGCHECK_VIEWED`, `BGCHECK_DELETED`, `EMPLOYEE_RESIGNED`, `PASSWORD_RESET`, `ACCOUNT_LOCKED`

## 4. API 목록

| 메서드 | 경로 | 권한 | 기능 |
|---|---|---|---|
| POST | /bitcom/api/auth/login | 공개 | 사번+비밀번호 → 세션 쿠키. 퇴사자/잠금 거부, 실패 카운트 |
| POST | /bitcom/api/auth/logout | 로그인 | 세션 삭제 |
| GET | /bitcom/api/auth/me | 로그인 | 내 사번·이름·role·must_change_password |
| POST | /bitcom/api/auth/change-password | 로그인 | 규칙 검증, 임시와 동일 불가, 세션 유지 |
| GET | /bitcom/api/me | EMPLOYEE/ADMIN | 내 정보 (F2 열람 항목만) |
| PATCH | /bitcom/api/me | EMPLOYEE/ADMIN | phone, address만. 변경 이력 기록 |
| GET | /bitcom/api/admin/employees?status= | ADMIN | 목록 + 필터. 최근 BGC 상태 포함 |
| POST | /bitcom/api/admin/employees | ADMIN | 생성. 채번, 파싱, 임시 비밀번호 1회 반환 |
| GET | /bitcom/api/admin/employees/{id} | ADMIN | 상세 + 퇴사일 + BGC 이력 요약 |
| PATCH | /bitcom/api/admin/employees/{id} | ADMIN | 전체 항목 수정. 변경 이력 기록 |
| POST | /bitcom/api/admin/employees/{id}/resign | ADMIN | 퇴사 처리: 상태 변경, 세션 삭제, BGC 삭제, 감사 로그 |
| POST | /bitcom/api/admin/employees/{id}/reset-password | ADMIN | 임시 비밀번호 재발급 + 잠금 해제 + 세션 삭제 |
| POST | /bitcom/api/admin/employees/{id}/background-checks | ADMIN | 요청. 조건: 생년월일 있음, 진행 중 건 없음 |
| GET | /bitcom/api/admin/employees/{id}/background-checks | ADMIN | 이력 목록 (status·일시만) |
| GET | /bitcom/api/admin/background-checks/{bcId} | ADMIN | 상세 (민감 필드). 열람 감사 로그 |
| POST | /bitcom/api/admin/background-checks/{bcId}/refresh | ADMIN | TIMEOUT 건 GET 재확인 |
| GET | /bitcom/api/admin/employees/{id}/history | ADMIN | 변경 이력 + 감사 로그 (이력 탭) |

권한 검사는 Spring Security(세션 필터 + 역할) + 서비스 계층에서 "본인 또는 ADMIN" 재검사. 퇴사자는 세션 필터에서 status 확인 후 401.

## 5. 화면 목록 (Next.js)

| 경로 | 역할 | 내용 |
|---|---|---|
| /login | 공개 | 사번·비밀번호. 퇴사/잠금 안내 문구 |
| /change-password | 로그인 | 첫 로그인 강제 진입. 완료 전 다른 경로 차단 |
| /me | EMPLOYEE | 내 정보 조회 + 연락처·주소 수정 폼 |
| /admin | ADMIN | 직원 목록 (상태 필터, 최근 BGC 배지) + 계정 생성 버튼 |
| /admin/employees/new | ADMIN | 생성 폼. 성공 시 사번·임시 비밀번호 모달(1회) |
| /admin/employee?id= | ADMIN | (정적 export 를 위해 쿼리스트링) 탭: 기본정보(수정) / Background Check(요청·이력·상세 펼침·재확인) / 이력 |

- 로그인 후 role로 분기: ADMIN → /admin, EMPLOYEE → /me. must_change_password면 /change-password.
- BGC 탭은 PENDING 건이 있으면 5초마다 목록 재조회(화면은 DB만 봄).
- 상세 펼침은 클릭 시에만 API 호출(열람 감사 로그 정확성).

## 6. Background Check 워커 (N3)

- 요청 API: POST 외부 → 201이면 행 생성(PENDING 또는 즉시 CLEAR/FLAGGED). 실패면 FAILED 행 + 사유.
- `@Scheduled(fixedDelay = <폴링 주기>)`: PENDING 행을 모아 GET. 200이면 상태 갱신, 500/503이면 poll_count 증가만, 404면 FAILED.
- 폴링 상한 초과 → TIMEOUT. `refresh` API로 GET 재시도.
- 값(타임아웃, 재시도 횟수·간격, 폴링 주기, 중단 조건)은 MEASUREMENTS.md 확정 후 `application.yml`에 상수로. 코드에는 이름만 먼저 둠.
- 외부 API 호출 실패는 요청 트랜잭션과 분리(외부 실패가 앱 데이터에 영향 없게).

## 7. 시드 (F0)

- 시드 10명 + ADMIN-001. 앱 기동 시 employees가 비어 있으면 적재.
- 관리자·직원 제출용 계정: ADMIN-001 / EMP-001. 비밀번호는 시드에서 고정값으로 넣고 must_change_password=false (평가자 편의).
  그 외 시드 직원은 must_change_password=true.

## 8. 작업 순서와 시간 배분

| 순서 | 작업 | 예산 | 산출물 |
|---|---|---|---|
| 0 | 측정 전체 실행 띄우기 (대기 시간 제외) | 0:05 | measure/results |
| 1 | 백엔드 골격: 엔티티·스키마·시드·세션 인증·로그인/로그아웃/me | 1:00 | 로그인 동작 |
| 2 | 직원 API: me 조회/수정+이력, 관리자 목록/생성/상세/수정/퇴사/재발급 | 1:00 | 관리자 흐름 완성 |
| 3 | BGC: 외부 클라이언트, 요청 API, 워커, 이력/상세/refresh, 감사 로그 | 1:00 | 결과 확인 가능 |
| 4 | 프론트: 로그인·비밀번호 변경·내 정보·관리자 목록·생성·상세(3탭) | 1:30 | 전체 화면 |
| 5 | 배포: RDS, EC2(docker), Pages+Function 프록시, 보안그룹 | 0:45 | URL |
| 6 | 문서: MEASUREMENTS(실측값→설정값 반영), DECISIONS, AI_LOG, README | 0:40 | 제출물 2·3·4 |

### 범위 조정 후보 (시간 부족 시 뺄 순서)
1. 이력 탭 화면 (API는 유지, 화면만 생략)
2. 관리자의 직원 정보 수정 화면 (생성·조회·퇴사만 유지)
3. refresh(TIMEOUT 재확인) 버튼
4. 목록 상태 필터 (전체 표시만)

뺀 항목은 README에 "무엇을 넣고 뺐는지"로 기록.

## 9. 배포 절차 (N5)

1. RDS PostgreSQL (ap-northeast-2, db.t4g.micro, 퍼블릭 접근 불가, EC2 SG만 허용)
2. EC2 (ap-northeast-2, t3.small, Amazon Linux 2023, docker) — Spring Boot 이미지 실행, 환경변수로 DB·외부 API URL
3. Cloudflare Pages — 루트 디렉터리 = 저장소 루트, 빌드 명령 `cd frontend && npm ci && npm run build:pages`, 출력 `frontend/out`, 환경변수 `BACKEND_ORIGIN=http://<EC2>:8080`, `NODE_VERSION=22`
4. EC2 SG: 8080 인바운드 = Cloudflare IP 대역, 22 = 내 IP
5. 제출용 계정 확인, 스모크 테스트(로그인 → 생성 → BGC → 퇴사 → 재로그인 거부)
