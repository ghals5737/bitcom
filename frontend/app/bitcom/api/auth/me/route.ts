import { NextResponse } from "next/server";
import { toMeSummary } from "@/lib/mock/store";
import { handle, requireUser } from "@/lib/server/auth";

export async function GET() {
  return handle(async () => {
    const { employee } = await requireUser({ allowMustChange: true });
    return NextResponse.json(toMeSummary(employee));
  });
}
