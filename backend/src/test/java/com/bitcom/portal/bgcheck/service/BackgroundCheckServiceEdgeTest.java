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
import com.bitcom.portal.employee.dto.EmployeeDtos.BgcSummaryOfEmployee;
import com.bitcom.portal.employee.entity.Employee;
import com.bitcom.portal.employee.repository.AuditLogRepository;
import com.bitcom.portal.employee.repository.EmployeeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

/** BackgroundCheckService 경계·실패 조건. props: 폴링 상한 240s 또는 3회 (횟수 경계를 작게 잡음) */
class BackgroundCheckServiceEdgeTest {
    private static final Instant T0 = Instant.parse("2026-09-03T00:00:00Z");

    private final BackgroundCheckRepository checks = mock(BackgroundCheckRepository.class);
    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final AuditLogRepository audits = mock(AuditLogRepository.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final BgcProperties props = new BgcProperties("http://x", 1000, 1000, 3, 0, 5000, 240, 3);

    private final List<Result> scripted = new ArrayList<>();
    private final List<String> gets = new ArrayList<>();
    private final List<ExternalCreateRequest> posts = new ArrayList<>();
    private final BackgroundCheckClient client = new BackgroundCheckClient() {
        public Result create(ExternalCreateRequest r) { posts.add(r); return scripted.remove(0); }
        public Result get(String checkId) { gets.add(checkId); return scripted.remove(0); }
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
        when(tx.execute(any())).thenAnswer(inv -> ((TransactionCallback<Object>) inv.getArgument(0)).doInTransaction(null));
        doAnswer(inv -> { ((Consumer<Object>) inv.getArgument(0)).accept(null); return null; }).when(tx).executeWithoutResult(any());
    }

    private BackgroundCheckService at(Instant now) {
        return new BackgroundCheckService(checks, employees, audits, client, props, Clock.fixed(now, ZoneOffset.UTC), tx);
    }

    private static ExternalCheck ext(String checkId, String status, String completedAt) {
        boolean fin = !"pending".equals(status);
        return new ExternalCheck(checkId, "EMP-003", status, fin ? false : null, fin ? true : null, fin ? true : null,
                fin ? "good" : null, "2026-09-03T00:00:00Z", completedAt, null, null);
    }

    private BackgroundCheck pending(String checkId, Instant requestedAt, int pollCount) {
        BackgroundCheck b = BackgroundCheck.builder().id(++seq).employeeId("EMP-003").checkId(checkId).status(BgcStatus.PENDING)
                .requestedBy("ADMIN-001").requestedAt(requestedAt).pollCount(pollCount).build();
        when(checks.findById(b.getId())).thenReturn(Optional.of(b));
        when(checks.findAllByStatus(BgcStatus.PENDING)).thenReturn(List.of(b));
        return b;
    }

    // ---------- 요청 실패 ----------

    @Test
    void request_for_unknown_or_resigned_employee_is_rejected_before_external_call() {
        when(employees.findById("EMP-404")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> at(T0).request("EMP-404", "ADMIN-001")).isInstanceOf(ApiException.class).extracting("code").isEqualTo("NOT_FOUND");

        emp.setStatus(EmployeeStatus.RESIGNED);
        assertThatThrownBy(() -> at(T0).request("EMP-003", "ADMIN-001")).isInstanceOf(ApiException.class).extracting("code").isEqualTo("RESIGNED");
        assertThat(posts).isEmpty();
        verifyNoInteractions(audits);
    }

    @Test
    void request_ok_but_missing_checkId_is_recorded_as_FAILED_not_PENDING() {
        // 명세 밖 응답(2xx 인데 checkId 없음) → PENDING 으로 두면 폴링이 null 을 GET 하게 되므로 FAILED
        scripted.add(new Result(200, ext(null, "pending", null), null, 1));
        BgcSummary s = at(T0).request("EMP-003", "ADMIN-001");
        assertThat(s.status()).isEqualTo(BgcStatus.FAILED);
        assertThat(s.failureReason()).contains("checkId");
    }

    @Test
    void request_network_error_zero_status_is_FAILED_with_attempts() {
        scripted.add(new Result(0, null, "timeout/network: SocketTimeoutException", 3));
        BgcSummary s = at(T0).request("EMP-003", "ADMIN-001");
        assertThat(s.status()).isEqualTo(BgcStatus.FAILED);
        assertThat(s.failureReason()).contains("3회").contains("SocketTimeout");
    }

    @Test
    void request_final_with_unparsable_completedAt_falls_back_to_request_time() {
        scripted.add(new Result(200, ext("CHK-1", "clear", "not-a-date"), null, 1));
        BgcSummary s = at(T0).request("EMP-003", "ADMIN-001");
        assertThat(s.status()).isEqualTo(BgcStatus.CLEAR);
        assertThat(s.completedAt()).isEqualTo(T0);
    }

    // ---------- 폴링 경계 ----------

    @Test
    void poll_404_marks_FAILED_immediately_regardless_of_limits() {
        BackgroundCheck b = pending("CHK-2", T0, 0);
        scripted.add(new Result(404, null, "HTTP 404", 1));
        at(T0.plusSeconds(5)).pollPending();
        assertThat(b.getStatus()).isEqualTo(BgcStatus.FAILED);
        assertThat(b.getPollCount()).isEqualTo(1);
    }

    @Test
    void poll_network_error_keeps_pending_and_records_reason() {
        BackgroundCheck b = pending("CHK-3", T0, 0);
        scripted.add(new Result(0, null, "timeout/network: SocketTimeoutException", 1));
        at(T0.plusSeconds(5)).pollPending();
        assertThat(b.getStatus()).isEqualTo(BgcStatus.PENDING);
        assertThat(b.getFailureReason()).contains("SocketTimeout");
        assertThat(b.getLastPolledAt()).isEqualTo(T0.plusSeconds(5));
    }

    @Test
    void poll_elapsed_one_second_below_limit_stays_pending_at_limit_becomes_TIMEOUT() {
        BackgroundCheck b = pending("CHK-4", T0, 0);
        scripted.add(new Result(200, ext("CHK-4", "pending", null), null, 1));
        at(T0.plusSeconds(239)).pollPending();
        assertThat(b.getStatus()).isEqualTo(BgcStatus.PENDING);

        scripted.add(new Result(200, ext("CHK-4", "pending", null), null, 1));
        at(T0.plusSeconds(240)).pollPending();
        assertThat(b.getStatus()).isEqualTo(BgcStatus.TIMEOUT);
        assertThat(b.getFailureReason()).contains("240s").contains("2회");
    }

    @Test
    void poll_attempt_limit_triggers_TIMEOUT_even_when_elapsed_is_small() {
        BackgroundCheck b = pending("CHK-5", T0, 2); // 이미 2회, 상한 3회
        scripted.add(new Result(503, null, "HTTP 503", 1));
        at(T0.plusSeconds(15)).pollPending();
        assertThat(b.getPollCount()).isEqualTo(3);
        assertThat(b.getStatus()).isEqualTo(BgcStatus.TIMEOUT);
        assertThat(b.getFailureReason()).contains("3회").contains("503");
    }

    @Test
    void poll_final_response_on_last_allowed_attempt_wins_over_timeout() {
        BackgroundCheck b = pending("CHK-6", T0, 2);
        scripted.add(new Result(200, ext("CHK-6", "flagged", "2026-09-03T00:03:00Z"), null, 1));
        at(T0.plusSeconds(300)).pollPending(); // 경과·횟수 모두 상한 도달이지만 최종 응답이 먼저
        assertThat(b.getStatus()).isEqualTo(BgcStatus.FLAGGED);
        assertThat(b.getCompletedAt()).isEqualTo(Instant.parse("2026-09-03T00:03:00Z"));
    }

    @Test
    void poll_skips_row_that_became_final_between_select_and_apply() {
        BackgroundCheck b = pending("CHK-7", T0, 0);
        b.setStatus(BgcStatus.CLEAR); // 목록 조회 후 다른 경로로 완료된 상황
        scripted.add(new Result(200, ext("CHK-7", "pending", null), null, 1));
        at(T0.plusSeconds(5)).pollPending();
        assertThat(b.getPollCount()).isZero();
        assertThat(b.getStatus()).isEqualTo(BgcStatus.CLEAR);
    }

    @Test
    void poll_with_no_pending_rows_makes_no_external_call() {
        when(checks.findAllByStatus(BgcStatus.PENDING)).thenReturn(List.of());
        at(T0).pollPending();
        assertThat(gets).isEmpty();
    }

    // ---------- 재확인·상세·삭제 ----------

    @Test
    void refresh_unknown_id_is_not_found() {
        when(checks.findById(999L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> at(T0).refresh(999L, "ADMIN-001")).isInstanceOf(ApiException.class).extracting("code").isEqualTo("NOT_FOUND");
    }

    @Test
    void refresh_still_pending_or_5xx_keeps_TIMEOUT_with_reason() {
        BackgroundCheck b = pending("CHK-8", T0, 3);
        b.setStatus(BgcStatus.TIMEOUT);

        scripted.add(new Result(200, ext("CHK-8", "pending", null), null, 1));
        assertThat(at(T0.plusSeconds(600)).refresh(b.getId(), "ADMIN-001").status()).isEqualTo(BgcStatus.TIMEOUT);
        assertThat(b.getFailureReason()).contains("pending");

        scripted.add(new Result(503, null, "HTTP 503", 1));
        assertThat(at(T0.plusSeconds(700)).refresh(b.getId(), "ADMIN-001").status()).isEqualTo(BgcStatus.TIMEOUT);
        assertThat(b.getFailureReason()).contains("503");
        assertThat(b.getPollCount()).isEqualTo(5);
        assertThat(gets).containsExactly("CHK-8", "CHK-8");
    }

    @Test
    void refresh_rejected_for_PENDING_FAILED_CLEAR() {
        for (BgcStatus st : List.of(BgcStatus.PENDING, BgcStatus.FAILED, BgcStatus.CLEAR)) {
            BackgroundCheck b = pending("CHK-9", T0, 0);
            b.setStatus(st);
            assertThatThrownBy(() -> at(T0).refresh(b.getId(), "ADMIN-001")).isInstanceOf(ApiException.class).extracting("code").isEqualTo("NOT_TIMEOUT");
        }
        assertThat(gets).isEmpty();
    }

    @Test
    void detail_unknown_writes_no_audit() {
        when(checks.findById(999L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> at(T0).detail(999L, "ADMIN-001")).isInstanceOf(ApiException.class).extracting("code").isEqualTo("NOT_FOUND");
        verifyNoInteractions(audits);
    }

    @Test
    void list_for_unknown_employee_is_not_found() {
        when(employees.findById("EMP-404")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> at(T0).list("EMP-404")).isInstanceOf(ApiException.class).extracting("code").isEqualTo("NOT_FOUND");
    }

    @Test
    void summaryOf_empty_and_with_pending() {
        when(checks.findAllByEmployeeIdOrderByRequestedAtDesc("EMP-003")).thenReturn(List.of());
        assertThat(at(T0).summaryOf("EMP-003")).isEqualTo(new BgcSummaryOfEmployee(0, null, false));

        BackgroundCheck p = pending("CHK-10", T0, 0);
        BackgroundCheck done = pending("CHK-11", T0.minusSeconds(100), 2);
        done.setStatus(BgcStatus.CLEAR);
        when(checks.findAllByEmployeeIdOrderByRequestedAtDesc("EMP-003")).thenReturn(List.of(p, done));
        assertThat(at(T0).summaryOf("EMP-003")).isEqualTo(new BgcSummaryOfEmployee(2, BgcStatus.PENDING, true));
    }

    @Test
    void deleteAllForEmployee_returns_ids_and_is_empty_when_none() {
        BackgroundCheck a = pending("CHK-12", T0, 0);
        BackgroundCheck b = pending("CHK-13", T0, 0);
        when(checks.findAllByEmployeeId("EMP-003")).thenReturn(List.of(a, b));
        assertThat(at(T0).deleteAllForEmployee("EMP-003")).containsExactly(a.getId(), b.getId());
        verify(checks).deleteAll(List.of(a, b));

        when(checks.findAllByEmployeeId("EMP-999")).thenReturn(List.of());
        assertThat(at(T0).deleteAllForEmployee("EMP-999")).isEmpty();
    }
}
