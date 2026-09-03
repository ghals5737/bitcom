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
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** F2~F6: 직원 조회/수정/생성/퇴사/재발급 + 변경 이력 + 감사 로그 */
@Service
@RequiredArgsConstructor
public class EmployeeService {
    private final EmployeeRepository employees;
    private final SessionRepository sessions;
    private final EmployeeChangeLogRepository changeLogs;
    private final AuditLogRepository audits;
    private final BackgroundCheckService backgroundChecks;
    private final PasswordEncoder passwordEncoder;
    private final TokenGenerator tokens;
    private final Clock clock;

    /** F4 성명 파싱 규칙: 첫 글자 = 성, 나머지 = 이름. 복성 사전 없음 (planning.md). */
    public static String[] parseName(String name) {
        String t = name.trim();
        return new String[]{t.substring(0, 1), t.substring(1)};
    }

    // ---------- 조회 ----------

    @Transactional(readOnly = true)
    public MeProfile me(String employeeId) {
        return MeProfile.from(get(employeeId));
    }

    @Transactional(readOnly = true)
    public List<EmployeeListItem> list(String status) {
        List<Employee> rows = (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status))
                ? employees.findAllByOrderByEmployeeIdAsc()
                : employees.findAllByStatusOrderByEmployeeIdAsc(parseStatus(status));
        return rows.stream()
                .map(e -> EmployeeListItem.from(e, backgroundChecks.latestStatus(e.getEmployeeId()).orElse(null)))
                .toList();
    }

    @Transactional(readOnly = true)
    public EmployeeDetail detail(String employeeId) {
        return toDetail(get(employeeId));
    }

    @Transactional(readOnly = true)
    public List<HistoryItem> history(String employeeId) {
        get(employeeId);
        return Stream.concat(
                        changeLogs.findAllByEmployeeIdOrderByChangedAtDesc(employeeId).stream().map(HistoryItem::change),
                        audits.findAllByTargetEmployeeIdOrderByCreatedAtDesc(employeeId).stream().map(HistoryItem::audit))
                .sorted(Comparator.comparing(HistoryItem::at).reversed())
                .toList();
    }

    // ---------- 수정 ----------

    /** F3: 직원 본인은 phone/address 만. 즉시 반영 + 이력. */
    @Transactional
    public MeProfile updateMe(String employeeId, UpdateMeRequest req) {
        Employee e = get(employeeId);
        List<Change> changes = List.of(
                new Change("phone", e.getPhone(), req.phone(), e::setPhone),
                new Change("address", e.getAddress(), req.address(), e::setAddress));
        apply(e, changes, employeeId);
        return MeProfile.from(e);
    }

    /** F5: 관리자는 전 항목. null 필드는 변경 없음. */
    @Transactional
    public EmployeeDetail adminUpdate(String employeeId, AdminUpdateEmployeeRequest req, String actor) {
        Employee e = get(employeeId);
        if (e.getStatus() == EmployeeStatus.RESIGNED) {
            throw ApiException.conflict("RESIGNED", "퇴사 처리된 직원은 수정할 수 없습니다.");
        }
        List<Change> changes = new ArrayList<>();
        if (req.name() != null) {
            changes.add(new Change("name", e.getName(), req.name(), v -> {
                String[] p = parseName(v);
                e.setName(v);
                e.setLastName(p[0]);
                e.setFirstName(p[1]);
            }));
        }
        if (req.birthDate() != null) changes.add(new Change("birthDate", str(e.getBirthDate()), str(req.birthDate()), v -> e.setBirthDate(LocalDate.parse(v))));
        if (req.phone() != null) changes.add(new Change("phone", e.getPhone(), req.phone(), e::setPhone));
        if (req.address() != null) changes.add(new Change("address", e.getAddress(), req.address(), e::setAddress));
        if (req.department() != null) changes.add(new Change("department", e.getDepartment(), req.department(), e::setDepartment));
        if (req.position() != null) changes.add(new Change("position", e.getPosition(), req.position(), e::setPosition));
        if (req.hireDate() != null) changes.add(new Change("hireDate", str(e.getHireDate()), str(req.hireDate()), v -> e.setHireDate(LocalDate.parse(v))));
        if (req.role() != null) changes.add(new Change("role", e.getRole().name(), req.role().name(), v -> e.setRole(Role.valueOf(v))));
        apply(e, changes, actor);
        return toDetail(e);
    }

    // ---------- 생성 / 퇴사 / 재발급 ----------

    @Transactional
    public CreateEmployeeResult create(CreateEmployeeRequest req, String actor) {
        String id = nextEmployeeId();
        String temp = tokens.temporaryPassword();
        String[] p = parseName(req.name());
        Instant now = clock.instant();
        Employee e = Employee.builder()
                .employeeId(id).name(req.name().trim()).lastName(p[0]).firstName(p[1])
                .birthDate(req.birthDate()).phone(blankToNull(req.phone())).address(blankToNull(req.address()))
                .department(blankToNull(req.department())).position(blankToNull(req.position())).hireDate(req.hireDate())
                .role(req.role()).status(EmployeeStatus.ACTIVE).resignedAt(null)
                .passwordHash(passwordEncoder.encode(temp)).mustChangePassword(true)
                .failedLoginCount(0).locked(false).createdAt(now).updatedAt(now)
                .build();
        employees.save(e);
        audit(actor, AuditAction.EMPLOYEE_CREATED, id, Map.of("role", e.getRole().name(), "hasBirthDate", e.getBirthDate() != null));
        return new CreateEmployeeResult(toDetail(e), temp);
    }

    /** F6/F8: 즉시 퇴사 + 세션 삭제 + Background Check 결과 삭제 + 감사 로그. 하나의 트랜잭션. */
    @Transactional
    public EmployeeDetail resign(String employeeId, LocalDate resignedAt, String actor) {
        Employee e = get(employeeId);
        if (e.getEmployeeId().equals(actor)) {
            throw ApiException.badRequest("SELF_RESIGN", "본인 계정은 퇴사 처리할 수 없습니다.");
        }
        if (e.getStatus() == EmployeeStatus.RESIGNED) {
            throw ApiException.conflict("ALREADY_RESIGNED", "이미 퇴사 처리된 직원입니다.");
        }
        Instant now = clock.instant();
        e.setStatus(EmployeeStatus.RESIGNED);
        e.setResignedAt(resignedAt != null ? resignedAt : LocalDate.ofInstant(now, ZoneOffset.UTC));
        e.setUpdatedAt(now);
        sessions.deleteAllByEmployeeId(employeeId);
        audit(actor, AuditAction.EMPLOYEE_RESIGNED, employeeId, Map.of("resignedAt", e.getResignedAt().toString(), "sessionsRevoked", true));
        List<Long> deleted = backgroundChecks.deleteAllForEmployee(employeeId);
        if (!deleted.isEmpty()) {
            audit(actor, AuditAction.BGCHECK_DELETED, employeeId, Map.of("deletedCount", deleted.size(), "bgcIds", deleted));
        }
        return toDetail(e);
    }

    /** N2: 임시 비밀번호 재발급 = 잠금 해제 + 변경 강제 + 세션 삭제 */
    @Transactional
    public ResetPasswordResult resetPassword(String employeeId, String actor) {
        Employee e = get(employeeId);
        if (e.getStatus() == EmployeeStatus.RESIGNED) {
            throw ApiException.conflict("RESIGNED", "퇴사 처리된 직원의 비밀번호는 재발급할 수 없습니다.");
        }
        String temp = tokens.temporaryPassword();
        e.setPasswordHash(passwordEncoder.encode(temp));
        e.setMustChangePassword(true);
        e.setLocked(false);
        e.setFailedLoginCount(0);
        e.setUpdatedAt(clock.instant());
        sessions.deleteAllByEmployeeId(employeeId);
        audit(actor, AuditAction.PASSWORD_RESET, employeeId, Map.of("unlocked", true));
        return new ResetPasswordResult(temp, toDetail(e));
    }

    // ---------- 내부 ----------

    String nextEmployeeId() {
        int next = employees.findMaxEmpNumber().orElse(0) + 1;
        return String.format("EMP-%03d", next);
    }

    private Employee get(String employeeId) {
        return employees.findById(employeeId)
                .orElseThrow(() -> ApiException.notFound("NOT_FOUND", "직원을 찾을 수 없습니다: " + employeeId));
    }

    private EmployeeDetail toDetail(Employee e) {
        return EmployeeDetail.from(e, backgroundChecks.summaryOf(e.getEmployeeId()));
    }

    private record Change(String field, String oldValue, String newValue, Consumer<String> setter) {}

    private void apply(Employee e, List<Change> changes, String actor) {
        Instant now = clock.instant();
        List<String> changed = new ArrayList<>();
        for (Change c : changes) {
            String next = blankToNull(c.newValue());
            if (c.newValue() == null || Objects.equals(c.oldValue(), next)) continue;
            c.setter().accept(next);
            changeLogs.save(EmployeeChangeLog.builder().employeeId(e.getEmployeeId()).changedBy(actor)
                    .field(c.field()).oldValue(c.oldValue()).newValue(next).changedAt(now).build());
            changed.add(c.field());
        }
        if (!changed.isEmpty()) {
            e.setUpdatedAt(now);
            audit(actor, AuditAction.EMPLOYEE_UPDATED, e.getEmployeeId(), Map.of("fields", changed));
        }
    }

    private void audit(String actor, AuditAction action, String target, Map<String, Object> detail) {
        audits.save(AuditLog.builder().actorId(actor).action(action).targetEmployeeId(target)
                .detail(new LinkedHashMap<>(detail)).createdAt(clock.instant()).build());
    }

    private static EmployeeStatus parseStatus(String s) {
        try {
            return EmployeeStatus.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("BAD_STATUS", "status 는 ALL, ACTIVE, RESIGNED 중 하나여야 합니다.");
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static String str(LocalDate d) {
        return d == null ? null : d.toString();
    }
}
