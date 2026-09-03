package com.bitcom.portal.employee.service;

import com.bitcom.portal.common.ApiException;
import com.bitcom.portal.common.AppProperties;
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

/** AuthService 경계·실패 조건 */
class AuthServiceEdgeTest {
    private static final Instant T0 = Instant.parse("2026-09-03T00:00:00Z");
    private static final int IDLE_SEC = 30 * 60;
    private static final int ABSOLUTE_SEC = 8 * 3600;

    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final SessionRepository sessions = mock(SessionRepository.class);
    private final AuditLogRepository audits = mock(AuditLogRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final TokenGenerator tokens = new TokenGenerator() {
        public String sessionId() { return "sid"; }
        public String temporaryPassword() { return "TempPass1!2"; }
    };
    private final AppProperties props = new AppProperties(
            new AppProperties.Session(30, 8, "SESSION", false), new AppProperties.Auth(5), new AppProperties.Seed("a", "b"));
    private Employee emp;

    @BeforeEach
    void setUp() {
        emp = Employee.builder().employeeId("EMP-001").name("김민준").lastName("김").firstName("민준")
                .role(Role.EMPLOYEE).status(EmployeeStatus.ACTIVE).passwordHash("hash")
                .mustChangePassword(false).failedLoginCount(0).locked(false).createdAt(T0).updatedAt(T0).build();
        when(employees.findById("EMP-001")).thenReturn(Optional.of(emp));
        when(sessions.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private AuthService at(Instant now) {
        return new AuthService(employees, sessions, audits, encoder, tokens, Clock.fixed(now, ZoneOffset.UTC), props);
    }

    private Session session(Instant createdAt, Instant expiresAt) {
        Session s = Session.builder().sessionId("sid").employeeId("EMP-001").createdAt(createdAt).lastAccessedAt(createdAt).expiresAt(expiresAt).build();
        when(sessions.findById("sid")).thenReturn(Optional.of(s));
        return s;
    }

    // ---------- 로그인 실패 ----------

    @Test
    void unknown_employee_returns_invalid_credentials_and_touches_nothing() {
        when(employees.findById("EMP-999")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> at(T0).login("EMP-999", "x"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("INVALID_CREDENTIALS");
        verifyNoInteractions(encoder, sessions, audits);
    }

    @Test
    void fourth_failure_is_boundary_below_lock() {
        when(encoder.matches(any(), any())).thenReturn(false);
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> at(T0).login("EMP-001", "bad")).isInstanceOf(ApiException.class);
        }
        assertThat(emp.getFailedLoginCount()).isEqualTo(4);
        assertThat(emp.isLocked()).isFalse();
        verify(sessions, never()).deleteAllByEmployeeId(any());
        verifyNoInteractions(audits);
    }

    @Test
    void locked_account_rejects_even_correct_password_without_touching_counter() {
        emp.setLocked(true);
        emp.setFailedLoginCount(5);
        when(encoder.matches(any(), any())).thenReturn(true);
        assertThatThrownBy(() -> at(T0).login("EMP-001", "pw"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("LOCKED");
        assertThat(emp.getFailedLoginCount()).isEqualTo(5);
        verify(encoder, never()).matches(any(), any()); // 잠금은 비밀번호 검사 전에 걸린다
    }

    @Test
    void successful_login_resets_failed_counter() {
        emp.setFailedLoginCount(3);
        when(encoder.matches("pw", "hash")).thenReturn(true);
        at(T0).login("EMP-001", "pw");
        assertThat(emp.getFailedLoginCount()).isZero();
    }

    @Test
    void resigned_check_precedes_lock_and_password() {
        emp.setStatus(EmployeeStatus.RESIGNED);
        emp.setLocked(true);
        assertThatThrownBy(() -> at(T0).login("EMP-001", "pw"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("RESIGNED");
    }

    @Test
    void login_id_is_normalized_but_blank_is_not_found() {
        when(encoder.matches("pw", "hash")).thenReturn(true);
        assertThat(at(T0).login("  emp-001 ", "pw").getEmployeeId()).isEqualTo("EMP-001");
        when(employees.findById("")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> at(T0).login("   ", "pw")).isInstanceOf(ApiException.class);
    }

    // ---------- 세션 경계 ----------

    @Test
    void resolve_unknown_session_is_empty() {
        when(sessions.findById("nope")).thenReturn(Optional.empty());
        assertThat(at(T0).resolve("nope")).isEmpty();
    }

    @Test
    void resolve_at_exact_expiry_instant_is_still_valid_one_second_later_is_not() {
        Instant exp = T0.plusSeconds(IDLE_SEC);
        Session s = session(T0, exp);
        assertThat(at(exp).resolve("sid")).isPresent();          // now == expiresAt → 유효 (isAfter 기준)
        assertThat(s.getExpiresAt()).isEqualTo(exp.plusSeconds(IDLE_SEC));

        session(T0, exp);                                         // 새 세션으로 재설정
        assertThat(at(exp.plusSeconds(1)).resolve("sid")).isEmpty();
        verify(sessions, atLeastOnce()).delete(any(Session.class));
    }

    @Test
    void sliding_extension_cannot_pass_absolute_limit() {
        // 7시간 59분 경과, 계속 사용 중이라 expiresAt 은 아직 미래
        Instant lastTouch = T0.plusSeconds(ABSOLUTE_SEC - 60);
        session(T0, lastTouch.plusSeconds(IDLE_SEC));
        assertThat(at(lastTouch).resolve("sid")).isPresent();

        // 8시간 + 1초 → 절대 상한 초과, 슬라이딩 만료가 남아 있어도 거부
        session(T0, T0.plusSeconds(ABSOLUTE_SEC + IDLE_SEC));
        assertThat(at(T0.plusSeconds(ABSOLUTE_SEC + 1)).resolve("sid")).isEmpty();
    }

    @Test
    void resolve_rejects_locked_employee_and_deletes_session() {
        Session s = session(T0, T0.plusSeconds(IDLE_SEC));
        emp.setLocked(true);
        assertThat(at(T0.plusSeconds(10)).resolve("sid")).isEmpty();
        verify(sessions).delete(s);
    }

    @Test
    void resolve_when_employee_row_missing_deletes_orphan_session() {
        Session s = session(T0, T0.plusSeconds(IDLE_SEC));
        when(employees.findById("EMP-001")).thenReturn(Optional.empty());
        assertThat(at(T0).resolve("sid")).isEmpty();
        verify(sessions).delete(s);
    }

    @Test
    void resolve_carries_must_change_flag_to_principal() {
        session(T0, T0.plusSeconds(IDLE_SEC));
        emp.setMustChangePassword(true);
        assertThat(at(T0).resolve("sid")).get().extracting("mustChangePassword").isEqualTo(true);
    }

    // ---------- 비밀번호 변경 실패 ----------

    @Test
    void change_password_rejects_wrong_current_password() {
        when(encoder.matches("wrong", "hash")).thenReturn(false);
        assertThatThrownBy(() -> at(T0).changePassword("EMP-001", "wrong", "NewPass1!x"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("INVALID_CURRENT_PASSWORD");
        assertThat(emp.getPasswordHash()).isEqualTo("hash");
        assertThat(emp.isMustChangePassword()).isFalse();
    }

    @Test
    void change_password_for_unknown_employee_is_not_found() {
        when(employees.findById("EMP-404")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> at(T0).changePassword("EMP-404", "a", "b"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("NOT_FOUND");
    }

    @Test
    void change_password_success_clears_must_change_and_stores_new_hash() {
        emp.setMustChangePassword(true);
        when(encoder.matches("Temp1!aaa", "hash")).thenReturn(true);
        when(encoder.encode("NewPass1!x")).thenReturn("newhash");
        at(T0.plusSeconds(5)).changePassword("EMP-001", "Temp1!aaa", "NewPass1!x");
        assertThat(emp.getPasswordHash()).isEqualTo("newhash");
        assertThat(emp.isMustChangePassword()).isFalse();
        assertThat(emp.getUpdatedAt()).isEqualTo(T0.plusSeconds(5));
    }
}
