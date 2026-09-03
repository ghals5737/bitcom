"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { toast } from "sonner";
import { AppShell } from "@/components/app-shell";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { Separator } from "@/components/ui/separator";
import { api, ApiError, fmtDate } from "@/lib/client";
import { useMe } from "@/hooks/use-me";
import type { MeProfile } from "@/lib/types";

export default function MePage() {
  const { me, loading } = useMe();
  const [profile, setProfile] = useState<MeProfile | null>(null);
  const [phone, setPhone] = useState("");
  const [address, setAddress] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!me) return;
    api<MeProfile>("/me").then((p) => {
      setProfile(p);
      setPhone(p.phone ?? "");
      setAddress(p.address ?? "");
    }).catch((e) => toast.error(e instanceof ApiError ? e.message : "조회 실패"));
  }, [me]);

  if (loading || !me) return null;

  const dirty = profile && (phone !== (profile.phone ?? "") || address !== (profile.address ?? ""));

  async function save(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    try {
      const p = await api<MeProfile>("/me", { method: "PATCH", json: { phone, address } });
      setProfile(p);
      toast.success("저장되었습니다. 변경 이력이 기록됩니다.");
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : "저장 실패");
    } finally {
      setBusy(false);
    }
  }

  const readonlyRows: [string, string][] = profile
    ? [
        ["사번", profile.employeeId], ["성명", profile.name], ["생년월일", profile.birthDate ?? "확인되지 않음"],
        ["부서", profile.department ?? "-"], ["직급", profile.position ?? "-"], ["입사일", fmtDate(profile.hireDate)],
      ]
    : [];

  return (
    <AppShell me={me}>
      <div className="mb-6 flex items-end justify-between">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">내 정보</h1>
          <p className="text-sm text-muted-foreground">인사 정보는 관리자만 수정할 수 있습니다. 연락처와 주소는 직접 수정할 수 있습니다.</p>
        </div>
        <Button variant="outline" nativeButton={false} render={<Link href="/change-password" />}>비밀번호 변경</Button>
      </div>

      <div className="grid gap-6 md:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle>기본 정보</CardTitle>
            <CardDescription>읽기 전용</CardDescription>
          </CardHeader>
          <CardContent>
            {!profile ? <Skeleton className="h-40" /> : (
              <dl className="divide-y">
                {readonlyRows.map(([k, v]) => (
                  <div key={k} className="grid grid-cols-3 py-2.5 text-sm">
                    <dt className="text-muted-foreground">{k}</dt>
                    <dd className="col-span-2">{v}</dd>
                  </div>
                ))}
              </dl>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>연락 정보</CardTitle>
            <CardDescription>즉시 반영되며 변경 이력이 저장됩니다.</CardDescription>
          </CardHeader>
          <CardContent>
            {!profile ? <Skeleton className="h-40" /> : (
              <form onSubmit={save} className="space-y-4">
                <div className="space-y-1.5">
                  <Label htmlFor="phone">연락처</Label>
                  <Input id="phone" value={phone} onChange={(e) => setPhone(e.target.value)} placeholder="010-0000-0000" />
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="address">주소</Label>
                  <Input id="address" value={address} onChange={(e) => setAddress(e.target.value)} />
                </div>
                <Separator />
                <div className="flex justify-end gap-2">
                  <Button type="button" variant="ghost" disabled={!dirty || busy} onClick={() => { setPhone(profile.phone ?? ""); setAddress(profile.address ?? ""); }}>되돌리기</Button>
                  <Button type="submit" disabled={!dirty || busy}>{busy ? "저장 중..." : "저장"}</Button>
                </div>
              </form>
            )}
          </CardContent>
        </Card>
      </div>
    </AppShell>
  );
}
