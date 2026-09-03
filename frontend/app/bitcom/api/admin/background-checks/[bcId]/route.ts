import { NextResponse } from "next/server";
import { getBgcDetail } from "@/lib/mock/store";
import { handle, requireRole } from "@/lib/server/auth";

export async function GET(_req: Request, { params }: { params: Promise<{ bcId: string }> }) {
  return handle(async () => {
    const { employee: actor } = await requireRole("ADMIN");
    const { bcId } = await params;
    return NextResponse.json(getBgcDetail(Number(bcId), actor.employeeId));
  });
}
