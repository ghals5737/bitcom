package com.bitcom.portal.employee.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

import java.time.Instant;

/** sessions 테이블 (N1: 서버 세션 저장소) */
@Entity
@Table(name = "sessions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Session {
    @Id
    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Column(name = "employee_id", nullable = false, length = 20)
    private String employeeId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_accessed_at", nullable = false)
    private Instant lastAccessedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}
