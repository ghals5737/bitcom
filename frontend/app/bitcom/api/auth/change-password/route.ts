import { NextResponse } from "next/server";
import { changePassword, toMeSummary } from "@/lib/mock/store";
import { handle, readJson, requireUser } from "@/lib/server/auth";

export async function POST(req: Request) {
  return handle(async () => {
    const { employee } = await requireUser({ allowMustChange: true });
    const { currentPassword, newPassword } = await readJson<{ currentPassword?: string; newPassword?: string }>(req);
    if (!currentPassword || !newPassword) return NextResponse.json({ error: "BAD_REQUEST", message: "현재 비밀번호와 새 비밀번호를 입력하세요." }, { status: 400 });
    changePassword(employee, currentPassword, newPassword);
    return NextResponse.json(toMeSummary(employee));
  });
}
