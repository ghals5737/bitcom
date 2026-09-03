package com.bitcom.portal.bgcheck.dto;

import com.bitcom.portal.bgcheck.BgcStatus;
import com.bitcom.portal.bgcheck.entity.BackgroundCheck;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Map;

/** Background Check 응답 DTO (프론트 BgcSummary / BgcDetail 과 동일 필드) */
public final class BgcDtos {
    private BgcDtos() {}

    public record BgcSummary(long id, String checkId, BgcStatus status, String requestedBy, Instant requestedAt,
                             Instant completedAt, String failureReason, int pollCount) {
        public static BgcSummary from(BackgroundCheck b) {
            return new BgcSummary(b.getId(), b.getCheckId(), b.getStatus(), b.getRequestedBy(), b.getRequestedAt(),
                    b.getCompletedAt(), b.getFailureReason(), b.getPollCount());
        }
    }

    /** 민감 필드 포함. 이 DTO 를 돌려주는 API 는 감사 로그(BGCHECK_VIEWED)를 남긴다. */
    public record BgcDetail(long id, String checkId, BgcStatus status, String requestedBy, Instant requestedAt,
                            Instant completedAt, String failureReason, int pollCount,
                            String employeeId, Boolean criminalRecord, Boolean educationVerified, Boolean employmentVerified,
                            String creditScore, Map<String, Object> requestPayload, Instant lastPolledAt) {
        public static BgcDetail from(BackgroundCheck b) {
            return new BgcDetail(b.getId(), b.getCheckId(), b.getStatus(), b.getRequestedBy(), b.getRequestedAt(),
                    b.getCompletedAt(), b.getFailureReason(), b.getPollCount(),
                    b.getEmployeeId(), b.getCriminalRecord(), b.getEducationVerified(), b.getEmploymentVerified(),
                    b.getCreditScore(), b.getRequestPayload(), b.getLastPolledAt());
        }
    }

    // ---------- 외부 API 계약 (swagger.yaml) ----------

    public record ExternalCreateRequest(String employeeId, String firstName, String lastName, String dateOfBirth) {}

    /** POST 201 / GET 200 본문. 실측상 estimatedCompletionSeconds 는 오지 않지만 명세에 있으므로 받을 수 있게 둔다. */
    @JsonIgnoreProperties(ignoreUnknown = true) // 외부 응답은 명세 외 필드가 와도 받아들인다
    public record ExternalCheck(String checkId, String employeeId, String status,
                                Boolean criminalRecord, Boolean educationVerified, Boolean employmentVerified, String creditScore,
                                String createdAt, String completedAt, Integer estimatedCompletionSeconds, String message) {
        public boolean isFinal() {
            return "clear".equalsIgnoreCase(status) || "flagged".equalsIgnoreCase(status);
        }
        public BgcStatus toStatus() {
            return "flagged".equalsIgnoreCase(status) ? BgcStatus.FLAGGED
                    : "clear".equalsIgnoreCase(status) ? BgcStatus.CLEAR : BgcStatus.PENDING;
        }
    }
}
