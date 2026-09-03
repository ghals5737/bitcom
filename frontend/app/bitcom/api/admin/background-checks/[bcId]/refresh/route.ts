import { NextResponse } from "next/server";
import { refreshBgc } from "@/lib/mock/store";
import { handle, requireRole } from "@/lib/server/auth";

export async function POST(_req: Request, { params }: { params: Promise<{ bcId: string }> }) {
  return handle(async () => {
    const { employee: actor } = await requireRole("ADMIN");
    const { bcId } = await params;
    return NextResponse.json(refreshBgc(Number(bcId), actor.employeeId));
  });
}
