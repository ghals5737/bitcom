// Route Handler 공통: 세션 쿠키 → 직원 확인, 역할 검사, 오류 응답 포맷.
// 실제 백엔드에서는 Spring Security 세션 필터가 이 역할을 한다.

import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { resolveSession, StoreError } from "@/lib/mock/store";
import type { Employee, Role, Session } from "@/lib/types";

export const SESSION_COOKIE = "SESSION";

export function jsonError(status: number, error: string, message: string) {
  return NextResponse.json({ error, message }, { status });
}

export async function currentUser(): Promise<{ session: Session; employee: Employee } | null> {
  const jar = await cookies();
  return resolveSession(jar.get(SESSION_COOKIE)?.value);
}

/** 로그인 필수. 비밀번호 변경이 필요한 상태면 변경 API 외에는 403. */
export async function requireUser(opts: { allowMustChange?: boolean } = {}) {
  const cu = await currentUser();
  if (!cu) throw new StoreError(401, "UNAUTHENTICATED", "로그인이 필요합니다.");
  if (cu.employee.mustChangePassword && !opts.allowMustChange) {
    throw new StoreError(403, "PASSWORD_CHANGE_REQUIRED", "임시 비밀번호를 변경한 뒤 이용할 수 있습니다.");
  }
  return cu;
}

export async function requireRole(role: Role) {
  const cu = await requireUser();
  if (cu.employee.role !== role) throw new StoreError(403, "FORBIDDEN", "관리자만 사용할 수 있는 기능입니다.");
  return cu;
}

/** 핸들러 본문을 감싸 StoreError → JSON 오류 응답으로 변환 */
export async function handle(fn: () => Promise<Response>): Promise<Response> {
  try {
    return await fn();
  } catch (e) {
    if (e instanceof StoreError) return jsonError(e.status, e.code, e.message);
    console.error(e);
    return jsonError(500, "INTERNAL", "서버 오류가 발생했습니다.");
  }
}

export async function readJson<T>(req: Request): Promise<T> {
  try {
    return (await req.json()) as T;
  } catch {
    throw new StoreError(400, "BAD_JSON", "요청 본문이 올바른 JSON이 아닙니다.");
  }
}
