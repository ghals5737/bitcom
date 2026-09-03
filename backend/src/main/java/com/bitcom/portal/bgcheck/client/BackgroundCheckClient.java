package com.bitcom.portal.bgcheck.client;

import com.bitcom.portal.bgcheck.dto.BgcDtos.ExternalCheck;
import com.bitcom.portal.bgcheck.dto.BgcDtos.ExternalCreateRequest;

/**
 * 규칙 3: 외부 호출은 Service 가 직접 하지 않고 이 인터페이스를 주입받는다. 테스트에서는 가짜 구현으로 교체.
 * 구현체는 HTTP 오류를 예외로 던지지 않고 Result 로 돌려준다 (호출자가 상태코드로 판단).
 */
public interface BackgroundCheckClient {

    /** POST /background-checks (재시도 포함) */
    Result create(ExternalCreateRequest request);

    /** GET /background-checks/{checkId} (단발, 재시도 없음 — 폴링 주기가 재시도 역할) */
    Result get(String checkId);

    /**
     * @param httpStatus 0 = 네트워크/타임아웃 (응답 없음)
     * @param body       2xx 일 때만 채워짐
     * @param error      실패 사유 요약 (로그·failure_reason 용, 민감값 없음)
     * @param attempts   실제 시도 횟수
     */
    record Result(int httpStatus, ExternalCheck body, String error, int attempts) {
        public boolean ok() {
            return httpStatus >= 200 && httpStatus < 300 && body != null;
        }
        public boolean notFound() {
            return httpStatus == 404;
        }
        public boolean badRequest() {
            return httpStatus == 400;
        }
    }
}
