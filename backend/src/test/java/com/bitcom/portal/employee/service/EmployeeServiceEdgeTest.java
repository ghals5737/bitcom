package com.bitcom.portal.employee.service;

import com.bitcom.portal.bgcheck.service.BackgroundCheckService;
import com.bitcom.portal.common.ApiException;
import com.bitcom.portal.common.TokenGenerator;
import com.bitcom.portal.employee.EmployeeStatus;
import com.bitcom.portal.employee.Role;
import com.bitcom.portal.employee.dto.EmployeeDtos.*;
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

/** EmployeeService 경계·실패 조건 */
class EmployeeServiceEdgeTest {
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

    private Employee active(String id) {
        Employee e = Employee.builder().employeeId(id).name("김민준").lastName("김").firstName("민준")
                .role(Role.EMPLOYEE).status(EmployeeStatus.ACTIVE).passwordHash("h")
                .mustChangePassword(false).failedLoginCount(0).locked(false).createdAt(T0).updatedAt(T0).build();
        when(employees.findById(id)).thenReturn(Optional.of(e));
        return e;
    }

    // ---------- 조회 실패 ----------

    @Test
    void me_detail_history_of_unknown_employee_are_not_found() {
        when(employees.findById("EMP-404")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.me("EMP-404")).isInstanceOf(ApiException.class).extracting("code").isEqualTo("NOT_FOUND");
        assertThatThrownBy(() -> service.detail("EMP-404")).isInstanceOf(ApiException.class).extracting("code").isEqualTo("NOT_FOUND");
        assertThatThrownBy(() -> service.history("EMP-404")).isInstanceOf(ApiException.class).extracting("code").isEqualTo("NOT_FOUND");
    }

    @Test
    void list_rejects_unknown_status_and_treats_null_blank_ALL_as_all() {
        when(employees.findAllByOrderByEmployeeIdAsc()).thenReturn(List.of());
        assertThatThrownBy(() -> service.list("FIRED")).isInstanceOf(ApiException.class).extracting("code").isEqualTo("BAD_STATUS");
        service.list(null);
        service.list("");
        service.list("all");
        verify(employees, times(3)).findAllByOrderByEmployeeIdAsc();
        service.list("resigned");
        verify(employees).findAllByStatusOrderByEmployeeIdAsc(EmployeeStatus.RESIGNED);
    }

    // ---------- 파싱·채번 경계 ----------

    @Test
    void parseName_two_char_name_gives_empty_first_name_for_single_char_input() {
        // validation 이 2자 미만을 막지만, 서비스 자체는 1자 입력에도 예외를 던지지 않는다 (성만 채움)
        assertThat(EmployeeService.parseName("김")).containsExactly("김", "");
        assertThat(EmployeeService.parseName("남궁민수현")).containsExactly("남", "궁민수현");
    }

    @Test
    void nextEmployeeId_pads_to_three_digits_and_grows_beyond_999() {
        when(employees.findMaxEmpNumber()).thenReturn(Optional.of(9));
        assertThat(service.nextEmployeeId()).isEqualTo("EMP-010");
        when(employees.findMaxEmpNumber()).thenReturn(Optional.of(999));
        assertThat(service.nextEmployeeId()).isEqualTo("EMP-1000");
    }

    // ---------- 수정 경계 ----------

    @Test
    void updateMe_with_all_null_changes_nothing_and_writes_no_logs() {
        Employee e = active("EMP-001");
        e.setPhone("010-1");
        service.updateMe("EMP-001", new UpdateMeRequest(null, null));
        assertThat(e.getPhone()).isEqualTo("010-1");
        assertThat(e.getUpdatedAt()).isEqualTo(T0);
        verifyNoInteractions(changeLogs, audits);
    }

    @Test
    void updateMe_blank_string_clears_value_and_logs_null_as_new_value() {
        Employee e = active("EMP-001");
        e.setAddress("서울");
        service.updateMe("EMP-001", new UpdateMeRequest(null, "   "));
        assertThat(e.getAddress()).isNull();
        ArgumentCaptor<EmployeeChangeLog> log = ArgumentCaptor.forClass(EmployeeChangeLog.class);
        verify(changeLogs).save(log.capture());
        assertThat(log.getValue().getOldValue()).isEqualTo("서울");
        assertThat(log.getValue().getNewValue()).isNull();
    }

    @Test
    void updateMe_same_value_is_not_a_change() {
        Employee e = active("EMP-001");
        e.setPhone("010-1");
        service.updateMe("EMP-001", new UpdateMeRequest("010-1", null));
        verifyNoInteractions(changeLogs, audits);
    }

    @Test
    void adminUpdate_on_resigned_employee_is_rejected() {
        Employee e = active("EMP-010");
        e.setStatus(EmployeeStatus.RESIGNED);
        assertThatThrownBy(() -> service.adminUpdate("EMP-010", new AdminUpdateEmployeeRequest("정하윤", null, null, null, null, null, null, null), "ADMIN-001"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("RESIGNED");
        verifyNoInteractions(changeLogs);
    }

    @Test
    void adminUpdate_name_change_reparses_external_api_names() {
        Employee e = active("EMP-003");
        service.adminUpdate("EMP-003", new AdminUpdateEmployeeRequest("남궁서준", null, null, null, null, null, null, null), "ADMIN-001");
        assertThat(e.getName()).isEqualTo("남궁서준");
        assertThat(e.getLastName()).isEqualTo("남");
        assertThat(e.getFirstName()).isEqualTo("궁서준");
        ArgumentCaptor<EmployeeChangeLog> log = ArgumentCaptor.forClass(EmployeeChangeLog.class);
        verify(changeLogs).save(log.capture());
        assertThat(log.getValue().getField()).isEqualTo("name");
        assertThat(log.getValue().getChangedBy()).isEqualTo("ADMIN-001");
    }

    @Test
    void adminUpdate_role_and_dates_are_logged_as_strings() {
        Employee e = active("EMP-002");
        e.setHireDate(LocalDate.of(2021, 7, 1));
        service.adminUpdate("EMP-002", new AdminUpdateEmployeeRequest(null, null, null, null, null, null, LocalDate.of(2021, 8, 1), Role.ADMIN), "ADMIN-001");
        assertThat(e.getRole()).isEqualTo(Role.ADMIN);
        assertThat(e.getHireDate()).isEqualTo(LocalDate.of(2021, 8, 1));
        ArgumentCaptor<EmployeeChangeLog> log = ArgumentCaptor.forClass(EmployeeChangeLog.class);
        verify(changeLogs, times(2)).save(log.capture());
        assertThat(log.getAllValues()).extracting(EmployeeChangeLog::getField).containsExactly("hireDate", "role");
        assertThat(log.getAllValues().get(0).getOldValue()).isEqualTo("2021-07-01");
        assertThat(log.getAllValues().get(1).getNewValue()).isEqualTo("ADMIN");
    }

    // ---------- 퇴사·재발급 실패 ----------

    @Test
    void resign_already_resigned_is_conflict_and_does_not_touch_sessions() {
        Employee e = active("EMP-010");
        e.setStatus(EmployeeStatus.RESIGNED);
        assertThatThrownBy(() -> service.resign("EMP-010", null, "ADMIN-001"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("ALREADY_RESIGNED");
        verifyNoInteractions(sessions, bgc, audits);
    }

    @Test
    void resign_with_explicit_date_uses_it_and_skips_bgc_audit_when_nothing_deleted() {
        active("EMP-010");
        EmployeeDetail d = service.resign("EMP-010", LocalDate.of(2026, 9, 30), "ADMIN-001");
        assertThat(d.resignedAt()).isEqualTo(LocalDate.of(2026, 9, 30));
        verify(audits, times(1)).save(any()); // EMPLOYEE_RESIGNED 만, BGCHECK_DELETED 없음
    }

    @Test
    void resign_unknown_employee_is_not_found() {
        when(employees.findById("EMP-404")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.resign("EMP-404", null, "ADMIN-001"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("NOT_FOUND");
    }

    @Test
    void resetPassword_on_resigned_is_rejected_and_hash_unchanged() {
        Employee e = active("EMP-010");
        e.setStatus(EmployeeStatus.RESIGNED);
        assertThatThrownBy(() -> service.resetPassword("EMP-010", "ADMIN-001"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("RESIGNED");
        assertThat(e.getPasswordHash()).isEqualTo("h");
        verifyNoInteractions(sessions);
    }

    @Test
    void create_with_null_optional_fields_and_admin_role() {
        when(employees.findMaxEmpNumber()).thenReturn(Optional.of(10));
        CreateEmployeeResult r = service.create(new CreateEmployeeRequest("이서연", null, null, null, null, null, null, Role.ADMIN), "ADMIN-001");
        assertThat(r.employee().role()).isEqualTo(Role.ADMIN);
        assertThat(r.employee().birthDate()).isNull();
        assertThat(r.employee().department()).isNull();
        assertThat(r.employee().status()).isEqualTo(EmployeeStatus.ACTIVE);
    }
}
