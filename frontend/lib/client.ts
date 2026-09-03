"use client";

// 브라우저 → /bitcom/api/* 호출 래퍼. 실제 배포에서는 같은 경로가 Cloudflare Pages Function 프록시를 거쳐 Spring Boot로 간다.

export const API_PREFIX = "/bitcom/api";

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) {
    super(message);
  }
}

export async function api<T>(path: string, init: RequestInit & { json?: unknown } = {}): Promise<T> {
  const { json, headers, ...rest } = init;
  const res = await fetch(API_PREFIX + path, {
    ...rest,
    credentials: "same-origin",
    headers: { ...(json !== undefined ? { "Content-Type": "application/json" } : {}), ...(headers ?? {}) },
    body: json !== undefined ? JSON.stringify(json) : rest.body,
    cache: "no-store",
  });
  if (res.status === 204) return undefined as T;
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new ApiError(res.status, body.error ?? "ERROR", body.message ?? `요청 실패 (${res.status})`);
  return body as T;
}

export const fmtDate = (iso: string | null | undefined) => (iso ? iso.slice(0, 10) : "-");
export const fmtDateTime = (iso: string | null | undefined) => {
  if (!iso) return "-";
  const d = new Date(iso);
  return d.toLocaleString("ko-KR", { year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false });
};
