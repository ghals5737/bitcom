import { NextResponse } from "next/server";
import { adminUpdateEmployee, getEmployeeDetail } from "@/lib/mock/store";
import { handle, readJson, requireRole } from "@/lib/server/auth";

type Ctx = { params: Promise<{ id: string }> };

export async function GET(_req: Request, { params }: Ctx) {
  return handle(async () => {
    await requireRole("ADMIN");
    const { id } = await params;
    return NextResponse.json(getEmployeeDetail(id));
  });
}

export async function PATCH(req: Request, { params }: Ctx) {
  return handle(async () => {
    const { employee: actor } = await requireRole("ADMIN");
    const { id } = await params;
    const patch = await readJson<Record<string, unknown>>(req);
    return NextResponse.json(adminUpdateEmployee(id, patch, actor.employeeId));
  });
}
