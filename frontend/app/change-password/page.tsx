"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { api, ApiError } from "@/lib/client";
import { homeFor, useMe } from "@/hooks/use-me";
import type { MeSummary } from "@/lib/types";

export default function ChangePasswordPage() {
  const router = useRouter();
  const { me, loading } = useMe({ allowMustChange: true });
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  if (loading || !me) return null;

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (newPassword !== confirm) return setError("새 비밀번호 확인이 일치하지 않습니다.");
    setBusy(true);
    setError(null);
    try {
      const m = await api<MeSummary>("/auth/change-password", { method: "POST", json: { currentPassword, newPassword } });
      router.replace(homeFor(m.role));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "변경 중 오류가 발생했습니다.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-muted/30 px-4">
      <Card className="w-full max-w-sm">
        <CardHeader>
          <CardTitle className="text-xl">비밀번호 변경</CardTitle>
          <CardDescription>
            {me.mustChangePassword
              ? "임시 비밀번호로 로그인했습니다. 계속하려면 새 비밀번호를 설정하세요."
              : "새 비밀번호를 설정합니다."}
          </CardDescription>
        </CardHeader>
        <CardContent>
          <form onSubmit={submit} className="space-y-4">
            <div className="space-y-1.5">
              <Label htmlFor="cur">{me.mustChangePassword ? "임시 비밀번호" : "현재 비밀번호"}</Label>
              <Input id="cur" type="password" value={currentPassword} onChange={(e) => setCurrentPassword(e.target.value)} required autoFocus />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="new">새 비밀번호</Label>
              <Input id="new" type="password" value={newPassword} onChange={(e) => setNewPassword(e.target.value)} required />
              <p className="text-xs text-muted-foreground">8자 이상, 숫자와 특수문자 포함. 임시 비밀번호와 달라야 합니다.</p>
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="confirm">새 비밀번호 확인</Label>
              <Input id="confirm" type="password" value={confirm} onChange={(e) => setConfirm(e.target.value)} required />
            </div>
            {error && <Alert variant="destructive"><AlertDescription>{error}</AlertDescription></Alert>}
            <Button type="submit" className="w-full" disabled={busy}>{busy ? "변경 중..." : "변경하고 계속"}</Button>
            {!me.mustChangePassword && (
              <Button type="button" variant="ghost" className="w-full" onClick={() => router.back()}>취소</Button>
            )}
          </form>
        </CardContent>
      </Card>
    </div>
  );
}
