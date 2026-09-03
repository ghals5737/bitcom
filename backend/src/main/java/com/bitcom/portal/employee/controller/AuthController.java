package com.bitcom.portal.employee.controller;

import com.bitcom.portal.common.AppProperties;
import com.bitcom.portal.common.AuthenticatedUser;
import com.bitcom.portal.employee.dto.AuthDtos.ChangePasswordRequest;
import com.bitcom.portal.employee.dto.AuthDtos.LoginRequest;
import com.bitcom.portal.employee.dto.AuthDtos.MeSummary;
import com.bitcom.portal.employee.entity.Session;
import com.bitcom.portal.employee.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

/** 규칙 4: 컨트롤러는 분기·트랜잭션 없이 서비스 호출과 HTTP 변환만. */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;
    private final AppProperties props;

    @PostMapping("/login")
    public ResponseEntity<MeSummary> login(@Valid @RequestBody LoginRequest req) {
        Session s = authService.login(req.employeeId(), req.password());
        MeSummary me = MeSummary.from(authService.getEmployee(s.getEmployeeId()));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, sessionCookie(s.getSessionId(), Duration.ofMinutes(props.session().idleMinutes())).toString())
                .body(me);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal AuthenticatedUser user) {
        authService.logout(user.sessionId());
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, sessionCookie("", Duration.ZERO).toString())
                .build();
    }

    @GetMapping("/me")
    public MeSummary me(@AuthenticationPrincipal AuthenticatedUser user) {
        return new MeSummary(user.employeeId(), user.name(), user.role(), user.mustChangePassword());
    }

    @PostMapping("/change-password")
    public MeSummary changePassword(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody ChangePasswordRequest req) {
        return MeSummary.from(authService.changePassword(user.employeeId(), req.currentPassword(), req.newPassword()));
    }

    private ResponseCookie sessionCookie(String value, Duration maxAge) {
        return ResponseCookie.from(props.session().cookieName(), value)
                .httpOnly(true)
                .secure(props.session().cookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build();
    }
}
