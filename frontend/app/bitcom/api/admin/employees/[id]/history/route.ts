import { NextResponse } from "next/server";
import { history } from "@/lib/mock/store";
import { handle, requireRole } from "@/lib/server/auth";

export async function GET(_req: Request, { params }: { params: Promise<{ id: string }> }) {
  return handle(async () => {
    await requireRole("ADMIN");
    const { id } = await params;
    return NextResponse.json(history(id));
  });
}
