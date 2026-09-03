# AI_LOG.md — AI 협업 기록

- 사용 도구: Claude Code (모델 Claude Fable 5.1), 2026-09-03 단일 세션
- 대화 전체: `log/conversation.md` (턴 단위 정리, 질문·답변·소요 시간·도구 호출), `log/conversation.jsonl` (같은 내용의 정제 데이터), `log/raw/*.jsonl` (Claude Code 원본 transcript)
- 로그는 Claude Code 의 Stop 훅이 매 답변 뒤 `log/parse_transcript.py` 를 실행해 자동 갱신했다.

진행 흐름: 과제 분석 → 대화 로그 훅 → 외부 API 실측 스크립트 → 기획 논의(기능 F0~F9, 비기능 N1~N5 를 질문-결정 방식으로 확정, `docs/planning.md`) → 구현 계획(`docs/implementation-plan.md`) → 프론트(목업 API 로 먼저) → 백엔드(Spring Boot + RDS) → 경계·실패 테스트 → 배포(Cloudflare Pages + EC2) → 문서.

---

## A. AI 의 제안을 거절하거나 되돌린 지점

### A-1. 성명 파싱: 복성 사전 대신 "첫 글자 = 성"

- **AI 가 제안한 것**: 복성 사전(남궁·황보·선우 등)으로 우선 분리하고, 계정 생성 화면에서 관리자가 파싱 결과를 확인·수정하게 하자. 시드의 남궁서준·황보라온을 정확히 가르기 위해.
- **내가 대신 택한 것**: 사전 없이 첫 글자를 성, 나머지를 이름으로 기계적으로 나눈다. 관리자 수정 UI 도 두지 않는다.
- **그렇게 판단한 근거**: 이 필드의 목적은 실제 성씨 관리가 아니라 외부 API 의 필수 입력(firstName/lastName)을 채우는 것이다. 선우진은 사전을 둬도 "선우+진" 인지 "선+우진" 인지 못 가른다. 사전은 유지 비용만 늘리고 모호 케이스는 남는다. 실측에서 외부 API 는 어떤 문자열이든 201 을 돌려줬으므로 형식만 맞으면 된다. 대신 이 한계를 DECISIONS 에 적었다. (`EmployeeService.parseName`)

### A-2. 관리자 계정 사번: EMP-000 대신 ADMIN-001

- **AI 가 제안한 것**: 시드 번호 앞인 EMP-000 을 관리자로 두면 "마지막 사번 + 1" 채번 규칙에 영향이 없다.
- **내가 대신 택한 것**: `ADMIN-001` 로 접두어를 분리.
- **그렇게 판단한 근거**: 관리자는 인사 데이터가 아니라 시스템 계정이라는 것을 사번만 봐도 알 수 있어야 한다. 채번은 EMP- 접두어 안에서만 돌게 하면(정규식으로 EMP-숫자만 집계) 규칙도 깨지지 않는다. (`EmployeeRepository.findMaxEmpNumber`)

### A-3. Background Check 결과 보관: 유예 기간 없이 퇴사 시 즉시 삭제

- **AI 가 제안한 것**: 퇴사 후 N일(예: 30일) 유예를 두고 그 뒤 민감 필드만 비우자. 실수 복구와 최소 보유 원칙을 둘 다 잡을 수 있다.
- **내가 대신 택한 것**: 퇴사 처리 트랜잭션 안에서 결과 행을 즉시 삭제하고 삭제 사실만 감사 로그에 남긴다.
- **그렇게 판단한 근거**: 보유 근거가 "재직자 관리" 이면 퇴사 시점이 근거 소멸 시점이다. 유예 기간은 스케줄러와 "언제 지웠는가" 를 설명할 상태가 하나 더 필요해서 6시간 안에서 설명 가능한 범위를 넘는다고 봤다. 복구가 필요한 상황은 DECISIONS (3) 의 "틀리는 상황" 으로 남겼다.

### A-4. (추가) 배포 대상: Railway/Render 대신 Cloudflare Pages + EC2 + RDS

- **AI 가 제안한 것**: git push 로 배포되고 상시 프로세스가 도는 Railway/Render 가 설정 시간이 가장 적다.
- **내가 대신 택한 것**: 정적 프론트는 Cloudflare Pages, 백엔드는 이미 nginx 가 돌고 있는 EC2 에 docker 로, DB 는 RDS.
- **그렇게 판단한 근거**: 과제가 "클라우드를 적극 활용" 을 요구하고, 이미 갖고 있는 EC2/RDS 를 쓰는 편이 면접에서 인프라 구성을 직접 설명하기 좋다. 그 대가로 A-2 에 없던 문제(도메인 없음 → Function 프록시, IP 직접 fetch 차단)를 겪었다. B-6 참고.

---

## B. AI 가 처음에 잘못 만들어서 고친 지점

### B-1. 로그인 실패 카운트가 롤백되어 계정 잠금이 영원히 안 걸림 (실측/DB 확인으로 발견)

- **무엇이 틀렸나**: `AuthService.login` 이 비밀번호 불일치 시 `failedLoginCount` 를 올린 뒤 `ApiException`(RuntimeException) 을 던진다. `@Transactional` 기본 규칙이 RuntimeException 에서 롤백이라 카운트 증가가 매번 취소됐다. 6번째 시도에서도 "(1/5)".
- **어떻게 발견했나**: 단위 테스트(목 저장소)는 통과했다. 배포 전 curl 로 5회 틀린 비밀번호를 보낸 뒤 DB 의 `employees.failed_login_count` 를 직접 조회했더니 0 이었다. 즉 **실측 + DB 확인**. 목 기반 단위 테스트는 트랜잭션 경계를 검증하지 못한다는 것을 이때 알았다.
- **고침**: `@Transactional(noRollbackFor = ApiException.class)`. 이후 curl 로 5회째 LOCKED, 6회째 LOCKED 확인.

### B-2. Spring 빈 순환 참조로 기동 실패 (로그로 발견)

- **무엇이 틀렸나**: `SecurityConfig` 가 `SessionAuthFilter` 를, 필터가 `AuthService` 를, `AuthService` 가 `SecurityConfig` 안에 정의된 `PasswordEncoder` 빈을 필요로 해서 순환.
- **어떻게 발견했나**: 첫 `bootRun` 의 "APPLICATION FAILED TO START ... circular reference" 로그.
- **고침**: `PasswordEncoder` 빈을 `PasswordConfig` 로 분리.

### B-3. 서비스가 자기 자신의 `@Transactional` 메서드를 호출 (코드 리딩으로 발견)

- **무엇이 틀렸나**: `BackgroundCheckService` 가 외부 API 호출 뒤 같은 클래스의 `@Transactional protected` 메서드로 저장하게 짜여 있었다. 내부 호출은 프록시를 타지 않아 트랜잭션이 걸리지 않는다.
- **어떻게 발견했나**: 컴파일 직후 코드를 다시 읽다가. 테스트나 로그로는 드러나지 않았을 결함(저장은 되니까).
- **고침**: 저장 구간을 `TransactionTemplate` 으로 감쌌다.

### B-4. 허용되지 않은 필드를 백엔드가 조용히 무시 (curl 비교로 발견)

- **무엇이 틀렸나**: 직원이 `PATCH /me` 에 `name` 을 보내면 프론트 목업은 403 을 줬는데 Spring 백엔드는 Jackson 이 알 수 없는 필드를 버리고 200 을 줬다. 규칙 7(요청 검증) 위반.
- **어떻게 발견했나**: 목업과 백엔드에 같은 curl 을 보내 응답을 나란히 비교.
- **고침**: `spring.jackson.deserialization.fail-on-unknown-properties=true`. 외부 API 응답 DTO 만 `@JsonIgnoreProperties(ignoreUnknown = true)` 로 예외.

### B-5. 외부 API 가 2xx 인데 checkId 를 안 주면 폴러가 null 을 GET (테스트 작성 중 발견)

- **무엇이 틀렸나**: 명세 밖 응답(checkId 없는 201)을 PENDING 으로 저장해 폴러가 `GET /background-checks/null` 을 반복하게 되어 있었다.
- **어떻게 발견했나**: 경계 조건 테스트를 쓰면서 "2xx 인데 checkId 가 없으면?" 을 케이스로 적다가.
- **고침**: 그 경우 FAILED 로 기록. 테스트 `request_ok_but_missing_checkId_is_recorded_as_FAILED_not_PENDING`.

### B-6. Cloudflare Pages Function 이 EC2 IP 로 fetch 하지 못함 (배포 후 403 으로 발견)

- **무엇이 틀렸나**: 계획 단계에서 "도메인이 없어도 Function 이 `http://<EC2 IP>` 로 프록시하면 된다" 고 봤다. 실제로는 Cloudflare Workers/Functions 의 `fetch` 가 호스트명 없는 IP 접근을 차단한다(error 1003).
- **어떻게 발견했나**: 배포 후 `/bitcom/api/*` 가 전부 403 에 본문 `error code: 1003`. EC2 는 curl 로 정상이었으므로 프록시 구간으로 좁힌 뒤 Cloudflare 문서 확인.
- **고침**: `BACKEND_ORIGIN=http://15.165.171.81.sslip.io` (IP 를 호스트명으로 감싸는 공개 DNS).

### B-7. (프론트) shadcn Button 의 `asChild` 미지원

- **무엇이 틀렸나**: AI 가 Radix 기반 shadcn 을 전제로 `<Button asChild><Link/></Button>` 을 썼는데, 설치된 버전은 Base UI 기반이라 `asChild` 가 없다.
- **어떻게 발견했나**: `tsc` 타입 오류. 고친 뒤 `nativeButton={false}` 누락은 브라우저 콘솔 경고로 다시 발견.
- **고침**: `render={<Link/>} nativeButton={false}`.

---

## C. 이 코드에서 지금 내가 가장 설명하기 어려운 부분

**Next.js `rewrites` 와 Cloudflare Pages Function 프록시가 왜 "같은 경로" 로 동작하는지, 그리고 정적 export 에서 동적 라우트가 왜 안 되는지.**

프론트는 로컬 개발에서는 `next.config.ts` 의 `rewrites` 로 `/bitcom/api/*` 를 Spring Boot(localhost:8081) 에 넘기고, 배포에서는 저장소 루트의 `functions/bitcom/api/[[path]].ts` 가 같은 경로를 EC2 로 넘긴다. 화면 코드는 둘을 구분하지 않고 `/bitcom/api/...` 만 호출한다. 이 구조는 AI 가 제안했고 결과적으로 동작하지만, 다음 두 가지는 내가 원리를 확인하지 않고 받아들였다.

1. 정적 export(`output: "export"`)에서는 `rewrites` 가 무시된다는 경고가 나오는데, 왜 Pages Function 은 정적 파일보다 먼저 요청을 받는지(Cloudflare Pages 의 라우팅 순서).
2. 상세 페이지를 `/admin/employees/[id]` 에서 `/admin/employee?id=` 로 바꿔야 했던 이유. "동적 세그먼트는 빌드 시점에 경로를 다 알아야 정적 HTML 로 만들 수 있다" 는 설명은 이해했지만, `generateStaticParams` 로 시드 10명만 미리 만들고 나머지를 fallback 하는 방법이 왜 export 에서는 불가능한지는 프레임워크 문서를 읽고 확인하지 못했다.

또 하나, Hibernate 에서 `@JdbcTypeCode(SqlTypes.JSON)` 을 붙인 `Map<String,Object>` 필드가 PostgreSQL `jsonb` 컬럼으로 읽고 쓰이는 매핑은 AI 가 쓴 대로 두었고 동작을 확인했을 뿐, 어떤 직렬화기가 어느 시점에 개입하는지는 설명하지 못한다.

이 세 가지는 면접에서 질문받으면 "동작은 확인했고 원리는 확인하지 못했다" 고 답할 부분이다.
