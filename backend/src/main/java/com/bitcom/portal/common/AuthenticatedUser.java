package com.bitcom.portal.common;

import com.bitcom.portal.employee.Role;

/** SecurityContext 의 principal. 세션 필터가 DB 에서 확인한 직원 정보만 담는다. */
public record AuthenticatedUser(String employeeId, String name, Role role, boolean mustChangePassword, String sessionId) {
    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
