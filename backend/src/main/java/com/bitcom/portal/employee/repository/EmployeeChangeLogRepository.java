package com.bitcom.portal.employee.repository;

import com.bitcom.portal.employee.entity.EmployeeChangeLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EmployeeChangeLogRepository extends JpaRepository<EmployeeChangeLog, Long> {
    List<EmployeeChangeLog> findAllByEmployeeIdOrderByChangedAtDesc(String employeeId);
}
