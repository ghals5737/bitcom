package com.bitcom.portal.employee.repository;

import com.bitcom.portal.employee.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    List<AuditLog> findAllByTargetEmployeeIdOrderByCreatedAtDesc(String targetEmployeeId);
}
