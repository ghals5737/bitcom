import { Badge } from "@/components/ui/badge";
import type { BgcStatus, EmployeeStatus } from "@/lib/types";
import { cn } from "@/lib/utils";

export function EmployeeStatusBadge({ status }: { status: EmployeeStatus }) {
  return status === "ACTIVE"
    ? <Badge className="bg-emerald-600 hover:bg-emerald-600">재직</Badge>
    : <Badge variant="secondary" className="text-muted-foreground">퇴사</Badge>;
}

const BGC_STYLE: Record<BgcStatus, { label: string; className: string }> = {
  PENDING: { label: "진행 중", className: "bg-amber-500 text-white hover:bg-amber-500" },
  CLEAR: { label: "CLEAR", className: "bg-emerald-600 text-white hover:bg-emerald-600" },
  FLAGGED: { label: "FLAGGED", className: "bg-red-600 text-white hover:bg-red-600" },
  FAILED: { label: "실패", className: "bg-zinc-500 text-white hover:bg-zinc-500" },
  TIMEOUT: { label: "시간 초과", className: "bg-orange-600 text-white hover:bg-orange-600" },
};

export function BgcStatusBadge({ status }: { status: BgcStatus | null }) {
  if (!status) return <span className="text-xs text-muted-foreground">없음</span>;
  const s = BGC_STYLE[status];
  return <Badge className={cn(s.className, status === "PENDING" && "animate-pulse")}>{s.label}</Badge>;
}
