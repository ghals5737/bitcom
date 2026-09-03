"use client";

import { AppShell } from "@/components/app-shell";
import { useMe } from "@/hooks/use-me";

/** 관리자 영역 가드 (F9). 실제 통제는 API의 requireRole("ADMIN")이 한다. */
export default function AdminLayout({ children }: { children: React.ReactNode }) {
  const { me, loading } = useMe({ requireRole: "ADMIN" });
  if (loading || !me) return null;
  return <AppShell me={me}>{children}</AppShell>;
}
