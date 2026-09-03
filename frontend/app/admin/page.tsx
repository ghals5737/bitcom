"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Badge } from "@/components/ui/badge";
import { BgcStatusBadge, EmployeeStatusBadge } from "@/components/status-badge";
import { api, ApiError, fmtDate } from "@/lib/client";
import type { EmployeeListItem } from "@/lib/types";
import { cn } from "@/lib/utils";

type Filter = "ALL" | "ACTIVE" | "RESIGNED";

export default function AdminEmployeesPage() {
  const router = useRouter();
  const [filter, setFilter] = useState<Filter>("ALL");
  const [q, setQ] = useState("");
  const [rows, setRows] = useState<EmployeeListItem[] | null>(null);

  const load = useCallback(async () => {
    try {
      setRows(await api<EmployeeListItem[]>(`/admin/employees?status=${filter}`));
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "목록 조회 실패");
    }
  }, [filter]);

  useEffect(() => { void load(); }, [load]);

  const filtered = (rows ?? []).filter((r) => {
    const s = q.trim().toLowerCase();
    return !s || r.employeeId.toLowerCase().includes(s) || r.name.includes(s) || (r.department ?? "").includes(s);
  });
  const counts = rows ? { total: rows.length, pending: rows.filter((r) => r.latestBgcStatus === "PENDING").length } : null;

  return (
    <>
      <div className="mb-6 flex items-end justify-between">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">직원 목록</h1>
          <p className="text-sm text-muted-foreground">전체 직원을 표시합니다. 재직 상태로 걸러볼 수 있습니다.</p>
        </div>
        <Button nativeButton={false} render={<Link href="/admin/employees/new" />}>계정 생성</Button>
      </div>

      <Card>
        <CardContent className="pt-6">
          <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
            <Tabs value={filter} onValueChange={(v) => setFilter(v as Filter)}>
              <TabsList>
                <TabsTrigger value="ALL">전체</TabsTrigger>
                <TabsTrigger value="ACTIVE">재직</TabsTrigger>
                <TabsTrigger value="RESIGNED">퇴사</TabsTrigger>
              </TabsList>
            </Tabs>
            <div className="flex items-center gap-3">
              {counts && counts.pending > 0 && <Badge variant="outline" className="border-amber-500 text-amber-700">Background Check 진행 중 {counts.pending}</Badge>}
              <Input placeholder="사번 · 성명 · 부서 검색" value={q} onChange={(e) => setQ(e.target.value)} className="w-64" />
            </div>
          </div>

          {!rows ? <Skeleton className="h-64" /> : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="w-28">사번</TableHead>
                  <TableHead>성명</TableHead>
                  <TableHead>부서</TableHead>
                  <TableHead>직급</TableHead>
                  <TableHead>입사일</TableHead>
                  <TableHead>역할</TableHead>
                  <TableHead>상태</TableHead>
                  <TableHead>Background Check</TableHead>
                  <TableHead className="w-20" />
                </TableRow>
              </TableHeader>
              <TableBody>
                {filtered.length === 0 && (
                  <TableRow><TableCell colSpan={9} className="py-10 text-center text-muted-foreground">해당하는 직원이 없습니다.</TableCell></TableRow>
                )}
                {filtered.map((r) => (
                  <TableRow
                    key={r.employeeId}
                    role="link"
                    tabIndex={0}
                    onClick={() => router.push(`/admin/employee?id=${r.employeeId}`)}
                    onKeyDown={(e) => { if (e.key === "Enter") router.push(`/admin/employee?id=${r.employeeId}`); }}
                    className={cn("cursor-pointer", r.status === "RESIGNED" && "text-muted-foreground")}
                  >
                    <TableCell className="font-mono text-xs">{r.employeeId}</TableCell>
                    <TableCell className="font-medium text-foreground">{r.name}</TableCell>
                    <TableCell>{r.department ?? "-"}</TableCell>
                    <TableCell>{r.position ?? "-"}</TableCell>
                    <TableCell>{fmtDate(r.hireDate)}</TableCell>
                    <TableCell>{r.role === "ADMIN" ? <Badge>관리자</Badge> : <span className="text-sm">직원</span>}</TableCell>
                    <TableCell><EmployeeStatusBadge status={r.status} /></TableCell>
                    <TableCell><BgcStatusBadge status={r.latestBgcStatus} /></TableCell>
                    <TableCell className="text-right">
                      <Button variant="ghost" size="sm" nativeButton={false} render={<Link href={`/admin/employee?id=${r.employeeId}`} onClick={(e) => e.stopPropagation()} />}>상세</Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
          {counts && <p className="mt-3 text-xs text-muted-foreground">{filtered.length} / {counts.total}명 표시</p>}
        </CardContent>
      </Card>
    </>
  );
}
