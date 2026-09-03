package com.bitcom.portal.employee.repository;

import com.bitcom.portal.employee.entity.Session;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;

public interface SessionRepository extends JpaRepository<Session, String> {
    @Modifying
    @Query("DELETE FROM Session s WHERE s.employeeId = :employeeId")
    int deleteAllByEmployeeId(String employeeId);

    @Modifying
    @Query("DELETE FROM Session s WHERE s.expiresAt < :now")
    int deleteExpired(Instant now);
}
