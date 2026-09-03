"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { api, ApiError } from "@/lib/client";
import { homeFor } from "@/hooks/use-me";
import type { MeSummary } from "@/lib/types";

/** 진입점: 로그인 상태와 역할에 따라 분기 (F1) */
export default function RootPage() {
  const router = useRouter();
  useEffect(() => {
    api<MeSummary>("/auth/me")
      .then((m) => router.replace(m.mustChangePassword ? "/change-password" : homeFor(m.role)))
      .catch((e) => router.replace(e instanceof ApiError && e.status !== 401 ? "/login" : "/login"));
  }, [router]);
  return null;
}
