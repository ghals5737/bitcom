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
| `lib/mock/store.ts` | 인메모리 목업 저장소. 시드 11명, 세션, 변경 이력, Background Check, 감사 로그. 외부 API 거동(즉시 완료/pending/실패/TIMEOUT)을 확률로 흉내냄 |
| `lib/server/auth.ts` | Route Handler 공통: 세션 쿠키 검증, 역할 검사, 오류 응답 |
| `app/bitcom/api/**` | 실제 백엔드와 같은 경로/계약의 Route Handler. 배포 시 Cloudflare Pages Function 프록시로 대체 |
| `lib/client.ts` | 브라우저 → `/bitcom/api/*` fetch 래퍼 |
| `hooks/use-me.ts` | 로그인 상태 조회 + 역할/임시 비밀번호 분기 |
| `app/login`, `app/change-password`, `app/me` | 공개 / 비밀번호 변경 / 직원 본인 화면 |
| `app/admin/**` | 관리자: 목록, 계정 생성, 상세(기본정보 · Background Check · 이력 탭) |

## 목업 → 실제 백엔드 전환 시

1. `app/bitcom/api/**` 와 `lib/mock/**` 삭제
2. `functions/bitcom/api/[[path]].ts` (Pages Function) 추가, `next.config.ts` 에 `output: "export"` 설정
3. 화면 코드는 변경 없음 (`/bitcom/api/*` 계약 동일)
