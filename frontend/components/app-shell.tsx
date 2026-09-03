"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { api } from "@/lib/client";
import type { MeSummary } from "@/lib/types";
import { cn } from "@/lib/utils";

export function AppShell({ me, children }: { me: MeSummary; children: React.ReactNode }) {
  const router = useRouter();
  const pathname = usePathname();
  const nav = me.role === "ADMIN"
    ? [{ href: "/admin", label: "직원 목록" }, { href: "/admin/employees/new", label: "계정 생성" }, { href: "/me", label: "내 정보" }]
    : [{ href: "/me", label: "내 정보" }];

  async function logout() {
    await api("/auth/logout", { method: "POST" });
    router.replace("/login");
  }

  return (
    <div className="min-h-screen bg-muted/30">
      <header className="border-b bg-background">
        <div className="mx-auto flex h-14 max-w-6xl items-center justify-between px-4">
          <div className="flex items-center gap-6">
            <Link href={me.role === "ADMIN" ? "/admin" : "/me"} className="font-semibold tracking-tight">
              비트컴퓨터 직원 포털
            </Link>
            <nav className="flex items-center gap-1">
              {nav.map((n) => (
                <Link
                  key={n.href}
                  href={n.href}
                  className={cn(
                    "rounded-md px-3 py-1.5 text-sm text-muted-foreground transition-colors hover:bg-muted hover:text-foreground",
                    (pathname === n.href || (n.href !== "/admin" && pathname.startsWith(n.href))) && "bg-muted text-foreground",
                  )}
                >
                  {n.label}
                </Link>
              ))}
            </nav>
          </div>
          <div className="flex items-center gap-3">
            <span className="text-sm">
              {me.name} <span className="text-muted-foreground">({me.employeeId})</span>
            </span>
            <Badge variant={me.role === "ADMIN" ? "default" : "secondary"}>{me.role === "ADMIN" ? "관리자" : "직원"}</Badge>
            <Button variant="outline" size="sm" onClick={logout}>로그아웃</Button>
          </div>
        </div>
      </header>
      <main className="mx-auto max-w-6xl px-4 py-8">{children}</main>
    </div>
  );
}
