import { NextResponse } from "next/server";
import { listBgc, requestBgc } from "@/lib/mock/store";
import { handle, requireRole } from "@/lib/server/auth";

type Ctx = { params: Promise<{ id: string }> };

export async function GET(_req: Request, { params }: Ctx) {
  return handle(async () => {
    await requireRole("ADMIN");
    const { id } = await params;
    return NextResponse.json(listBgc(id));
  });
}

export async function POST(_req: Request, { params }: Ctx) {
  return handle(async () => {
    const { employee: actor } = await requireRole("ADMIN");
    const { id } = await params;
    return NextResponse.json(requestBgc(id, actor.employeeId), { status: 201 });
  });
}
