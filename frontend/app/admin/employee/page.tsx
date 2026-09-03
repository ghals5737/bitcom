"use client";

import { Suspense, useCallback, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import Link from "next/link";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";
import { Separator } from "@/components/ui/separator";
import { BgcStatusBadge, EmployeeStatusBadge } from "@/components/status-badge";
import { api, ApiError, fmtDate, fmtDateTime } from "@/lib/client";
import type { AuditAction, BgcDetail, BgcSummary, EmployeeDetail, HistoryItem, Role } from "@/lib/types";

const ROLE_ITEMS = [{ value: "EMPLOYEE", label: "직원" }, { value: "ADMIN", label: "관리자" }];
const FIELD_LABEL: Record<string, string> = {
  name: "성명", birthDate: "생년월일", phone: "연락처", address: "주소", department: "부서", position: "직급", hireDate: "입사일", role: "역할",
};
const ACTION_LABEL: Record<AuditAction, string> = {
  EMPLOYEE_CREATED: "계정 생성", EMPLOYEE_UPDATED: "정보 수정", BGCHECK_REQUESTED: "Background Check 요청", BGCHECK_VIEWED: "Background Check 상세 열람",
  BGCHECK_DELETED: "Background Check 결과 삭제", EMPLOYEE_RESIGNED: "퇴사 처리", PASSWORD_RESET: "임시 비밀번호 재발급", ACCOUNT_LOCKED: "계정 잠금",
};

/** 정적 export 를 위해 /admin/employee?id=EMP-001 형태. useSearchParams 는 Suspense 경계가 필요하다. */
export default function EmployeeDetailPage() {
  return (
    <Suspense fallback={<Skeleton className="h-96" />}>
      <EmployeeDetail />
    </Suspense>
  );
}

function EmployeeDetail() {
  const id = useSearchParams().get("id") ?? "";
  const [emp, setEmp] = useState<EmployeeDetail | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setEmp(await api<EmployeeDetail>(`/admin/employees/${id}`));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "조회 실패");
    }
  }, [id]);

  useEffect(() => { void load(); }, [load]);

  if (error) return <Alert variant="destructive"><AlertTitle>조회 실패</AlertTitle><AlertDescription>{error} <Link href="/admin" className="underline">목록으로</Link></AlertDescription></Alert>;
  if (!emp) return <Skeleton className="h-96" />;

  return (
    <>
      <div className="mb-6 flex items-start justify-between">
        <div>
          <div className="mb-1 text-sm text-muted-foreground"><Link href="/admin" className="hover:underline">직원 목록</Link> / {emp.employeeId}</div>
          <h1 className="flex items-center gap-3 text-2xl font-semibold tracking-tight">
            {emp.name}
            <EmployeeStatusBadge status={emp.status} />
            {emp.role === "ADMIN" && <Badge>관리자</Badge>}
            {emp.locked && <Badge variant="destructive">잠김</Badge>}
            {emp.mustChangePassword && <Badge variant="outline">임시 비밀번호</Badge>}
          </h1>
          <p className="text-sm text-muted-foreground">{emp.department ?? "-"} · {emp.position ?? "-"} · 입사 {fmtDate(emp.hireDate)}{emp.resignedAt && ` · 퇴사 ${emp.resignedAt}`}</p>
        </div>
        <AdminActions emp={emp} onChanged={load} />
      </div>

      <Tabs defaultValue="info">
        <TabsList>
          <TabsTrigger value="info">기본정보</TabsTrigger>
          <TabsTrigger value="bgc">Background Check {emp.backgroundCheckSummary.hasPending && <span className="ml-1.5 inline-block h-2 w-2 animate-pulse rounded-full bg-amber-500" />}</TabsTrigger>
          <TabsTrigger value="history">이력</TabsTrigger>
        </TabsList>
        <TabsContent value="info" className="mt-4"><InfoTab emp={emp} onSaved={load} /></TabsContent>
        <TabsContent value="bgc" className="mt-4"><BgcTab emp={emp} onChanged={load} /></TabsContent>
        <TabsContent value="history" className="mt-4"><HistoryTab id={emp.employeeId} /></TabsContent>
      </Tabs>
    </>
  );
}

// ---------- 상단 액션: 퇴사 처리 / 임시 비밀번호 재발급 ----------
function AdminActions({ emp, onChanged }: { emp: EmployeeDetail; onChanged: () => Promise<void> }) {
  const [resignOpen, setResignOpen] = useState(false);
  const [resignedAt, setResignedAt] = useState(new Date().toISOString().slice(0, 10));
  const [temp, setTemp] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function resign() {
    setBusy(true);
    try {
      await api(`/admin/employees/${emp.employeeId}/resign`, { method: "POST", json: { resignedAt } });
      toast.success("퇴사 처리되었습니다. 세션이 종료되고 Background Check 결과가 삭제되었습니다.");
      setResignOpen(false);
      await onChanged();
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "퇴사 처리 실패");
    } finally {
      setBusy(false);
    }
  }

  async function reset() {
    setBusy(true);
    try {
      const r = await api<{ temporaryPassword: string }>(`/admin/employees/${emp.employeeId}/reset-password`, { method: "POST" });
      setTemp(r.temporaryPassword);
      await onChanged();
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "재발급 실패");
    } finally {
      setBusy(false);
    }
  }

  const resigned = emp.status === "RESIGNED";
  return (
    <div className="flex gap-2">
      <Button variant="outline" onClick={reset} disabled={busy || resigned}>{emp.locked ? "잠금 해제 (임시 비밀번호 재발급)" : "임시 비밀번호 재발급"}</Button>
      <Button variant="destructive" onClick={() => setResignOpen(true)} disabled={busy || resigned}>퇴사 처리</Button>

      <Dialog open={resignOpen} onOpenChange={setResignOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{emp.name} ({emp.employeeId}) 퇴사 처리</DialogTitle>
            <DialogDescription>처리 즉시 로그인이 차단되고 진행 중인 세션이 종료됩니다. Background Check 결과는 삭제되며 되돌릴 수 없습니다.</DialogDescription>
          </DialogHeader>
          <div className="space-y-1.5">
            <Label htmlFor="resignedAt">퇴사일 (기록용)</Label>
            <Input id="resignedAt" type="date" value={resignedAt} onChange={(e) => setResignedAt(e.target.value)} />
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setResignOpen(false)}>취소</Button>
            <Button variant="destructive" onClick={resign} disabled={busy}>퇴사 처리</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={!!temp} onOpenChange={(o) => !o && setTemp(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>임시 비밀번호가 재발급되었습니다</DialogTitle>
            <DialogDescription>잠금이 해제되고 기존 세션은 모두 종료되었습니다. 직원은 첫 로그인 시 비밀번호를 변경해야 합니다.</DialogDescription>
          </DialogHeader>
          <div className="rounded-md border bg-muted/40 p-4 text-center font-mono text-lg font-semibold select-all">{temp}</div>
          <DialogFooter>
            <Button variant="outline" onClick={() => { if (temp) { navigator.clipboard?.writeText(temp); toast.success("복사했습니다."); } }}>복사</Button>
            <Button onClick={() => setTemp(null)}>닫기</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

// ---------- 기본정보 탭 ----------
function InfoTab({ emp, onSaved }: { emp: EmployeeDetail; onSaved: () => Promise<void> }) {
  const [form, setForm] = useState({
    name: emp.name, birthDate: emp.birthDate ?? "", phone: emp.phone ?? "", address: emp.address ?? "",
    department: emp.department ?? "", position: emp.position ?? "", hireDate: emp.hireDate ?? "", role: emp.role as Role,
  });
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    setForm({ name: emp.name, birthDate: emp.birthDate ?? "", phone: emp.phone ?? "", address: emp.address ?? "",
      department: emp.department ?? "", position: emp.position ?? "", hireDate: emp.hireDate ?? "", role: emp.role });
  }, [emp]);

  const set = (k: keyof typeof form, v: string) => setForm((f) => ({ ...f, [k]: v }));
  const resigned = emp.status === "RESIGNED";

  async function save(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    try {
      await api(`/admin/employees/${emp.employeeId}`, { method: "PATCH", json: form });
      toast.success("저장되었습니다.");
      await onSaved();
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : "저장 실패");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="grid gap-6 lg:grid-cols-3">
      <Card className="lg:col-span-2">
        <CardHeader>
          <CardTitle>인적사항</CardTitle>
          <CardDescription>{resigned ? "퇴사 처리된 직원은 수정할 수 없습니다." : "관리자는 전체 항목을 수정할 수 있습니다. 변경 이력이 기록됩니다."}</CardDescription>
        </CardHeader>
        <CardContent>
          <form onSubmit={save} className="grid gap-4 sm:grid-cols-2">
            <div className="space-y-1.5"><Label>사번</Label><Input value={emp.employeeId} disabled /></div>
            <div className="space-y-1.5"><Label>성명</Label><Input value={form.name} onChange={(e) => set("name", e.target.value)} disabled={resigned} /></div>
            <div className="space-y-1.5"><Label>생년월일</Label><Input type="date" value={form.birthDate} onChange={(e) => set("birthDate", e.target.value)} disabled={resigned} />
              {!form.birthDate && <p className="text-xs text-amber-600">확인되지 않음. Background Check를 요청할 수 없습니다.</p>}</div>
            <div className="space-y-1.5"><Label>연락처</Label><Input value={form.phone} onChange={(e) => set("phone", e.target.value)} disabled={resigned} /></div>
            <div className="space-y-1.5"><Label>주소</Label><Input value={form.address} onChange={(e) => set("address", e.target.value)} disabled={resigned} /></div>
            <div className="space-y-1.5"><Label>부서</Label><Input value={form.department} onChange={(e) => set("department", e.target.value)} disabled={resigned} /></div>
            <div className="space-y-1.5"><Label>직급</Label><Input value={form.position} onChange={(e) => set("position", e.target.value)} disabled={resigned} /></div>
            <div className="space-y-1.5"><Label>입사일</Label><Input type="date" value={form.hireDate} onChange={(e) => set("hireDate", e.target.value)} disabled={resigned} /></div>
            <div className="space-y-1.5"><Label>역할</Label>
              <Select items={ROLE_ITEMS} value={form.role} onValueChange={(v) => set("role", v ?? "EMPLOYEE")} disabled={resigned}>
                <SelectTrigger><SelectValue /></SelectTrigger>
                <SelectContent><SelectItem value="EMPLOYEE">직원</SelectItem><SelectItem value="ADMIN">관리자</SelectItem></SelectContent>
              </Select></div>
            <div className="flex items-end justify-end sm:col-span-2">
              <Button type="submit" disabled={busy || resigned}>{busy ? "저장 중..." : "저장"}</Button>
            </div>
          </form>
        </CardContent>
      </Card>

      <Card>
        <CardHeader><CardTitle className="text-base">계정 상태</CardTitle></CardHeader>
        <CardContent>
          <dl className="space-y-2.5 text-sm">
            <Row k="재직 상태" v={<EmployeeStatusBadge status={emp.status} />} />
            <Row k="퇴사일" v={emp.resignedAt ?? "-"} />
            <Row k="잠금" v={emp.locked ? <Badge variant="destructive">잠김</Badge> : "정상"} />
            <Row k="로그인 실패" v={`${emp.failedLoginCount}회`} />
            <Row k="비밀번호" v={emp.mustChangePassword ? "임시 (변경 필요)" : "설정됨"} />
            <Separator />
            <Row k="외부 API lastName" v={<code className="text-xs">{emp.lastName}</code>} />
            <Row k="외부 API firstName" v={<code className="text-xs">{emp.firstName}</code>} />
            <Separator />
            <Row k="생성" v={fmtDateTime(emp.createdAt)} />
            <Row k="최근 수정" v={fmtDateTime(emp.updatedAt)} />
          </dl>
        </CardContent>
      </Card>
    </div>
  );
}

function Row({ k, v }: { k: string; v: React.ReactNode }) {
  return <div className="flex items-center justify-between gap-4"><dt className="text-muted-foreground">{k}</dt><dd className="text-right">{v}</dd></div>;
}

// ---------- Background Check 탭 ----------
function BgcTab({ emp, onChanged }: { emp: EmployeeDetail; onChanged: () => Promise<void> }) {
  const [list, setList] = useState<BgcSummary[] | null>(null);
  const [detail, setDetail] = useState<BgcDetail | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try { setList(await api<BgcSummary[]>(`/admin/employees/${emp.employeeId}/background-checks`)); }
    catch (e) { toast.error(e instanceof ApiError ? e.message : "이력 조회 실패"); }
  }, [emp.employeeId]);

  useEffect(() => { void load(); }, [load]);

  // PENDING 건이 있으면 5초마다 재조회 (화면은 DB 상태만 본다)
  const hasPending = list?.some((b) => b.status === "PENDING") ?? false;
  useEffect(() => {
    if (!hasPending) return;
    const t = setInterval(async () => { await load(); }, 5000);
    return () => clearInterval(t);
  }, [hasPending, load]);
  useEffect(() => { if (!hasPending && list) void onChanged(); /* 목록 배지 갱신 */ // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [hasPending]);

  const resigned = emp.status === "RESIGNED";
  const noDob = !emp.birthDate;
  const disabledReason = resigned ? "퇴사 처리된 직원은 조회할 수 없습니다." : noDob ? "생년월일이 확인되지 않아 요청할 수 없습니다. (외부 API 필수 항목)" : hasPending ? "진행 중인 조회가 끝난 뒤 다시 요청할 수 있습니다." : null;

  async function request() {
    setBusy(true);
    try {
      const b = await api<BgcSummary>(`/admin/employees/${emp.employeeId}/background-checks`, { method: "POST" });
      toast[b.status === "FAILED" ? "error" : "success"](b.status === "FAILED" ? `요청 실패: ${b.failureReason}` : b.status === "PENDING" ? "요청되었습니다. 결과가 나오면 자동으로 갱신됩니다." : `즉시 완료: ${b.status}`);
      await load();
      await onChanged();
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "요청 실패");
    } finally {
      setBusy(false);
    }
  }

  async function openDetail(id: number) {
    try { setDetail(await api<BgcDetail>(`/admin/background-checks/${id}`)); }
    catch (e) { toast.error(e instanceof ApiError ? e.message : "상세 조회 실패"); }
  }

  async function refresh(id: number) {
    setBusy(true);
    try {
      const b = await api<BgcSummary>(`/admin/background-checks/${id}/refresh`, { method: "POST" });
      toast[b.status === "TIMEOUT" ? "warning" : "success"](b.status === "TIMEOUT" ? `아직 결과를 받지 못했습니다: ${b.failureReason}` : `결과 확인: ${b.status}`);
      await load();
      await onChanged();
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "재확인 실패");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card>
      <CardHeader className="flex-row items-start justify-between space-y-0">
        <div>
          <CardTitle>Background Check</CardTitle>
          <CardDescription>관리자만 열람할 수 있습니다. 상세를 열면 열람 기록이 남습니다.</CardDescription>
        </div>
        <div className="text-right">
          <Button onClick={request} disabled={busy || !!disabledReason}>{busy ? "요청 중..." : "새로 요청"}</Button>
          {disabledReason && <p className="mt-1 max-w-xs text-xs text-muted-foreground">{disabledReason}</p>}
        </div>
      </CardHeader>
      <CardContent>
        {resigned && <Alert className="mb-4"><AlertDescription>퇴사 처리 시 Background Check 결과가 삭제되었습니다. 삭제 기록은 이력 탭에서 확인할 수 있습니다.</AlertDescription></Alert>}
        {!list ? <Skeleton className="h-32" /> : list.length === 0 ? (
          <p className="py-8 text-center text-sm text-muted-foreground">조회 이력이 없습니다.</p>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>상태</TableHead><TableHead>checkId</TableHead><TableHead>요청</TableHead><TableHead>완료</TableHead><TableHead>폴링</TableHead><TableHead>비고</TableHead><TableHead className="text-right">동작</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {list.map((b) => (
                <TableRow key={b.id}>
                  <TableCell><BgcStatusBadge status={b.status} /></TableCell>
                  <TableCell className="font-mono text-xs">{b.checkId ?? "-"}</TableCell>
                  <TableCell className="text-xs">{fmtDateTime(b.requestedAt)}<br /><span className="text-muted-foreground">{b.requestedBy}</span></TableCell>
                  <TableCell className="text-xs">{fmtDateTime(b.completedAt)}</TableCell>
                  <TableCell className="text-xs">{b.pollCount}회</TableCell>
                  <TableCell className="max-w-56 text-xs text-muted-foreground">{b.failureReason ?? ""}</TableCell>
                  <TableCell className="text-right">
                    {(b.status === "CLEAR" || b.status === "FLAGGED") && <Button size="sm" variant="outline" onClick={() => openDetail(b.id)}>상세 보기</Button>}
                    {b.status === "TIMEOUT" && <Button size="sm" variant="outline" onClick={() => refresh(b.id)} disabled={busy}>결과 다시 확인</Button>}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </CardContent>

      <Dialog open={!!detail} onOpenChange={(o) => !o && setDetail(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">조회 결과 <BgcStatusBadge status={detail?.status ?? null} /></DialogTitle>
            <DialogDescription>민감정보입니다. 이 열람은 감사 로그에 기록되었습니다.</DialogDescription>
          </DialogHeader>
          {detail && (
            <dl className="space-y-2 text-sm">
              <Row k="checkId" v={<code className="text-xs">{detail.checkId}</code>} />
              <Separator />
              <Row k="범죄 기록" v={detail.criminalRecord ? <Badge variant="destructive">있음</Badge> : <Badge variant="secondary">없음</Badge>} />
              <Row k="학력 검증" v={detail.educationVerified ? "확인됨" : <span className="text-red-600">미확인</span>} />
              <Row k="경력 검증" v={detail.employmentVerified ? "확인됨" : <span className="text-red-600">미확인</span>} />
              <Row k="신용 등급" v={<span className="uppercase">{detail.creditScore ?? "-"}</span>} />
              <Separator />
              <Row k="요청 값" v={<code className="text-xs">{detail.requestPayload ? `${detail.requestPayload.lastName} / ${detail.requestPayload.firstName} / ${detail.requestPayload.dateOfBirth}` : "-"}</code>} />
              <Row k="요청" v={fmtDateTime(detail.requestedAt)} />
              <Row k="완료" v={fmtDateTime(detail.completedAt)} />
            </dl>
          )}
          <DialogFooter><Button onClick={() => setDetail(null)}>닫기</Button></DialogFooter>
        </DialogContent>
      </Dialog>
    </Card>
  );
}

// ---------- 이력 탭 ----------
function HistoryTab({ id }: { id: string }) {
  const [items, setItems] = useState<HistoryItem[] | null>(null);
  useEffect(() => {
    api<HistoryItem[]>(`/admin/employees/${id}/history`).then(setItems).catch((e) => toast.error(e instanceof ApiError ? e.message : "이력 조회 실패"));
  }, [id]);

  return (
    <Card>
      <CardHeader>
        <CardTitle>이력</CardTitle>
        <CardDescription>정보 변경 이력과 감사 로그(요청·열람·삭제·퇴사·재발급)를 시간순으로 표시합니다.</CardDescription>
      </CardHeader>
      <CardContent>
        {!items ? <Skeleton className="h-32" /> : items.length === 0 ? (
          <p className="py-8 text-center text-sm text-muted-foreground">기록이 없습니다.</p>
        ) : (
          <Table>
            <TableHeader><TableRow><TableHead className="w-44">일시</TableHead><TableHead className="w-28">행위자</TableHead><TableHead className="w-44">구분</TableHead><TableHead>내용</TableHead></TableRow></TableHeader>
            <TableBody>
              {items.map((it) => (
                <TableRow key={`${it.kind}-${it.id}`}>
                  <TableCell className="text-xs">{fmtDateTime(it.at)}</TableCell>
                  <TableCell className="font-mono text-xs">{it.actor}</TableCell>
                  <TableCell>{it.kind === "change" ? <Badge variant="outline">변경 · {FIELD_LABEL[it.field] ?? it.field}</Badge> : <Badge variant={it.action === "BGCHECK_VIEWED" || it.action === "BGCHECK_DELETED" ? "destructive" : "secondary"}>{ACTION_LABEL[it.action]}</Badge>}</TableCell>
                  <TableCell className="text-sm">
                    {it.kind === "change"
                      ? <><span className="text-muted-foreground line-through">{it.oldValue ?? "(없음)"}</span> → <span>{it.newValue ?? "(없음)"}</span></>
                      : <code className="text-xs text-muted-foreground">{JSON.stringify(it.detail)}</code>}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </CardContent>
    </Card>
  );
}
