/**
 * Cloudflare Pages Function: /bitcom/api/* → Spring Boot (EC2) 프록시.
 *
 * - 브라우저는 pages.dev 단일 오리진만 보므로 세션 쿠키가 SameSite=Lax 로 그대로 동작한다 (docs/implementation-plan.md 1절).
 * - 환경변수 BACKEND_ORIGIN (예: http://3.34.1.2:8080) 을 Pages 프로젝트 설정에 넣는다.
 * - 메서드·본문·쿠키·Content-Type 을 그대로 전달하고, 응답의 Set-Cookie 를 그대로 돌려준다.
 */
interface Env {
  BACKEND_ORIGIN: string;
}

const HOP_BY_HOP = new Set(["connection", "keep-alive", "transfer-encoding", "te", "trailer", "upgrade", "proxy-authorization", "proxy-authenticate", "host", "content-length"]);

export const onRequest: PagesFunction<Env> = async ({ request, env }) => {
  const origin = (env.BACKEND_ORIGIN || "").replace(/\/+$/, "");
  if (!origin) {
    return json(500, { error: "PROXY_MISCONFIGURED", message: "BACKEND_ORIGIN 환경변수가 설정되지 않았습니다." });
  }

  const incoming = new URL(request.url);
  const target = origin + incoming.pathname + incoming.search; // /bitcom/api/... 경로를 그대로 유지

  const headers = new Headers();
  request.headers.forEach((value, key) => {
    if (!HOP_BY_HOP.has(key.toLowerCase())) headers.set(key, value);
  });
  headers.set("X-Forwarded-Host", incoming.host);
  headers.set("X-Forwarded-Proto", incoming.protocol.replace(":", ""));

  const hasBody = !["GET", "HEAD"].includes(request.method);
  let upstream: Response;
  try {
    upstream = await fetch(target, {
      method: request.method,
      headers,
      body: hasBody ? await request.arrayBuffer() : undefined,
      redirect: "manual",
    });
  } catch (e) {
    return json(502, { error: "BACKEND_UNREACHABLE", message: "백엔드에 연결할 수 없습니다." });
  }

  const out = new Headers();
  upstream.headers.forEach((value, key) => {
    if (!HOP_BY_HOP.has(key.toLowerCase())) out.append(key, value);
  });
  // Set-Cookie 는 여러 개일 수 있어 개별로 다시 붙인다 (getSetCookie 지원 런타임)
  const setCookies = (upstream.headers as Headers & { getSetCookie?: () => string[] }).getSetCookie?.();
  if (setCookies && setCookies.length) {
    out.delete("set-cookie");
    for (const c of setCookies) out.append("set-cookie", c);
  }
  return new Response(upstream.body, { status: upstream.status, headers: out });
};

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json; charset=utf-8" } });
}
