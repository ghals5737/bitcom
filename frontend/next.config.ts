import type { NextConfig } from "next";

/**
 * - STATIC_EXPORT=1 (Cloudflare Pages 빌드): 정적 export → out/. API 는 Pages Function(/functions/bitcom/api) 이 EC2 로 프록시.
 * - 로컬 개발: BACKEND_URL 로 /bitcom/api/* 를 Spring Boot 에 rewrite (dev 서버 전용).
 */
const backend = process.env.BACKEND_URL;
const staticExport = process.env.STATIC_EXPORT === "1";

const nextConfig: NextConfig = {
  output: staticExport ? "export" : undefined,
  images: { unoptimized: true },
  // export 모드에서는 rewrites 자체를 정의하지 않는다 (정의만 있어도 경고)
  ...(staticExport || !backend
    ? {}
    : { rewrites: async () => [{ source: "/bitcom/api/:path*", destination: `${backend}/bitcom/api/:path*` }] }),
};

export default nextConfig;
