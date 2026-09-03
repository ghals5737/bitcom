package com.bitcom.portal.bgcheck;

/** 앱 내부 상태. 외부 API 의 pending/clear/flagged 에 FAILED(요청 실패), TIMEOUT(폴링 상한 초과) 을 더한 것. */
public enum BgcStatus {
    PENDING, CLEAR, FLAGGED, FAILED, TIMEOUT;

    public boolean isFinal() {
        return this != PENDING;
    }
}
