package com.bitcom.portal.bgcheck.repository;

import com.bitcom.portal.bgcheck.BgcStatus;
import com.bitcom.portal.bgcheck.entity.BackgroundCheck;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BackgroundCheckRepository extends JpaRepository<BackgroundCheck, Long> {
    List<BackgroundCheck> findAllByEmployeeIdOrderByRequestedAtDesc(String employeeId);

    Optional<BackgroundCheck> findFirstByEmployeeIdOrderByRequestedAtDesc(String employeeId);

    boolean existsByEmployeeIdAndStatus(String employeeId, BgcStatus status);

    List<BackgroundCheck> findAllByStatus(BgcStatus status);

    List<BackgroundCheck> findAllByEmployeeId(String employeeId);
}
