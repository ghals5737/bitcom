package com.bitcom.portal.employee.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** employee_change_logs 테이블 (F3 변경 이력) */
@Entity
@Table(name = "employee_change_logs")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeChangeLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id", nullable = false, length = 20)
    private String employeeId;

    @Column(name = "changed_by", nullable = false, length = 20)
    private String changedBy;

    @Column(nullable = false, length = 30)
    private String field;

    @Column(name = "old_value", length = 200)
    private String oldValue;

    @Column(name = "new_value", length = 200)
    private String newValue;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;
}
