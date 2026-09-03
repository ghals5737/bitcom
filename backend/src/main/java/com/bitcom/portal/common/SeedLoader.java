package com.bitcom.portal.common;

import com.bitcom.portal.employee.EmployeeStatus;
import com.bitcom.portal.employee.Role;
import com.bitcom.portal.employee.entity.Employee;
import com.bitcom.portal.employee.repository.EmployeeRepository;
import com.bitcom.portal.employee.service.EmployeeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * F0: employees 가 비어 있으면 과제 시드 10명 + ADMIN-001 적재.
 * 시드 목록(사번·성명·생년월일)은 InterviewTasks.txt 그대로. 그 외 항목은 임의값.
 * 제출용 계정(ADMIN-001, EMP-001)만 고정 비밀번호 + 변경 강제 해제, 나머지는 임시 비밀번호 상태.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeedLoader implements ApplicationRunner {
    private final EmployeeRepository employees;
    private final PasswordEncoder encoder;
    private final AppProperties props;
    private final Clock clock;

    private record Seed(String id, String name, String birth, String dept, String pos, String hire, String phone, String addr) {}

    private static final List<Seed> SEED = List.of(
            new Seed("EMP-001", "김민준", "1990-03-15", "개발1팀", "선임", "2018-04-02", "010-2000-0001", "서울특별시 강남구"),
            new Seed("EMP-002", "김민준", "1994-11-02", "개발2팀", "주임", "2021-07-01", "010-2000-0002", "경기도 성남시"),
            new Seed("EMP-003", "남궁서준", "1988-07-21", "개발1팀", "책임", "2014-01-06", "010-2000-0003", "서울특별시 송파구"),
            new Seed("EMP-004", "황보라온", "1995-02-09", "디자인팀", "주임", "2022-03-14", "010-2000-0004", "서울특별시 마포구"),
            new Seed("EMP-005", "김솔", "1992-12-30", "QA팀", "선임", "2019-09-02", "010-2000-0005", "인천광역시 연수구"),
            new Seed("EMP-006", "선우진", "1991-05-05", "개발2팀", "선임", "2017-11-13", "010-2000-0006", "경기도 고양시"),
            new Seed("EMP-007", "이서연", null, "경영지원팀", "사원", "2024-02-01", "010-2000-0007", "서울특별시 영등포구"),
            new Seed("EMP-008", "박민준", "1993-08-17", "개발1팀", "주임", "2020-05-18", "010-2000-0008", "경기도 수원시"),
            new Seed("EMP-009", "최지우", "1996-04-03", "디자인팀", "사원", "2023-08-21", "010-2000-0009", "서울특별시 관악구"),
            new Seed("EMP-010", "정하윤", "1989-10-11", "QA팀", "책임", "2013-06-03", "010-2000-0010", "서울특별시 동작구")
    );

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (employees.count() > 0) {
            log.info("seed skipped: employees already present");
            return;
        }
        Instant now = clock.instant();
        employees.save(build("ADMIN-001", "관리자", "1985-01-01", "인사팀", "팀장", "2015-03-02", "010-1000-0001", "서울특별시 서초구",
                Role.ADMIN, props.seed().adminPassword(), false, now));
        for (Seed s : SEED) {
            boolean submitAccount = s.id().equals("EMP-001");
            String pw = submitAccount ? props.seed().employeePassword() : "Temp-" + s.id() + "!1";
            employees.save(build(s.id(), s.name(), s.birth(), s.dept(), s.pos(), s.hire(), s.phone(), s.addr(),
                    Role.EMPLOYEE, pw, !submitAccount, now));
        }
        log.info("seed loaded: {} employees", employees.count());
    }

    private Employee build(String id, String name, String birth, String dept, String pos, String hire, String phone, String addr,
                           Role role, String rawPassword, boolean mustChange, Instant now) {
        String[] p = EmployeeService.parseName(name);
        return Employee.builder()
                .employeeId(id).name(name).lastName(p[0]).firstName(p[1])
                .birthDate(birth == null ? null : LocalDate.parse(birth))
                .phone(phone).address(addr).department(dept).position(pos).hireDate(LocalDate.parse(hire))
                .role(role).status(EmployeeStatus.ACTIVE)
                .passwordHash(encoder.encode(rawPassword)).mustChangePassword(mustChange)
                .failedLoginCount(0).locked(false).createdAt(now).updatedAt(now)
                .build();
    }
}
