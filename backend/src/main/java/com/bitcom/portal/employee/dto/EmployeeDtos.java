package com.bitcom.portal.employee.dto;

import com.bitcom.portal.bgcheck.BgcStatus;
import com.bitcom.portal.employee.AuditAction;
import com.bitcom.portal.employee.EmployeeStatus;
import com.bitcom.portal.employee.Role;
import com.bitcom.portal.employee.entity.AuditLog;
import com.bitcom.portal.employee.entity.Employee;
import com.bitcom.portal.employee.entity.EmployeeChangeLog;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/** 직원 도메인 요청/응답 DTO. 프론트 lib/types.ts 와 필드명이 같다. */
public final class EmployeeDtos {
    private EmployeeDtos() {}

    private static final String PHONE = "^[0-9\\-+() ]{0,30}$";
    private static final String TEXT = "^[^<>\"'`;]*$"; // 태그·따옴표·세미콜론 금지 (규칙 7)

    // ---------- 요청 ----------

    /** 직원 본인 수정 (F3): phone, address 만 허용. 다른 필드는 DTO 에 없으므로 무시가 아니라 400. */
    public record UpdateMeRequest(
            @Pattern(regexp = PHONE, message = "연락처 형식이 올바르지 않습니다") @Size(max = 30) String phone,
            @Pattern(regexp = TEXT, message = "허용되지 않는 문자가 있습니다") @Size(max = 200) String address
    ) {}

    public record CreateEmployeeRequest(
            @NotBlank @Size(min = 2, max = 50) @Pattern(regexp = "^[가-힣]+$", message = "성명은 한글만 입력할 수 있습니다") String name,
            LocalDate birthDate,
            @Pattern(regexp = PHONE, message = "연락처 형식이 올바르지 않습니다") @Size(max = 30) String phone,
            @Pattern(regexp = TEXT, message = "허용되지 않는 문자가 있습니다") @Size(max = 200) String address,
            @Pattern(regexp = TEXT, message = "허용되지 않는 문자가 있습니다") @Size(max = 50) String department,
            @Pattern(regexp = TEXT, message = "허용되지 않는 문자가 있습니다") @Size(max = 50) String position,
            LocalDate hireDate,
            @NotNull Role role
    ) {}

    /** 관리자 수정 (F5): 전 항목. null 인 필드는 "변경 없음". */
    public record AdminUpdateEmployeeRequest(
            @Size(min = 2, max = 50) @Pattern(regexp = "^[가-힣]+$", message = "성명은 한글만 입력할 수 있습니다") String name,
            LocalDate birthDate,
            @Pattern(regexp = PHONE, message = "연락처 형식이 올바르지 않습니다") @Size(max = 30) String phone,
            @Pattern(regexp = TEXT, message = "허용되지 않는 문자가 있습니다") @Size(max = 200) String address,
            @Pattern(regexp = TEXT, message = "허용되지 않는 문자가 있습니다") @Size(max = 50) String department,
            @Pattern(regexp = TEXT, message = "허용되지 않는 문자가 있습니다") @Size(max = 50) String position,
            LocalDate hireDate,
            Role role
    ) {}

    public record ResignRequest(LocalDate resignedAt) {}

    // ---------- 응답 ----------

    public record MeProfile(String employeeId, String name, LocalDate birthDate, String phone, String address,
                            String department, String position, LocalDate hireDate) {
        public static MeProfile from(Employee e) {
            return new MeProfile(e.getEmployeeId(), e.getName(), e.getBirthDate(), e.getPhone(), e.getAddress(),
                    e.getDepartment(), e.getPosition(), e.getHireDate());
        }
    }

    public record EmployeeListItem(String employeeId, String name, String department, String position, LocalDate hireDate,
                                   Role role, EmployeeStatus status, BgcStatus latestBgcStatus) {
        public static EmployeeListItem from(Employee e, BgcStatus latest) {
            return new EmployeeListItem(e.getEmployeeId(), e.getName(), e.getDepartment(), e.getPosition(), e.getHireDate(),
                    e.getRole(), e.getStatus(), latest);
        }
    }

    public record BgcSummaryOfEmployee(int total, BgcStatus latestStatus, boolean hasPending) {}

    public record EmployeeDetail(String employeeId, String name, String lastName, String firstName, LocalDate birthDate,
                                 String phone, String address, String department, String position, LocalDate hireDate,
                                 Role role, EmployeeStatus status, LocalDate resignedAt, boolean mustChangePassword,
                                 boolean locked, int failedLoginCount, Instant createdAt, Instant updatedAt,
                                 BgcSummaryOfEmployee backgroundCheckSummary) {
        public static EmployeeDetail from(Employee e, BgcSummaryOfEmployee bgc) {
            return new EmployeeDetail(e.getEmployeeId(), e.getName(), e.getLastName(), e.getFirstName(), e.getBirthDate(),
                    e.getPhone(), e.getAddress(), e.getDepartment(), e.getPosition(), e.getHireDate(),
                    e.getRole(), e.getStatus(), e.getResignedAt(), e.isMustChangePassword(),
                    e.isLocked(), e.getFailedLoginCount(), e.getCreatedAt(), e.getUpdatedAt(), bgc);
        }
    }

    public record CreateEmployeeResult(EmployeeDetail employee, String temporaryPassword) {}

    public record ResetPasswordResult(String temporaryPassword, EmployeeDetail employee) {}

    /** 이력 탭 항목: kind=change | audit (프론트 HistoryItem 유니온과 동일) */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HistoryItem(String kind, long id, Instant at, String actor,
                              String field, String oldValue, String newValue,
                              AuditAction action, Map<String, Object> detail) {
        public static HistoryItem change(EmployeeChangeLog c) {
            return new HistoryItem("change", c.getId(), c.getChangedAt(), c.getChangedBy(), c.getField(), c.getOldValue(), c.getNewValue(), null, null);
        }
        public static HistoryItem audit(AuditLog a) {
            return new HistoryItem("audit", a.getId(), a.getCreatedAt(), a.getActorId(), null, null, null, a.getAction(), a.getDetail());
        }
    }
}
