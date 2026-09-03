import { NextResponse } from "next/server";
import { toMeProfile, updateMe } from "@/lib/mock/store";
import { handle, readJson, requireUser } from "@/lib/server/auth";

export async function GET() {
  return handle(async () => {
    const { employee } = await requireUser();
    return NextResponse.json(toMeProfile(employee));
  });
}

export async function PATCH(req: Request) {
  return handle(async () => {
    const { employee } = await requireUser();
    const patch = await readJson<Record<string, unknown>>(req);
    return NextResponse.json(updateMe(employee, patch));
  });
}
