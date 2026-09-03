package com.bitcom.portal.employee.dto;

import com.bitcom.portal.employee.Role;
import com.bitcom.portal.employee.entity.Employee;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 인증 관련 요청/응답 DTO. 규칙 7: request 는 validation 으로 형식을 고정한다. */
public final class AuthDtos {
    private AuthDtos() {}

    public record LoginRequest(
            @NotBlank @Size(max = 20) @Pattern(regexp = "^[A-Za-z]+-\\d{3,6}$", message = "사번 형식이 아닙니다 (예: EMP-001)")
            String employeeId,
            @NotBlank @Size(min = 1, max = 100)
            String password
    ) {}

    public record ChangePasswordRequest(
            @NotBlank @Size(max = 100) String currentPassword,
            @NotBlank @Size(min = 8, max = 100)
            @Pattern(regexp = "^(?=.*\\d)(?=.*[^A-Za-z0-9]).{8,}$", message = "비밀번호는 8자 이상이며 숫자와 특수문자를 포함해야 합니다.")
            String newPassword
    ) {}

    /** GET /auth/me, 로그인 응답 */
    public record MeSummary(String employeeId, String name, Role role, boolean mustChangePassword) {
        public static MeSummary from(Employee e) {
            return new MeSummary(e.getEmployeeId(), e.getName(), e.getRole(), e.isMustChangePassword());
        }
    }
}
