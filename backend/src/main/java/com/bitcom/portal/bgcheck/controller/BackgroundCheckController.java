package com.bitcom.portal.bgcheck.controller;

import com.bitcom.portal.bgcheck.dto.BgcDtos.BgcDetail;
import com.bitcom.portal.bgcheck.dto.BgcDtos.BgcSummary;
import com.bitcom.portal.bgcheck.service.BackgroundCheckService;
import com.bitcom.portal.common.AuthenticatedUser;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** F7/F8: 관리자 전용 (/admin/** → ROLE_ADMIN). 규칙 4: 분기 없음. */
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
@Validated
public class BackgroundCheckController {
    private static final String ID = "^[A-Z]+-\\d{3,6}$";

    private final BackgroundCheckService service;

    @GetMapping("/employees/{id}/background-checks")
    public List<BgcSummary> list(@PathVariable @Pattern(regexp = ID) String id) {
        return service.list(id);
    }

    @PostMapping("/employees/{id}/background-checks")
    @ResponseStatus(HttpStatus.CREATED)
    public BgcSummary request(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable @Pattern(regexp = ID) String id) {
        return service.request(id, user.employeeId());
    }

    @GetMapping("/background-checks/{bcId}")
    public BgcDetail detail(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable @Positive long bcId) {
        return service.detail(bcId, user.employeeId());
    }

    @PostMapping("/background-checks/{bcId}/refresh")
    public BgcSummary refresh(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable @Positive long bcId) {
        return service.refresh(bcId, user.employeeId());
    }
}
