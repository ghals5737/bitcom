"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { api, ApiError } from "@/lib/client";
import { homeFor } from "@/hooks/use-me";
import type { MeSummary } from "@/lib/types";

export default function LoginPage() {
  const router = useRouter();
  const [employeeId, setEmployeeId] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<{ code: string; message: string } | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const me = await api<MeSummary>("/auth/login", { method: "POST", json: { employeeId, password } });
      router.replace(me.mustChangePassword ? "/change-password" : homeFor(me.role));
    } catch (err) {
      if (err instanceof ApiError) setError({ code: err.code, message: err.message });
      else setError({ code: "ERROR", message: "로그인 중 오류가 발생했습니다." });
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-muted/30 px-4">
      <Card className="w-full max-w-sm">
        <CardHeader>
          <CardTitle className="text-xl">비트컴퓨터 직원 포털</CardTitle>
          <CardDescription>사번과 비밀번호로 로그인하세요.</CardDescription>
        </CardHeader>
        <CardContent>
          <form onSubmit={submit} className="space-y-4">
            <div className="space-y-1.5">
              <Label htmlFor="employeeId">사번</Label>
              <Input id="employeeId" placeholder="EMP-001" value={employeeId} onChange={(e) => setEmployeeId(e.target.value)} autoComplete="username" autoFocus required />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="password">비밀번호</Label>
              <Input id="password" type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" required />
            </div>
            {error && (
              <Alert variant="destructive">
                <AlertTitle>{error.code === "RESIGNED" ? "접근 불가" : error.code === "LOCKED" ? "계정 잠김" : "로그인 실패"}</AlertTitle>
                <AlertDescription>{error.message}</AlertDescription>
              </Alert>
            )}
            <Button type="submit" className="w-full" disabled={busy}>{busy ? "확인 중..." : "로그인"}</Button>
          </form>
          <div className="mt-6 rounded-md border bg-muted/40 p-3 text-xs text-muted-foreground">
            <p className="mb-1 font-medium text-foreground">평가용 계정 (목업)</p>
            <p>관리자: ADMIN-001 / admin1234!</p>
            <p>직원: EMP-001 / emp1234!</p>
            <p className="mt-1">그 외 시드 직원은 임시 비밀번호 <code>Temp-EMP-00N!1</code> (첫 로그인 시 변경 강제)</p>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
