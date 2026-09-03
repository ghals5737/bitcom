package com.bitcom.portal.employee.service;

import com.bitcom.portal.common.ApiException;
import com.bitcom.portal.common.AppProperties;
import com.bitcom.portal.common.AuthenticatedUser;
import com.bitcom.portal.common.TokenGenerator;
import com.bitcom.portal.employee.EmployeeStatus;
import com.bitcom.portal.employee.Role;
import com.bitcom.portal.employee.entity.Employee;
import com.bitcom.portal.employee.entity.Session;
import com.bitcom.portal.employee.repository.AuditLogRepository;
import com.bitcom.portal.employee.repository.EmployeeRepository;
import com.bitcom.portal.employee.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 규칙 3 검증: Clock/TokenGenerator/PasswordEncoder 를 고정값으로 주입해 시간·랜덤에 의존하지 않고 검증한다. */
class AuthServiceTest {
    private static final Instant T0 = Instant.parse("2026-09-03T00:00:00Z");

    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final SessionRepository sessions = mock(SessionRepository.class);
    private final AuditLogRepository audits = mock(AuditLogRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final TokenGenerator tokens = new TokenGenerator() {
        public String sessionId() { return "fixed-session-id"; }
        public String temporaryPassword() { return "TempPass1!2"; }
    };
    private final AppProperties props = new AppProperties(
            new AppProperties.Session(30, 8, "SESSION", false),
            new AppProperties.Auth(5),
            new AppProperties.Seed("a", "b"));

    private Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
    private AuthService service;
    private Employee emp;

    @BeforeEach
    void setUp() {
        service = new AuthService(employees, sessions, audits, encoder, tokens, clock, props);
        emp = Employee.builder().employeeId("EMP-001").name("김민준").lastName("김").firstName("민준")
                .role(Role.EMPLOYEE).status(EmployeeStatus.ACTIVE).passwordHash("hash")
                .mustChangePassword(false).failedLoginCount(0).locked(false).createdAt(T0).updatedAt(T0).build();
        when(employees.findById("EMP-001")).thenReturn(Optional.of(emp));
        when(sessions.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void login_success_creates_session_with_idle_expiry_from_injected_clock() {
        when(encoder.matches("pw", "hash")).thenReturn(true);

        Session s = service.login("emp-001", "pw");

        assertThat(s.getSessionId()).isEqualTo("fixed-session-id");
        assertThat(s.getCreatedAt()).isEqualTo(T0);
        assertThat(s.getExpiresAt()).isEqualTo(T0.plusSeconds(30 * 60));
    }

    @Test
    void fifth_failure_locks_account_and_revokes_sessions() {
        when(encoder.matches(any(), any())).thenReturn(false);

        for (int i = 1; i <= 4; i++) {
            assertThatThrownBy(() -> service.login("EMP-001", "bad"))
                    .isInstanceOf(ApiException.class).hasMessageContaining("(" + i + "/5)");
        }
        assertThatThrownBy(() -> service.login("EMP-001", "bad"))
                .isInstanceOf(ApiException.class).hasMessageContaining("잠겼습니다");

        assertThat(emp.isLocked()).isTrue();
        verify(sessions).deleteAllByEmployeeId("EMP-001");
        verify(audits).save(any());
    }

    @Test
    void resigned_employee_cannot_login() {
        emp.setStatus(EmployeeStatus.RESIGNED);
        assertThatThrownBy(() -> service.login("EMP-001", "pw"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("RESIGNED");
    }

    @Test
    void resolve_extends_session_while_active_and_drops_after_idle_timeout() {
        Session s = Session.builder().sessionId("sid").employeeId("EMP-001")
                .createdAt(T0).lastAccessedAt(T0).expiresAt(T0.plusSeconds(1800)).build();
        when(sessions.findById("sid")).thenReturn(Optional.of(s));

        // 10분 뒤 접근 → 연장
        service = new AuthService(employees, sessions, audits, encoder, tokens, Clock.fixed(T0.plusSeconds(600), ZoneOffset.UTC), props);
        Optional<AuthenticatedUser> u = service.resolve("sid");
        assertThat(u).isPresent();
        assertThat(s.getExpiresAt()).isEqualTo(T0.plusSeconds(600 + 1800));

        // 그로부터 31분 뒤 → 만료, 세션 삭제
        service = new AuthService(employees, sessions, audits, encoder, tokens, Clock.fixed(T0.plusSeconds(600 + 1860), ZoneOffset.UTC), props);
        assertThat(service.resolve("sid")).isEmpty();
        verify(sessions).delete(s);
    }

    @Test
    void resolve_rejects_session_of_resigned_employee_even_if_not_expired() {
        Session s = Session.builder().sessionId("sid").employeeId("EMP-001")
                .createdAt(T0).lastAccessedAt(T0).expiresAt(T0.plusSeconds(1800)).build();
        when(sessions.findById("sid")).thenReturn(Optional.of(s));
        emp.setStatus(EmployeeStatus.RESIGNED);

        assertThat(service.resolve("sid")).isEmpty();
        verify(sessions).delete(s);
    }

    @Test
    void change_password_rejects_same_as_temporary() {
        when(encoder.matches("Temp1!aaa", "hash")).thenReturn(true);
        assertThatThrownBy(() -> service.changePassword("EMP-001", "Temp1!aaa", "Temp1!aaa"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("SAME_AS_TEMP");
    }
}
