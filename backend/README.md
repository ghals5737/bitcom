# backend

Spring Boot 3.5 / Java 17 / PostgreSQL(RDS) / Flyway / Spring Security / Lombok.
모든 API 는 `/bitcom/api` 로 시작한다 (`server.servlet.context-path`).

## 실행

```bash
set -a && . ./.env && set +a   # DB_URL, DB_USER, DB_PASSWORD, BACKGROUND_URL
./gradlew bootRun                # 기본 8080. 로컬에서 8080 이 사용 중이면 SERVER_PORT=8081 ./gradlew bootRun
./gradlew test
```

프론트 개발 서버를 실제 백엔드에 붙이려면 `frontend/.env.local` 에 `BACKEND_URL=http://localhost:8081` 을 두면 된다 (없으면 프론트 목업이 응답).

기동 시 Flyway 가 `src/main/resources/db/migration/V1__init.sql` 을 적용하고, `employees` 가 비어 있으면 `SeedLoader` 가 시드 10명 + ADMIN-001 을 넣는다.
제출용 계정: `ADMIN-001 / admin1234!`, `EMP-001 / emp1234!` (환경변수 `SEED_ADMIN_PASSWORD`, `SEED_EMPLOYEE_PASSWORD` 로 변경 가능).

## 구조 (규칙 1: 도메인 → Controller / Service / Repository)

```
com.bitcom.portal
├── common/      SecurityConfig, SessionAuthFilter(세션 쿠키 → principal), GlobalExceptionHandler,
│                ClockConfig(Clock 빈), TokenGenerator(세션ID·임시비밀번호), AppProperties, SeedLoader
├── employee/    controller: AuthController(/auth), MeController(/me), AdminEmployeeController(/admin/employees)
│                service:    AuthService(로그인·세션·잠금·비밀번호), EmployeeService(조회·수정·생성·퇴사·재발급·이력)
│                repository: Employee, Session, EmployeeChangeLog, AuditLog
│                entity / dto
└── bgcheck/     controller: BackgroundCheckController(/admin/employees/{id}/background-checks, /admin/background-checks/{id})
                 service:    BackgroundCheckService(요청·상세·폴링·재확인·삭제), BackgroundCheckPoller(@Scheduled)
                 client:     BackgroundCheckClient(인터페이스) / RestBackgroundCheckClient(실제 HTTP), BgcProperties
                 repository / entity / dto
```

## 규칙 적용

| 규칙 | 적용 |
|---|---|
| 2. Entity 는 테이블, 변환은 DTO | `entity/*` 는 컬럼만. 응답은 `dto/*` record 의 `from(entity)`, 요청은 validation 붙은 record |
| 3. 시간·랜덤·외부호출 주입 | `Clock`, `TokenGenerator`, `BackgroundCheckClient` 를 생성자 주입. 테스트는 `Clock.fixed`, 고정 토큰, 스크립트된 가짜 클라이언트로 검증 (`src/test`) |
| 4. Controller 분기·트랜잭션 금지 | 컨트롤러는 서비스 호출 + HTTP 변환(쿠키·상태코드)만. `@Transactional` 은 Service 에만 |
| 6. `/bitcom/api` 접두어 | context-path |
| 7. request validation | `@Valid` + `@Pattern/@Size/@NotBlank`. 사번 형식, 한글 성명, 연락처 문자 집합, 태그·따옴표·세미콜론 금지. path variable 도 `@Validated` + `@Pattern` |

## 트랜잭션 경계 (설명 포인트)

- 외부 API 호출은 트랜잭션 밖. 결과 저장만 `TransactionTemplate` 으로 감싼다 (같은 클래스 내부 호출은 `@Transactional` 프록시를 타지 않기 때문).
- 퇴사 처리(`EmployeeService.resign`)는 상태 변경 + 세션 삭제 + Background Check 삭제 + 감사 로그 2건이 한 트랜잭션.

## 외부 API 값 (application.yml `bgcheck.*`)

MEASUREMENTS.md 실측값을 근거로 확정한다. 현재 값은 실측 결과(HBRC-FULL-0903)를 반영한 초안:
read-timeout 35s(응답이 30s 부근에 몰림), POST 재시도 3회/1s, 폴링 5s, 상한 240s 또는 40회(pending→완료 p95 153s).
