# AI_LOG 작성용 메모 (작업 중 발생한 사건 기록)

## B. AI가 처음에 잘못 만들어서 고친 지점

1. **로그인 실패 카운트가 롤백되어 잠금이 영원히 안 걸림** (backend AuthService.login)
   - 무엇이 틀렸나: 실패 카운트를 올린 뒤 ApiException(RuntimeException)을 던졌는데, `@Transactional` 기본 동작이 RuntimeException 에서 롤백이라 카운트 증가가 사라짐. 6번째 시도에서도 "(1/5)".
   - 어떻게 발견: 단위 테스트(목 저장소)는 통과했음. curl 로 5회 실패 후 DB(`employees.failed_login_count`)를 직접 조회해 0인 것을 보고 발견 → **실측/DB 확인**.
   - 고침: `@Transactional(noRollbackFor = ApiException.class)`.
   - 교훈: 목 기반 단위 테스트는 트랜잭션 경계를 검증하지 못한다.

2. **SecurityConfig ↔ SessionAuthFilter ↔ AuthService ↔ PasswordEncoder 순환 참조로 기동 실패**
   - 발견: 기동 로그(APPLICATION FAILED TO START, circular reference).
   - 고침: PasswordEncoder 빈을 PasswordConfig 로 분리.

3. **BackgroundCheckService 안에서 자기 자신의 @Transactional 메서드 호출** (프록시 미적용)
   - 발견: 코드 리딩 (컴파일 직후 자체 검토).
   - 고침: 저장 구간을 TransactionTemplate 으로 감쌈.

4. **프론트: 직원이 허용되지 않은 필드(name)를 보내면 백엔드가 200으로 무시**
   - 발견: curl 로 목업(403)과 백엔드(200) 응답 비교.
   - 고침: `spring.jackson.deserialization.fail-on-unknown-properties=true` + 외부 API DTO 만 `@JsonIgnoreProperties(ignoreUnknown)`.

5. **프론트: shadcn Button 의 `asChild` 미지원 (Base UI 기반)** → 타입 오류로 발견, `render` prop 으로 교체. 이후 `nativeButton={false}` 누락은 브라우저 콘솔 경고로 발견.

## A. AI 제안을 거절/변경한 지점 (사용자 결정)

1. 성명 파싱: AI 는 복성 사전 + 관리자 확인 제안 → 사용자는 "첫 글자 = 성" 단순 규칙 (목적이 API 형식 맞추기).
2. 관리자 사번: AI 는 EMP-000 제안 → 사용자는 ADMIN-001 접두어 분리.
3. Background Check 보관: AI 는 퇴사 후 N일 유예 뒤 민감 필드 파기 제안 → 사용자는 퇴사 시 즉시 삭제.
4. 배포: AI 는 Railway/Render 제안 → 사용자는 Cloudflare Pages + EC2 + RDS.

## C. 설명하기 어려운 부분 후보

- Next.js `rewrites.beforeFiles` 가 app/bitcom/api Route Handler 보다 우선 적용되는 동작 (프레임워크 내부 순서에 의존).
- Hibernate `@JdbcTypeCode(SqlTypes.JSON)` 으로 Map → jsonb 매핑되는 방식.
