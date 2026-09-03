"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { api, ApiError } from "@/lib/client";
import type { MeSummary, Role } from "@/lib/types";

/**
 * 로그인 상태 조회 + 화면 분기 (F1).
 * - 미로그인 → /login
 * - 임시 비밀번호 상태 → /change-password
 * - role 불일치 → 역할에 맞는 홈으로
 * 실제 통제는 API가 하고, 이 훅은 편의용 분기만 담당한다.
 */
export function useMe(opts: { requireRole?: Role; allowMustChange?: boolean } = {}) {
  const router = useRouter();
  const [me, setMe] = useState<MeSummary | null>(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    try {
      const m = await api<MeSummary>("/auth/me");
      if (m.mustChangePassword && !opts.allowMustChange) {
        router.replace("/change-password");
        return;
      }
      if (opts.requireRole && m.role !== opts.requireRole) {
        router.replace(m.role === "ADMIN" ? "/admin" : "/me");
        return;
      }
      setMe(m);
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) router.replace("/login");
    } finally {
      setLoading(false);
    }
  }, [router, opts.requireRole, opts.allowMustChange]);

  useEffect(() => {
    void load();
  }, [load]);

  return { me, loading, reload: load };
}

export function homeFor(role: Role) {
  return role === "ADMIN" ? "/admin" : "/me";
}
