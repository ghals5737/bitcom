import type { NextConfig } from "next";

/**
 * BACKEND_URL 이 설정되면 /bitcom/api/* 를 실제 백엔드(Spring Boot)로 프록시한다 (beforeFiles → app/bitcom/api 목업보다 우선).
 * 설정이 없으면 app/bitcom/api 의 인메모리 목업이 응답한다.
 * 배포(Cloudflare Pages)에서는 이 역할을 functions/bitcom/api/[[path]].ts 가 한다.
 */
const backend = process.env.BACKEND_URL;

const nextConfig: NextConfig = {
  async rewrites() {
    if (!backend) return [];
    return {
      beforeFiles: [{ source: "/bitcom/api/:path*", destination: `${backend}/bitcom/api/:path*` }],
      afterFiles: [],
      fallback: [],
    };
  },
};

export default nextConfig;
