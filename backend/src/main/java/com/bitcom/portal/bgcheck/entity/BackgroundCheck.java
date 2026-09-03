package com.bitcom.portal.bgcheck.entity;

import com.bitcom.portal.bgcheck.BgcStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/** background_checks 테이블 (F7/F8/N3). 민감 필드는 완료 시에만 채워지고 로그에는 쓰지 않는다. */
@Entity
@Table(name = "background_checks")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BackgroundCheck {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id", nullable = false, length = 20)
    private String employeeId;

    @Column(name = "check_id", length = 60)
    private String checkId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private BgcStatus status;

    @Column(name = "criminal_record")
    private Boolean criminalRecord;

    @Column(name = "education_verified")
    private Boolean educationVerified;

    @Column(name = "employment_verified")
    private Boolean employmentVerified;

    @Column(name = "credit_score", length = 10)
    private String creditScore;

    @Column(name = "requested_by", nullable = false, length = 20)
    private String requestedBy;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "last_polled_at")
    private Instant lastPolledAt;

    @Column(name = "poll_count", nullable = false)
    private int pollCount;

    @Column(name = "failure_reason", length = 300)
    private String failureReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_payload", columnDefinition = "jsonb")
    private Map<String, Object> requestPayload;
}
