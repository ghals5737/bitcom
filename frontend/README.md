# frontend

Next.js 16 (App Router) + Tailwind v4 + shadcn/ui (Base UI 기반 헤드리스).

## 실행

```bash
npm install
npm run dev
```

평가용 계정(목업): 관리자 `ADMIN-001 / admin1234!`, 직원 `EMP-001 / emp1234!`.
그 외 시드 직원은 임시 비밀번호 `Temp-EMP-00N!1` 상태라 첫 로그인 시 변경을 강제한다.

## 구조

| 경로 | 역할 |
|---|---|
| `lib/types.ts` | DB 스키마(docs/implementation-plan.md 3절)와 1:1 타입 + API DTO |
| `lib/client.ts` | 브라우저 → `/bitcom/api/*` fetch 래퍼 |
| `hooks/use-me.ts` | 로그인 상태 조회 + 역할/임시 비밀번호 분기 |
| `app/login`, `app/change-password`, `app/me` | 공개 / 비밀번호 변경 / 직원 본인 화면 |
| `app/admin/**` | 관리자: 목록, 계정 생성, 상세 `/admin/employee?id=` (기본정보 · Background Check · 이력 탭). 정적 export 를 위해 동적 세그먼트 대신 쿼리 사용 |

## 실행 모드

| 모드 | 명령 | API 경로 처리 |
|---|---|---|
| 로컬 개발 | `npm run dev` (+ `.env.local` 의 `BACKEND_URL=http://localhost:8081`) | next.config rewrites → Spring Boot |
| Cloudflare Pages | `npm run build:pages` → `out/` | 저장소 루트 `functions/bitcom/api/[[path]].ts` 가 `BACKEND_ORIGIN` 으로 프록시 |

평가용 계정: 관리자 `ADMIN-001 / admin1234!`, 직원 `EMP-001 / emp1234!` (백엔드 시드).
