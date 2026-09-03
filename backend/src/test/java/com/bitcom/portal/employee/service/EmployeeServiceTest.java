package com.bitcom.portal.employee.service;

import com.bitcom.portal.bgcheck.service.BackgroundCheckService;
import com.bitcom.portal.common.ApiException;
import com.bitcom.portal.common.TokenGenerator;
import com.bitcom.portal.employee.AuditAction;
import com.bitcom.portal.employee.EmployeeStatus;
import com.bitcom.portal.employee.Role;
import com.bitcom.portal.employee.dto.EmployeeDtos.*;
import com.bitcom.portal.employee.entity.AuditLog;
import com.bitcom.portal.employee.entity.Employee;
import com.bitcom.portal.employee.entity.EmployeeChangeLog;
import com.bitcom.portal.employee.repository.AuditLogRepository;
import com.bitcom.portal.employee.repository.EmployeeChangeLogRepository;
import com.bitcom.portal.employee.repository.EmployeeRepository;
import com.bitcom.portal.employee.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EmployeeServiceTest {
    private static final Instant T0 = Instant.parse("2026-09-03T00:00:00Z");

    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final SessionRepository sessions = mock(SessionRepository.class);
    private final EmployeeChangeLogRepository changeLogs = mock(EmployeeChangeLogRepository.class);
    private final AuditLogRepository audits = mock(AuditLogRepository.class);
    private final BackgroundCheckService bgc = mock(BackgroundCheckService.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final TokenGenerator tokens = new TokenGenerator() {
        public String sessionId() { return "sid"; }
        public String temporaryPassword() { return "TempPass1!2"; }
    };
    private EmployeeService service;

    @BeforeEach
    void setUp() {
        service = new EmployeeService(employees, sessions, changeLogs, audits, bgc, encoder, tokens, Clock.fixed(T0, ZoneOffset.UTC));
        when(encoder.encode(any())).thenAnswer(inv -> "hash(" + inv.getArgument(0) + ")");
        when(employees.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(bgc.summaryOf(any())).thenReturn(new BgcSummaryOfEmployee(0, null, false));
        when(bgc.deleteAllForEmployee(any())).thenReturn(List.of());
    }

    @Test
    void parseName_first_char_is_last_name_rest_is_first_name() {
        // F4 결정: 복성 사전 없음. 남궁서준 → 남 / 궁서준 (planning.md 에 기록된 한계)
        assertThat(EmployeeService.parseName("김민준")).containsExactly("김", "민준");
        assertThat(EmployeeService.parseName("남궁서준")).containsExactly("남", "궁서준");
        assertThat(EmployeeService.parseName("김솔")).containsExactly("김", "솔");
        assertThat(EmployeeService.parseName(" 선우진 ")).containsExactly("선", "우진");
    }

    @Test
    void create_assigns_next_emp_number_and_returns_temp_password_once() {
        when(employees.findMaxEmpNumber()).thenReturn(Optional.of(10));

        CreateEmployeeResult r = service.create(new CreateEmployeeRequest("황보라온", LocalDate.of(1995, 2, 9), "", "", "디자인팀", "주임", null, Role.EMPLOYEE), "ADMIN-001");

        assertThat(r.employee().employeeId()).isEqualTo("EMP-011");
        assertThat(r.employee().lastName()).isEqualTo("황");
        assertThat(r.employee().firstName()).isEqualTo("보라온");
        assertThat(r.employee().mustChangePassword()).isTrue();
        assertThat(r.temporaryPassword()).isEqualTo("TempPass1!2");
        assertThat(r.employee().phone()).isNull(); // 빈 문자열 → null
        ArgumentCaptor<Employee> saved = ArgumentCaptor.forClass(Employee.class);
        verify(employees).save(saved.capture());
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("hash(TempPass1!2)");
        assertThat(saved.getValue().getCreatedAt()).isEqualTo(T0);
    }

    @Test
    void create_with_empty_table_starts_at_001() {
        when(employees.findMaxEmpNumber()).thenReturn(Optional.empty());
        assertThat(service.nextEmployeeId()).isEqualTo("EMP-001");
    }

    @Test
    void updateMe_records_change_log_only_for_changed_fields() {
        Employee e = active("EMP-001");
        e.setPhone("010-1");
        e.setAddress("서울");
        when(employees.findById("EMP-001")).thenReturn(Optional.of(e));

        service.updateMe("EMP-001", new UpdateMeRequest("010-2", "서울"));

        ArgumentCaptor<EmployeeChangeLog> log = ArgumentCaptor.forClass(EmployeeChangeLog.class);
        verify(changeLogs, times(1)).save(log.capture());
        assertThat(log.getValue().getField()).isEqualTo("phone");
        assertThat(log.getValue().getOldValue()).isEqualTo("010-1");
        assertThat(log.getValue().getNewValue()).isEqualTo("010-2");
        assertThat(log.getValue().getChangedBy()).isEqualTo("EMP-001");
        assertThat(e.getUpdatedAt()).isEqualTo(T0);
    }

    @Test
    void resign_marks_resigned_revokes_sessions_deletes_bgc_and_audits() {
        Employee e = active("EMP-010");
        when(employees.findById("EMP-010")).thenReturn(Optional.of(e));
        when(bgc.deleteAllForEmployee("EMP-010")).thenReturn(List.of(7L, 8L));

        EmployeeDetail d = service.resign("EMP-010", null, "ADMIN-001");

        assertThat(d.status()).isEqualTo(EmployeeStatus.RESIGNED);
        assertThat(d.resignedAt()).isEqualTo(LocalDate.of(2026, 9, 3)); // 주입된 Clock 기준
        verify(sessions).deleteAllByEmployeeId("EMP-010");
        ArgumentCaptor<AuditLog> audit = ArgumentCaptor.forClass(AuditLog.class);
        verify(audits, times(2)).save(audit.capture());
        assertThat(audit.getAllValues()).extracting(AuditLog::getAction)
                .containsExactly(AuditAction.EMPLOYEE_RESIGNED, AuditAction.BGCHECK_DELETED);
        assertThat(audit.getAllValues().get(1).getDetail()).containsEntry("deletedCount", 2);
    }

    @Test
    void resign_self_is_rejected() {
        when(employees.findById("ADMIN-001")).thenReturn(Optional.of(active("ADMIN-001")));
        assertThatThrownBy(() -> service.resign("ADMIN-001", null, "ADMIN-001"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("SELF_RESIGN");
    }

    @Test
    void resetPassword_unlocks_and_forces_change() {
        Employee e = active("EMP-009");
        e.setLocked(true);
        e.setFailedLoginCount(5);
        when(employees.findById("EMP-009")).thenReturn(Optional.of(e));

        ResetPasswordResult r = service.resetPassword("EMP-009", "ADMIN-001");

        assertThat(r.temporaryPassword()).isEqualTo("TempPass1!2");
        assertThat(e.isLocked()).isFalse();
        assertThat(e.getFailedLoginCount()).isZero();
        assertThat(e.isMustChangePassword()).isTrue();
        verify(sessions).deleteAllByEmployeeId("EMP-009");
    }

    private static Employee active(String id) {
        return Employee.builder().employeeId(id).name("김민준").lastName("김").firstName("민준")
                .role(Role.EMPLOYEE).status(EmployeeStatus.ACTIVE).passwordHash("h")
                .mustChangePassword(false).failedLoginCount(0).locked(false).createdAt(T0).updatedAt(T0).build();
    }
}
