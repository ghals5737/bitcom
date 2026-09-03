"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { api, ApiError } from "@/lib/client";
import type { CreateEmployeeInput, CreateEmployeeResult, Role } from "@/lib/types";

const ROLE_ITEMS = [{ value: "EMPLOYEE", label: "직원" }, { value: "ADMIN", label: "관리자" }];
const empty: CreateEmployeeInput = { name: "", birthDate: "", phone: "", address: "", department: "", position: "", hireDate: "", role: "EMPLOYEE" };

export default function NewEmployeePage() {
  const router = useRouter();
  const [form, setForm] = useState<CreateEmployeeInput>(empty);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<CreateEmployeeResult | null>(null);

  const set = <K extends keyof CreateEmployeeInput>(k: K, v: CreateEmployeeInput[K]) => setForm((f) => ({ ...f, [k]: v }));

  // 화면 미리보기용. 실제 파싱은 백엔드(목업 store)가 한다.
  const name = form.name.trim();
  const preview = name.length >= 2 ? { lastName: name[0], firstName: name.slice(1) } : null;

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const r = await api<CreateEmployeeResult>("/admin/employees", { method: "POST", json: form });
      setResult(r);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "생성 실패");
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <div className="mb-6">
        <h1 className="text-2xl font-semibold tracking-tight">직원 계정 생성</h1>
        <p className="text-sm text-muted-foreground">사번은 자동 채번됩니다. 임시 비밀번호는 생성 직후 한 번만 표시됩니다.</p>
      </div>

      <form onSubmit={submit} className="grid gap-6 lg:grid-cols-3">
        <Card className="lg:col-span-2">
          <CardHeader>
            <CardTitle>인적사항</CardTitle>
            <CardDescription>성명과 생년월일은 Background Check 입력값으로 쓰입니다.</CardDescription>
          </CardHeader>
          <CardContent className="grid gap-4 sm:grid-cols-2">
            <div className="space-y-1.5">
              <Label htmlFor="name">성명 *</Label>
              <Input id="name" value={form.name} onChange={(e) => set("name", e.target.value)} placeholder="홍길동" required autoFocus />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="birthDate">생년월일</Label>
              <Input id="birthDate" type="date" value={form.birthDate ?? ""} onChange={(e) => set("birthDate", e.target.value)} />
              <p className="text-xs text-muted-foreground">비워두면 계정은 생성되지만 Background Check는 요청할 수 없습니다.</p>
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="phone">연락처</Label>
              <Input id="phone" value={form.phone ?? ""} onChange={(e) => set("phone", e.target.value)} placeholder="010-0000-0000" />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="address">주소</Label>
              <Input id="address" value={form.address ?? ""} onChange={(e) => set("address", e.target.value)} />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="department">부서</Label>
              <Input id="department" value={form.department ?? ""} onChange={(e) => set("department", e.target.value)} />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="position">직급</Label>
              <Input id="position" value={form.position ?? ""} onChange={(e) => set("position", e.target.value)} />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="hireDate">입사일</Label>
              <Input id="hireDate" type="date" value={form.hireDate ?? ""} onChange={(e) => set("hireDate", e.target.value)} />
            </div>
            <div className="space-y-1.5">
              <Label>역할</Label>
              <Select items={ROLE_ITEMS} value={form.role} onValueChange={(v) => set("role", (v ?? "EMPLOYEE") as Role)}>
                <SelectTrigger><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value="EMPLOYEE">직원</SelectItem>
                  <SelectItem value="ADMIN">관리자</SelectItem>
                </SelectContent>
              </Select>
            </div>
          </CardContent>
        </Card>

        <div className="space-y-6">
          <Card>
            <CardHeader>
              <CardTitle className="text-base">외부 API 전달 형식</CardTitle>
              <CardDescription>첫 글자를 성(lastName), 나머지를 이름(firstName)으로 보냅니다.</CardDescription>
            </CardHeader>
            <CardContent className="text-sm">
              {preview ? (
                <dl className="space-y-1 font-mono text-xs">
                  <div className="flex justify-between"><dt className="text-muted-foreground">lastName</dt><dd>{preview.lastName}</dd></div>
                  <div className="flex justify-between"><dt className="text-muted-foreground">firstName</dt><dd>{preview.firstName}</dd></div>
                  <div className="flex justify-between"><dt className="text-muted-foreground">dateOfBirth</dt><dd>{form.birthDate || <span className="text-amber-600">없음</span>}</dd></div>
                </dl>
              ) : <p className="text-muted-foreground">성명을 입력하면 미리보기가 표시됩니다.</p>}
            </CardContent>
          </Card>
          {error && <Alert variant="destructive"><AlertDescription>{error}</AlertDescription></Alert>}
          <div className="flex gap-2">
            <Button type="submit" className="flex-1" disabled={busy}>{busy ? "생성 중..." : "계정 생성"}</Button>
            <Button type="button" variant="outline" nativeButton={false} render={<Link href="/admin" />}>취소</Button>
          </div>
        </div>
      </form>

      <Dialog open={!!result} onOpenChange={(o) => { if (!o && result) router.push(`/admin/employees/${result.employee.employeeId}`); }}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>계정이 생성되었습니다</DialogTitle>
            <DialogDescription>아래 임시 비밀번호를 직원에게 전달하세요. 이 창을 닫으면 다시 볼 수 없습니다.</DialogDescription>
          </DialogHeader>
          {result && (
            <div className="space-y-3 rounded-md border bg-muted/40 p-4 font-mono text-sm">
              <div className="flex justify-between"><span className="text-muted-foreground">사번</span><span>{result.employee.employeeId}</span></div>
              <div className="flex justify-between"><span className="text-muted-foreground">성명</span><span>{result.employee.name}</span></div>
              <div className="flex justify-between"><span className="text-muted-foreground">임시 비밀번호</span><span className="select-all font-semibold">{result.temporaryPassword}</span></div>
            </div>
          )}
          <DialogFooter>
            <Button variant="outline" onClick={() => { if (result) { navigator.clipboard?.writeText(result.temporaryPassword); toast.success("임시 비밀번호를 복사했습니다."); } }}>복사</Button>
            <Button onClick={() => result && router.push(`/admin/employees/${result.employee.employeeId}`)}>상세로 이동</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}
