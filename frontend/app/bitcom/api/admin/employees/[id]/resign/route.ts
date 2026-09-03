import { NextResponse } from "next/server";
import { resign } from "@/lib/mock/store";
import { handle, readJson, requireRole } from "@/lib/server/auth";

export async function POST(req: Request, { params }: { params: Promise<{ id: string }> }) {
  return handle(async () => {
    const { employee: actor } = await requireRole("ADMIN");
    const { id } = await params;
    const body = await readJson<{ resignedAt?: string | null }>(req).catch(() => ({ resignedAt: null }));
    return NextResponse.json(resign(id, body.resignedAt ?? null, actor.employeeId));
  });
}
