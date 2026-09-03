package com.bitcom.portal.bgcheck.service;

import com.bitcom.portal.bgcheck.BgcStatus;
import com.bitcom.portal.bgcheck.client.BackgroundCheckClient;
import com.bitcom.portal.bgcheck.client.BackgroundCheckClient.Result;
import com.bitcom.portal.bgcheck.client.BgcProperties;
import com.bitcom.portal.bgcheck.dto.BgcDtos.BgcSummary;
import com.bitcom.portal.bgcheck.dto.BgcDtos.ExternalCheck;
import com.bitcom.portal.bgcheck.dto.BgcDtos.ExternalCreateRequest;
import com.bitcom.portal.bgcheck.entity.BackgroundCheck;
import com.bitcom.portal.bgcheck.repository.BackgroundCheckRepository;
import com.bitcom.portal.common.ApiException;
import com.bitcom.portal.employee.EmployeeStatus;
import com.bitcom.portal.employee.Role;
import com.bitcom.portal.employee.entity.Employee;
import com.bitcom.portal.employee.repository.AuditLogRepository;
import com.bitcom.portal.employee.repository.EmployeeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 규칙 3: 외부 호출(BackgroundCheckClient)을 가짜로 주입해 실측에서 본 거동(즉시 완료 / pending / 5xx / 폴링 상한)을 재현한다. */
class BackgroundCheckServiceTest {
    private static final Instant T0 = Instant.parse("2026-09-03T00:00:00Z");

    private final BackgroundCheckRepository checks = mock(BackgroundCheckRepository.class);
    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final AuditLogRepository audits = mock(AuditLogRepository.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final BgcProperties props = new BgcProperties("http://x", 1000, 1000, 3, 0, 5000, 240, 40);

    /** 호출 순서대로 결과를 돌려주는 가짜 클라이언트 */
    private final List<Result> scripted = new ArrayList<>();
    private final List<ExternalCreateRequest> sentPayloads = new ArrayList<>();
    private final BackgroundCheckClient client = new BackgroundCheckClient() {
        public Result create(ExternalCreateRequest r) { sentPayloads.add(r); return scripted.remove(0); }
        public Result get(String checkId) { return scripted.remove(0); }
    };

    private Employee emp;
    private long seq = 100;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        emp = Employee.builder().employeeId("EMP-003").name("남궁서준").lastName("남").firstName("궁서준")
                .birthDate(LocalDate.of(1988, 7, 21)).role(Role.EMPLOYEE).status(EmployeeStatus.ACTIVE)
                .passwordHash("h").createdAt(T0).updatedAt(T0).build();
        when(employees.findById("EMP-003")).thenReturn(Optional.of(emp));
        when(checks.save(any())).thenAnswer(inv -> { BackgroundCheck b = inv.getArgument(0); if (b.getId() == null) b.setId(++seq); return b; });
        // TransactionTemplate 은 콜백을 그대로 실행
        when(tx.execute(any())).thenAnswer(inv -> ((TransactionCallback<Object>) inv.getArgument(0)).doInTransaction(null));
        doAnswer(inv -> { ((Consumer<Object>) inv.getArgument(0)).accept(null); return null; })
                .when(tx).executeWithoutResult(any());
    }

    private BackgroundCheckService service(Instant now) {
        return new BackgroundCheckService(checks, employees, audits, client, props, Clock.fixed(now, ZoneOffset.UTC), tx);
    }

    private static ExternalCheck ext(String checkId, String status) {
        boolean fin = !"pending".equals(status);
        return new ExternalCheck(checkId, "EMP-003", status, fin ? false : null, fin ? true : null, fin ? true : null,
                fin ? "good" : null, "2026-09-03T00:00:00Z", fin ? "2026-09-03T00:00:20Z" : null, null, null);
    }

    @Test
    void request_sends_parsed_names_and_saves_pending_row_with_checkId() {
        scripted.add(new Result(200, ext("CHK-1", "pending"), null, 1));

        BgcSummary s = service(T0).request("EMP-003", "ADMIN-001");

        assertThat(sentPayloads).hasSize(1);
        assertThat(sentPayloads.get(0).lastName()).isEqualTo("남");
        assertThat(sentPayloads.get(0).firstName()).isEqualTo("궁서준");
        assertThat(sentPayloads.get(0).dateOfBirth()).isEqualTo("1988-07-21");
        assertThat(s.status()).isEqualTo(BgcStatus.PENDING);
        assertThat(s.checkId()).isEqualTo("CHK-1");
        verify(audits).save(any());
    }

    @Test
    void request_immediate_final_response_is_stored_as_final() {
        scripted.add(new Result(200, ext("CHK-2", "flagged"), null, 1));
        BgcSummary s = service(T0).request("EMP-003", "ADMIN-001");
        assertThat(s.status()).isEqualTo(BgcStatus.FLAGGED);
        assertThat(s.completedAt()).isEqualTo(Instant.parse("2026-09-03T00:00:20Z"));
    }

    @Test
    void request_failure_after_retries_is_recorded_as_FAILED_row() {
        scripted.add(new Result(503, null, "HTTP 503 overloaded", 3));
        BgcSummary s = service(T0).request("EMP-003", "ADMIN-001");
        assertThat(s.status()).isEqualTo(BgcStatus.FAILED);
        assertThat(s.checkId()).isNull();
        assertThat(s.failureReason()).contains("3회").contains("503");
    }

    @Test
    void request_rejected_without_birth_date_and_while_pending_exists() {
        emp.setBirthDate(null);
        assertThatThrownBy(() -> service(T0).request("EMP-003", "ADMIN-001"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("NO_BIRTH_DATE");

        emp.setBirthDate(LocalDate.of(1988, 7, 21));
        when(checks.existsByEmployeeIdAndStatus("EMP-003", BgcStatus.PENDING)).thenReturn(true);
        assertThatThrownBy(() -> service(T0).request("EMP-003", "ADMIN-001"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("ALREADY_PENDING");
        assertThat(sentPayloads).isEmpty(); // 외부 호출 자체가 나가지 않음
    }

    @Test
    void poll_keeps_pending_on_5xx_and_finalizes_on_200_final() {
        BackgroundCheck b = pending("CHK-3", T0);
        when(checks.findAllByStatus(BgcStatus.PENDING)).thenReturn(List.of(b));
        when(checks.findById(b.getId())).thenReturn(Optional.of(b));

        scripted.add(new Result(500, null, "HTTP 500", 1));
        service(T0.plusSeconds(5)).pollPending();
        assertThat(b.getStatus()).isEqualTo(BgcStatus.PENDING);
        assertThat(b.getPollCount()).isEqualTo(1);
        assertThat(b.getFailureReason()).contains("500");

        scripted.add(new Result(200, ext("CHK-3", "clear"), null, 1));
        service(T0.plusSeconds(10)).pollPending();
        assertThat(b.getStatus()).isEqualTo(BgcStatus.CLEAR);
        assertThat(b.getCreditScore()).isEqualTo("good");
        assertThat(b.getFailureReason()).isNull();
    }

    @Test
    void poll_marks_TIMEOUT_when_elapsed_exceeds_limit() {
        BackgroundCheck b = pending("CHK-4", T0);
        when(checks.findAllByStatus(BgcStatus.PENDING)).thenReturn(List.of(b));
        when(checks.findById(b.getId())).thenReturn(Optional.of(b));
        scripted.add(new Result(200, ext("CHK-4", "pending"), null, 1));

        service(T0.plusSeconds(240)).pollPending(); // poll-max-elapsed-seconds = 240

        assertThat(b.getStatus()).isEqualTo(BgcStatus.TIMEOUT);
        assertThat(b.getFailureReason()).contains("240s");
    }

    @Test
    void refresh_only_allowed_for_TIMEOUT_and_uses_GET_only() {
        BackgroundCheck b = pending("CHK-5", T0);
        b.setStatus(BgcStatus.TIMEOUT);
        when(checks.findById(b.getId())).thenReturn(Optional.of(b));
        scripted.add(new Result(200, ext("CHK-5", "clear"), null, 1));

        BgcSummary s = service(T0.plusSeconds(600)).refresh(b.getId(), "ADMIN-001");

        assertThat(s.status()).isEqualTo(BgcStatus.CLEAR);
        assertThat(sentPayloads).isEmpty(); // POST 없음

        BackgroundCheck done = pending("CHK-6", T0);
        done.setStatus(BgcStatus.CLEAR);
        when(checks.findById(done.getId())).thenReturn(Optional.of(done));
        assertThatThrownBy(() -> service(T0).refresh(done.getId(), "ADMIN-001"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("NOT_TIMEOUT");
    }

    @Test
    void detail_view_writes_audit_log_without_sensitive_values() {
        BackgroundCheck b = pending("CHK-7", T0);
        b.setStatus(BgcStatus.FLAGGED);
        b.setCriminalRecord(true);
        when(checks.findById(b.getId())).thenReturn(Optional.of(b));

        service(T0).detail(b.getId(), "ADMIN-001");

        ArgumentCaptor<com.bitcom.portal.employee.entity.AuditLog> cap = ArgumentCaptor.forClass(com.bitcom.portal.employee.entity.AuditLog.class);
        verify(audits).save(cap.capture());
        assertThat(cap.getValue().getDetail()).containsKeys("bgcId", "checkId").doesNotContainKey("criminalRecord");
    }

    private BackgroundCheck pending(String checkId, Instant requestedAt) {
        return BackgroundCheck.builder().id(++seq).employeeId("EMP-003").checkId(checkId).status(BgcStatus.PENDING)
                .requestedBy("ADMIN-001").requestedAt(requestedAt).pollCount(0).build();
    }
}
