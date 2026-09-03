package com.bitcom.portal.bgcheck.service;

import com.bitcom.portal.bgcheck.BgcStatus;
import com.bitcom.portal.bgcheck.client.BackgroundCheckClient;
import com.bitcom.portal.bgcheck.client.BackgroundCheckClient.Result;
import com.bitcom.portal.bgcheck.client.BgcProperties;
import com.bitcom.portal.bgcheck.dto.BgcDtos.BgcDetail;
import com.bitcom.portal.bgcheck.dto.BgcDtos.BgcSummary;
import com.bitcom.portal.bgcheck.dto.BgcDtos.ExternalCheck;
import com.bitcom.portal.bgcheck.dto.BgcDtos.ExternalCreateRequest;
import com.bitcom.portal.bgcheck.entity.BackgroundCheck;
import com.bitcom.portal.bgcheck.repository.BackgroundCheckRepository;
import com.bitcom.portal.common.ApiException;
import com.bitcom.portal.employee.AuditAction;
import com.bitcom.portal.employee.EmployeeStatus;
import com.bitcom.portal.employee.dto.EmployeeDtos.BgcSummaryOfEmployee;
import com.bitcom.portal.employee.entity.AuditLog;
import com.bitcom.portal.employee.entity.Employee;
import com.bitcom.portal.employee.repository.AuditLogRepository;
import com.bitcom.portal.employee.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * F7/F8/N3: 요청 → (외부 POST) → 행 저장 → 폴링 워커가 GET 으로 갱신.
 * 규칙 3: Clock, BackgroundCheckClient 주입. 외부 호출은 트랜잭션 밖에서 수행하고 결과만 트랜잭션 안에서 저장한다.
 * 같은 클래스 안의 @Transactional 메서드 호출은 프록시를 타지 않으므로 저장 구간은 TransactionTemplate 으로 감싼다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BackgroundCheckService {
    private final BackgroundCheckRepository checks;
    private final EmployeeRepository employees;
    private final AuditLogRepository audits;
    private final BackgroundCheckClient client;
    private final BgcProperties props;
    private final Clock clock;
    private final TransactionTemplate tx;

    // ---------- 조회 ----------

    @Transactional(readOnly = true)
    public List<BgcSummary> list(String employeeId) {
        requireEmployee(employeeId);
        return checks.findAllByEmployeeIdOrderByRequestedAtDesc(employeeId).stream().map(BgcSummary::from).toList();
    }

    /** 상세 = 민감정보 열람. 감사 로그 필수 (N4). */
    @Transactional
    public BgcDetail detail(long bgcId, String actor) {
        BackgroundCheck b = checks.findById(bgcId)
                .orElseThrow(() -> ApiException.notFound("NOT_FOUND", "Background Check 결과를 찾을 수 없습니다."));
        audit(actor, AuditAction.BGCHECK_VIEWED, b.getEmployeeId(), Map.of("bgcId", b.getId(), "checkId", String.valueOf(b.getCheckId())));
        return BgcDetail.from(b);
    }

    @Transactional(readOnly = true)
    public Optional<BgcStatus> latestStatus(String employeeId) {
        return checks.findFirstByEmployeeIdOrderByRequestedAtDesc(employeeId).map(BackgroundCheck::getStatus);
    }

    @Transactional(readOnly = true)
    public BgcSummaryOfEmployee summaryOf(String employeeId) {
        List<BackgroundCheck> all = checks.findAllByEmployeeIdOrderByRequestedAtDesc(employeeId);
        return new BgcSummaryOfEmployee(all.size(),
                all.isEmpty() ? null : all.get(0).getStatus(),
                all.stream().anyMatch(b -> b.getStatus() == BgcStatus.PENDING));
    }

    // ---------- 요청 (F7) ----------

    /**
     * 조건: 재직, 생년월일 있음, 진행 중 건 없음.
     * 외부 POST 는 트랜잭션 밖 → 결과를 별도 트랜잭션으로 저장 (외부 실패가 앱 데이터에 영향 없게).
     */
    public BgcSummary request(String employeeId, String actor) {
        Employee e = requireEmployee(employeeId);
        if (e.getStatus() == EmployeeStatus.RESIGNED) {
            throw ApiException.conflict("RESIGNED", "퇴사 처리된 직원은 조회할 수 없습니다.");
        }
        if (e.getBirthDate() == null) {
            throw ApiException.badRequest("NO_BIRTH_DATE", "생년월일이 확인되지 않은 직원은 Background Check를 요청할 수 없습니다. (외부 API 필수 항목)");
        }
        if (checks.existsByEmployeeIdAndStatus(employeeId, BgcStatus.PENDING)) {
            throw ApiException.conflict("ALREADY_PENDING", "진행 중인 Background Check가 있습니다. 완료된 뒤 다시 요청할 수 있습니다.");
        }
        ExternalCreateRequest payload = new ExternalCreateRequest(e.getEmployeeId(), e.getFirstName(), e.getLastName(), e.getBirthDate().toString());
        Instant requestedAt = clock.instant();
        Result r = client.create(payload);
        return BgcSummary.from(tx.execute(st -> saveRequested(e, payload, requestedAt, r, actor)));
    }

    private BackgroundCheck saveRequested(Employee e, ExternalCreateRequest payload, Instant requestedAt, Result r, String actor) {
        BackgroundCheck b = BackgroundCheck.builder()
                .employeeId(e.getEmployeeId()).status(BgcStatus.PENDING)
                .requestedBy(actor).requestedAt(requestedAt).pollCount(0)
                .requestPayload(new LinkedHashMap<>(Map.of("firstName", payload.firstName(), "lastName", payload.lastName(), "dateOfBirth", payload.dateOfBirth())))
                .build();
        if (r.ok() && (r.body().checkId() == null || r.body().checkId().isBlank())) {
            // 명세 밖 응답: 2xx 인데 checkId 없음 → PENDING 으로 두면 폴링할 대상이 없으므로 FAILED
            b.setStatus(BgcStatus.FAILED);
            b.setFailureReason("POST 응답에 checkId 없음");
        } else if (r.ok()) {
            ExternalCheck ext = r.body();
            b.setCheckId(ext.checkId());
            if (ext.isFinal()) applyFinal(b, ext, requestedAt);
        } else {
            b.setStatus(BgcStatus.FAILED);
            b.setFailureReason("POST 실패 (" + r.attempts() + "회 시도): " + r.error());
        }
        checks.save(b);
        audit(actor, AuditAction.BGCHECK_REQUESTED, e.getEmployeeId(), Map.of("bgcId", b.getId(), "status", b.getStatus().name()));
        return b;
    }

    // ---------- 폴링 (N3) ----------

    /** 스케줄러가 주기적으로 호출. PENDING 건마다 GET 1회. 외부 호출은 트랜잭션 밖. */
    public void pollPending() {
        List<BackgroundCheck> pending = checks.findAllByStatus(BgcStatus.PENDING);
        for (BackgroundCheck b : pending) {
            Result r = client.get(b.getCheckId());
            tx.executeWithoutResult(st -> applyPoll(b.getId(), r));
        }
    }

    private void applyPoll(long bgcId, Result r) {
        BackgroundCheck b = checks.findById(bgcId).orElse(null);
        if (b == null || b.getStatus() != BgcStatus.PENDING) return;
        Instant now = clock.instant();
        b.setPollCount(b.getPollCount() + 1);
        b.setLastPolledAt(now);
        if (r.ok()) {
            if (r.body().isFinal()) {
                applyFinal(b, r.body(), now);
                b.setFailureReason(null);
                return;
            }
        } else if (r.notFound()) {
            b.setStatus(BgcStatus.FAILED);
            b.setFailureReason("GET 404: checkId 를 외부에서 찾을 수 없음");
            return;
        } else {
            b.setFailureReason("마지막 응답 " + r.error());
        }
        // 중단 조건: 경과 시간 또는 횟수 상한
        long elapsed = Duration.between(b.getRequestedAt(), now).getSeconds();
        if (elapsed >= props.pollMaxElapsedSeconds() || b.getPollCount() >= props.pollMaxAttempts()) {
            b.setStatus(BgcStatus.TIMEOUT);
            b.setFailureReason("폴링 상한 초과 (" + elapsed + "s, " + b.getPollCount() + "회)"
                    + (r.ok() ? "" : ", 마지막 응답 " + r.error()));
        }
    }

    /** TIMEOUT 건 재확인: GET 만 다시 (POST 없음) */
    public BgcSummary refresh(long bgcId, String actor) {
        BackgroundCheck b = checks.findById(bgcId)
                .orElseThrow(() -> ApiException.notFound("NOT_FOUND", "Background Check 결과를 찾을 수 없습니다."));
        if (b.getStatus() != BgcStatus.TIMEOUT) {
            throw ApiException.conflict("NOT_TIMEOUT", "TIMEOUT 상태인 건만 재확인할 수 있습니다.");
        }
        Result r = client.get(b.getCheckId());
        return BgcSummary.from(tx.execute(st -> applyRefresh(bgcId, r)));
    }

    private BackgroundCheck applyRefresh(long bgcId, Result r) {
        BackgroundCheck b = checks.findById(bgcId).orElseThrow();
        Instant now = clock.instant();
        b.setPollCount(b.getPollCount() + 1);
        b.setLastPolledAt(now);
        if (r.ok() && r.body().isFinal()) {
            applyFinal(b, r.body(), now);
            b.setFailureReason(null);
        } else if (r.ok()) {
            b.setFailureReason("재확인: 아직 pending");
        } else {
            b.setFailureReason("재확인 실패: " + r.error());
        }
        return b;
    }

    // ---------- 퇴사 시 삭제 (F8) ----------

    @Transactional
    public List<Long> deleteAllForEmployee(String employeeId) {
        List<BackgroundCheck> rows = checks.findAllByEmployeeId(employeeId);
        List<Long> ids = rows.stream().map(BackgroundCheck::getId).toList();
        checks.deleteAll(rows);
        return ids;
    }

    // ---------- 내부 ----------

    private void applyFinal(BackgroundCheck b, ExternalCheck ext, Instant fallbackCompletedAt) {
        b.setStatus(ext.toStatus());
        b.setCriminalRecord(ext.criminalRecord());
        b.setEducationVerified(ext.educationVerified());
        b.setEmploymentVerified(ext.employmentVerified());
        b.setCreditScore(ext.creditScore());
        b.setCompletedAt(parseInstant(ext.completedAt()).orElse(fallbackCompletedAt));
    }

    private static Optional<Instant> parseInstant(String iso) {
        try {
            return iso == null ? Optional.empty() : Optional.of(Instant.parse(iso));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Employee requireEmployee(String employeeId) {
        return employees.findById(employeeId)
                .orElseThrow(() -> ApiException.notFound("NOT_FOUND", "직원을 찾을 수 없습니다: " + employeeId));
    }

    private void audit(String actor, AuditAction action, String target, Map<String, Object> detail) {
        audits.save(AuditLog.builder().actorId(actor).action(action).targetEmployeeId(target)
                .detail(new LinkedHashMap<>(detail)).createdAt(clock.instant()).build());
    }
}
