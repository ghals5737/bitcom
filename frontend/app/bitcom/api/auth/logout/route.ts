import { NextResponse } from "next/server";
import { cookies } from "next/headers";
import { logout } from "@/lib/mock/store";
import { SESSION_COOKIE } from "@/lib/server/auth";

export async function POST() {
  const jar = await cookies();
  const sid = jar.get(SESSION_COOKIE)?.value;
  if (sid) logout(sid);
  const res = new NextResponse(null, { status: 204 });
  res.cookies.set(SESSION_COOKIE, "", { httpOnly: true, path: "/", maxAge: 0 });
  return res;
}
