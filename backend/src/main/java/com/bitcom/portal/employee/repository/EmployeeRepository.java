package com.bitcom.portal.employee.repository;

import com.bitcom.portal.employee.EmployeeStatus;
import com.bitcom.portal.employee.entity.Employee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface EmployeeRepository extends JpaRepository<Employee, String> {
    List<Employee> findAllByOrderByEmployeeIdAsc();

    List<Employee> findAllByStatusOrderByEmployeeIdAsc(EmployeeStatus status);

    /** EMP- 접두어 안에서 가장 큰 번호 (F4 채번) */
    @Query(value = "SELECT MAX(CAST(SUBSTRING(employee_id FROM 5) AS INTEGER)) FROM employees WHERE employee_id ~ '^EMP-[0-9]+$'", nativeQuery = true)
    Optional<Integer> findMaxEmpNumber();
}
