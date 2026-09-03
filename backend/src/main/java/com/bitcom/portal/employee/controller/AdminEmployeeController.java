package com.bitcom.portal.employee.controller;

import com.bitcom.portal.common.AuthenticatedUser;
import com.bitcom.portal.employee.dto.EmployeeDtos.*;
import com.bitcom.portal.employee.service.EmployeeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** F4/F5/F6: 관리자 전용. /admin/** 는 SecurityConfig 에서 ROLE_ADMIN 만 통과. */
@RestController
@RequestMapping("/admin/employees")
@RequiredArgsConstructor
@Validated
public class AdminEmployeeController {
    private static final String ID = "^[A-Z]+-\\d{3,6}$";

    private final EmployeeService employeeService;

    @GetMapping
    public List<EmployeeListItem> list(@RequestParam(required = false) @Pattern(regexp = "^(?i)(ALL|ACTIVE|RESIGNED)?$") String status) {
        return employeeService.list(status);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateEmployeeResult create(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody CreateEmployeeRequest req) {
        return employeeService.create(req, user.employeeId());
    }

    @GetMapping("/{id}")
    public EmployeeDetail detail(@PathVariable @Pattern(regexp = ID) String id) {
        return employeeService.detail(id);
    }

    @PatchMapping("/{id}")
    public EmployeeDetail update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable @Pattern(regexp = ID) String id,
                                 @Valid @RequestBody AdminUpdateEmployeeRequest req) {
        return employeeService.adminUpdate(id, req, user.employeeId());
    }

    @PostMapping("/{id}/resign")
    public EmployeeDetail resign(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable @Pattern(regexp = ID) String id,
                                 @RequestBody(required = false) ResignRequest req) {
        return employeeService.resign(id, req == null ? null : req.resignedAt(), user.employeeId());
    }

    @PostMapping("/{id}/reset-password")
    public ResetPasswordResult resetPassword(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable @Pattern(regexp = ID) String id) {
        return employeeService.resetPassword(id, user.employeeId());
    }

    @GetMapping("/{id}/history")
    public List<HistoryItem> history(@PathVariable @Pattern(regexp = ID) String id) {
        return employeeService.history(id);
    }
}
