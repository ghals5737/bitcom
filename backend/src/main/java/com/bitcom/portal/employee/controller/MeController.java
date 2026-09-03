package com.bitcom.portal.employee.controller;

import com.bitcom.portal.common.AuthenticatedUser;
import com.bitcom.portal.employee.dto.EmployeeDtos.MeProfile;
import com.bitcom.portal.employee.dto.EmployeeDtos.UpdateMeRequest;
import com.bitcom.portal.employee.service.EmployeeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** F2/F3: 직원 본인. 대상은 항상 세션의 본인 사번 → 타인 정보 접근 경로 자체가 없다 (F9). */
@RestController
@RequestMapping("/me")
@RequiredArgsConstructor
public class MeController {
    private final EmployeeService employeeService;

    @GetMapping
    public MeProfile me(@AuthenticationPrincipal AuthenticatedUser user) {
        return employeeService.me(user.employeeId());
    }

    @PatchMapping
    public MeProfile update(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody UpdateMeRequest req) {
        return employeeService.updateMe(user.employeeId(), req);
    }
}
