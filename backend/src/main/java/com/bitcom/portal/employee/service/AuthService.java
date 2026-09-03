package com.bitcom.portal.employee.service;

import com.bitcom.portal.common.ApiException;
import com.bitcom.portal.common.AppProperties;
import com.bitcom.portal.common.AuthenticatedUser;
import com.bitcom.portal.common.TokenGenerator;
import com.bitcom.portal.employee.AuditAction;
import com.bitcom.portal.employee.EmployeeStatus;
import com.bitcom.portal.employee.entity.AuditLog;
import com.bitcom.portal.employee.entity.Employee;
import com.bitcom.portal.employee.entity.Session;
import com.bitcom.portal.employee.repository.AuditLogRepository;
import com.bitcom.portal.employee.repository.EmployeeRepository;
import com.bitcom.portal.employee.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * N1/N2: 로그인, 세션 검증·연장, 잠금, 비밀번호 변경.
 * 규칙 3: Clock, TokenGenerator, PasswordEncoder 는 주입.
 */
@Service
@RequiredArgsConstructor
public class AuthService {
    private final EmployeeRepository employees;
    private final SessionRepository sessions;
    private final AuditLogRepository audits;
    private final PasswordEncoder passwordEncoder;
    private final TokenGenerator tokens;
    private final Clock clock;
    private final AppProperties props;

    /**
     * noRollbackFor: 비밀번호 불일치로 ApiException 을 던져도 failedLoginCount 증가·잠금은 커밋되어야 한다.
     * (없으면 RuntimeException 롤백으로 카운트가 매번 0 으로 돌아가 잠금이 영원히 안 걸린다 — DB 확인으로 발견)
     */
    @Transactional(noRollbackFor = ApiException.class)
    public Session login(String rawEmployeeId, String password) {
        String employeeId = rawEmployeeId.trim().toUpperCase();
        Employee e = employees.findById(employeeId)
                .orElseThrow(() -> ApiException.unauthorized("INVALID_CREDENTIALS", "사번 또는 비밀번호가 올바르지 않습니다."));
        if (e.getStatus() == EmployeeStatus.RESIGNED) {
            throw ApiException.forbidden("RESIGNED", "퇴사 처리된 계정입니다. 시스템에 접근할 수 없습니다.");
        }
        if (e.isLocked()) {
            throw ApiException.locked("LOCKED", "로그인 실패 횟수를 초과해 잠긴 계정입니다. 관리자에게 임시 비밀번호 재발급을 요청하세요.");
        }
        Instant now = clock.instant();
        if (!passwordEncoder.matches(password, e.getPasswordHash())) {
            int threshold = props.auth().lockThreshold();
            e.setFailedLoginCount(e.getFailedLoginCount() + 1);
            e.setUpdatedAt(now);
            if (e.getFailedLoginCount() >= threshold) {
                e.setLocked(true);
                sessions.deleteAllByEmployeeId(e.getEmployeeId());
                audits.save(AuditLog.builder().actorId(e.getEmployeeId()).action(AuditAction.ACCOUNT_LOCKED)
                        .targetEmployeeId(e.getEmployeeId()).detail(Map.of("failedLoginCount", e.getFailedLoginCount())).createdAt(now).build());
                throw ApiException.locked("LOCKED", "로그인 실패 횟수를 초과해 계정이 잠겼습니다. 관리자에게 임시 비밀번호 재발급을 요청하세요.");
            }
            throw ApiException.unauthorized("INVALID_CREDENTIALS",
                    "사번 또는 비밀번호가 올바르지 않습니다. (" + e.getFailedLoginCount() + "/" + threshold + ")");
        }
        e.setFailedLoginCount(0);
        Session s = Session.builder()
                .sessionId(tokens.sessionId())
                .employeeId(e.getEmployeeId())
                .createdAt(now)
                .lastAccessedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(props.session().idleMinutes())))
                .build();
        return sessions.save(s);
    }

    @Transactional
    public void logout(String sessionId) {
        sessions.deleteById(sessionId);
    }

    /**
     * 세션 검증 + 슬라이딩 연장. 만료·절대상한·퇴사·잠금이면 세션을 지우고 빈 값.
     * 필터가 매 요청마다 호출한다 (F6: 퇴사 처리 = 세션 삭제 → 다음 요청부터 여기서 걸림).
     */
    @Transactional
    public Optional<AuthenticatedUser> resolve(String sessionId) {
        Optional<Session> found = sessions.findById(sessionId);
        if (found.isEmpty()) return Optional.empty();
        Session s = found.get();
        Instant now = clock.instant();
        Instant absoluteLimit = s.getCreatedAt().plus(Duration.ofHours(props.session().absoluteHours()));
        if (now.isAfter(s.getExpiresAt()) || now.isAfter(absoluteLimit)) {
            sessions.delete(s);
            return Optional.empty();
        }
        Optional<Employee> emp = employees.findById(s.getEmployeeId());
        if (emp.isEmpty() || emp.get().getStatus() != EmployeeStatus.ACTIVE || emp.get().isLocked()) {
            sessions.delete(s);
            return Optional.empty();
        }
        s.setLastAccessedAt(now);
        s.setExpiresAt(now.plus(Duration.ofMinutes(props.session().idleMinutes())));
        Employee e = emp.get();
        return Optional.of(new AuthenticatedUser(e.getEmployeeId(), e.getName(), e.getRole(), e.isMustChangePassword(), s.getSessionId()));
    }

    @Transactional
    public Employee changePassword(String employeeId, String currentPassword, String newPassword) {
        Employee e = employees.findById(employeeId)
                .orElseThrow(() -> ApiException.notFound("NOT_FOUND", "직원을 찾을 수 없습니다."));
        if (!passwordEncoder.matches(currentPassword, e.getPasswordHash())) {
            throw ApiException.badRequest("INVALID_CURRENT_PASSWORD", "현재 비밀번호가 올바르지 않습니다.");
        }
        if (currentPassword.equals(newPassword)) {
            throw ApiException.badRequest("SAME_AS_TEMP", "임시(현재) 비밀번호와 다른 비밀번호를 사용해야 합니다.");
        }
        e.setPasswordHash(passwordEncoder.encode(newPassword));
        e.setMustChangePassword(false);
        e.setUpdatedAt(clock.instant());
        return e;
    }

    @Transactional(readOnly = true)
    public Employee getEmployee(String employeeId) {
        return employees.findById(employeeId)
                .orElseThrow(() -> ApiException.notFound("NOT_FOUND", "직원을 찾을 수 없습니다."));
    }
}
