import { NextResponse } from "next/server";
import { login, toMeSummary, db, SESSION_IDLE_MINUTES } from "@/lib/mock/store";
import { handle, readJson, SESSION_COOKIE } from "@/lib/server/auth";

export async function POST(req: Request) {
  return handle(async () => {
    const { employeeId, password } = await readJson<{ employeeId?: string; password?: string }>(req);
    if (!employeeId || !password) return NextResponse.json({ error: "BAD_REQUEST", message: "사번과 비밀번호를 입력하세요." }, { status: 400 });
    const session = login(employeeId, password);
    const employee = db.employees.get(session.employeeId)!;
    const res = NextResponse.json(toMeSummary(employee));
    res.cookies.set(SESSION_COOKIE, session.sessionId, {
      httpOnly: true,
      sameSite: "lax",
      secure: process.env.NODE_ENV === "production",
      path: "/",
      maxAge: SESSION_IDLE_MINUTES * 60,
    });
    return res;
  });
}
