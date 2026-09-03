package com.bitcom.portal.employee.entity;

import com.bitcom.portal.employee.EmployeeStatus;
import com.bitcom.portal.employee.Role;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

/** employees 테이블. 규칙 2: Entity 는 테이블 정의만, 변환은 DTO 가 담당. */
@Entity
@Table(name = "employees")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Employee {
    @Id
    @Column(name = "employee_id", length = 20)
    private String employeeId;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(name = "last_name", nullable = false, length = 10)
    private String lastName;

    @Column(name = "first_name", nullable = false, length = 40)
    private String firstName;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Column(length = 30)
    private String phone;

    @Column(length = 200)
    private String address;

    @Column(length = 50)
    private String department;

    @Column(length = 50)
    private String position;

    @Column(name = "hire_date")
    private LocalDate hireDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private EmployeeStatus status;

    @Column(name = "resigned_at")
    private LocalDate resignedAt;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount;

    @Column(nullable = false)
    private boolean locked;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
