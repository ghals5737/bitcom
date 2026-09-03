import { NextResponse } from "next/server";
import { resetPassword } from "@/lib/mock/store";
import { handle, requireRole } from "@/lib/server/auth";

export async function POST(_req: Request, { params }: { params: Promise<{ id: string }> }) {
  return handle(async () => {
    const { employee: actor } = await requireRole("ADMIN");
    const { id } = await params;
    return NextResponse.json(resetPassword(id, actor.employeeId));
  });
}
