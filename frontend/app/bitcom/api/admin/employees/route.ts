import { NextResponse } from "next/server";
import { createEmployee, listEmployees } from "@/lib/mock/store";
import { handle, readJson, requireRole } from "@/lib/server/auth";
import type { CreateEmployeeInput } from "@/lib/types";

export async function GET(req: Request) {
  return handle(async () => {
    await requireRole("ADMIN");
    const status = new URL(req.url).searchParams.get("status") ?? undefined;
    return NextResponse.json(listEmployees(status));
  });
}

export async function POST(req: Request) {
  return handle(async () => {
    const { employee: actor } = await requireRole("ADMIN");
    const input = await readJson<CreateEmployeeInput>(req);
    return NextResponse.json(createEmployee(input, actor.employeeId), { status: 201 });
  });
}
