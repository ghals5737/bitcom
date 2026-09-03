package com.bitcom.portal.employee.dto;

import com.bitcom.portal.employee.Role;
import com.bitcom.portal.employee.dto.AuthDtos.ChangePasswordRequest;
import com.bitcom.portal.employee.dto.AuthDtos.LoginRequest;
import com.bitcom.portal.employee.dto.EmployeeDtos.AdminUpdateEmployeeRequest;
import com.bitcom.portal.employee.dto.EmployeeDtos.CreateEmployeeRequest;
import com.bitcom.portal.employee.dto.EmployeeDtos.UpdateMeRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** 규칙 7: request DTO validation 의 경계(길이)·형식(허용 문자) 검증. 스프링 없이 Validator 만 사용. */
class DtoValidationTest {
    private static final Validator V = Validation.buildDefaultValidatorFactory().getValidator();

    private static Set<String> fields(Object dto) {
        return V.validate(dto).stream().map(v -> v.getPropertyPath().toString()).collect(Collectors.toSet());
    }

    // ---------- LoginRequest ----------

    @ParameterizedTest
    @ValueSource(strings = {"EMP-001", "ADMIN-001", "emp-001", "EMP-123456"})
    void login_accepts_valid_employee_id_shapes(String id) {
        assertThat(fields(new LoginRequest(id, "x"))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "EMP-01", "EMP-1234567", "EMP001", "EMP-001; DROP TABLE employees", "EMP-001'--", "홍길동", "EMP-001 OR 1=1"})
    void login_rejects_malformed_or_injection_like_ids(String id) {
        assertThat(fields(new LoginRequest(id, "x"))).contains("employeeId");
    }

    @Test
    void login_password_boundaries() {
        assertThat(fields(new LoginRequest("EMP-001", ""))).contains("password");
        assertThat(fields(new LoginRequest("EMP-001", "a"))).isEmpty();
        assertThat(fields(new LoginRequest("EMP-001", "a".repeat(100)))).isEmpty();
        assertThat(fields(new LoginRequest("EMP-001", "a".repeat(101)))).contains("password");
    }

    // ---------- ChangePasswordRequest ----------

    @ParameterizedTest
    @ValueSource(strings = {"Abcdef1!", "12345678!", "!!!!!!!1", "한글비밀번호1!"})
    void change_password_accepts_8_plus_with_digit_and_special(String pw) {
        assertThat(fields(new ChangePasswordRequest("cur", pw))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Abcdef1", "Abcdefg!", "Abcdefgh", "12345678", "!!!!!!!!", "", "Ab1!"})
    void change_password_rejects_short_or_missing_class(String pw) {
        assertThat(fields(new ChangePasswordRequest("cur", pw))).contains("newPassword");
    }

    @Test
    void change_password_max_100() {
        String ok = "a".repeat(98) + "1!";
        assertThat(fields(new ChangePasswordRequest("cur", ok))).isEmpty();
        assertThat(fields(new ChangePasswordRequest("cur", ok + "a"))).contains("newPassword");
        assertThat(fields(new ChangePasswordRequest("", "Abcdef1!"))).contains("currentPassword");
    }

    // ---------- CreateEmployeeRequest ----------

    private static CreateEmployeeRequest create(String name, String phone, String address, String dept, String pos) {
        return new CreateEmployeeRequest(name, null, phone, address, dept, pos, null, Role.EMPLOYEE);
    }

    @Test
    void create_name_korean_only_length_2_to_50() {
        assertThat(fields(create("김솔", null, null, null, null))).isEmpty();
        assertThat(fields(create("김", null, null, null, null))).contains("name");
        assertThat(fields(create("가".repeat(50), null, null, null, null))).isEmpty();
        assertThat(fields(create("가".repeat(51), null, null, null, null))).contains("name");
        assertThat(fields(create("John", null, null, null, null))).contains("name");
        assertThat(fields(create("김민준2", null, null, null, null))).contains("name");
        assertThat(fields(create("<script>", null, null, null, null))).contains("name");
        assertThat(fields(create("", null, null, null, null))).contains("name");
    }

    @Test
    void create_role_required() {
        assertThat(fields(new CreateEmployeeRequest("김솔", null, null, null, null, null, null, null))).contains("role");
    }

    @ParameterizedTest
    @ValueSource(strings = {"010-2000-0001", "+82 10 2000 0001", "(02) 123-4567", ""})
    void phone_accepts_digits_and_separators(String phone) {
        assertThat(fields(create("김솔", phone, null, null, null))).isEmpty();
        assertThat(fields(new UpdateMeRequest(phone, null))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"010-2000-000a", "010;2000", "010'2000", "전화", "0000000000000000000000000000000"})
    void phone_rejects_letters_injection_chars_and_over_30(String phone) {
        assertThat(fields(create("김솔", phone, null, null, null))).contains("phone");
        assertThat(fields(new UpdateMeRequest(phone, null))).contains("phone");
    }

    @ParameterizedTest
    @ValueSource(strings = {"서울특별시 강남구 테헤란로 1, 101-1001호", "Seoul, Gangnam-gu (Apt. 3)", ""})
    void free_text_accepts_normal_addresses(String text) {
        assertThat(fields(create("김솔", null, text, text, text))).isEmpty();
        assertThat(fields(new UpdateMeRequest(null, text))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"<b>서울</b>", "서울\"", "서울'", "서울;", "서울`"})
    void free_text_rejects_tag_quote_semicolon_backtick(String text) {
        assertThat(fields(create("김솔", null, text, null, null))).contains("address");
        assertThat(fields(new UpdateMeRequest(null, text))).contains("address");
        assertThat(fields(create("김솔", null, null, text, null))).contains("department");
        assertThat(fields(create("김솔", null, null, null, text))).contains("position");
    }

    @Test
    void free_text_length_boundaries() {
        assertThat(fields(create("김솔", null, "a".repeat(200), "b".repeat(50), "c".repeat(50)))).isEmpty();
        assertThat(fields(create("김솔", null, "a".repeat(201), "b".repeat(51), "c".repeat(51)))).containsExactlyInAnyOrder("address", "department", "position");
        assertThat(fields(new UpdateMeRequest(null, "a".repeat(201)))).contains("address");
    }

    // ---------- AdminUpdateEmployeeRequest ----------

    @Test
    void admin_update_all_null_is_valid_and_partial_invalid_is_flagged() {
        assertThat(fields(new AdminUpdateEmployeeRequest(null, null, null, null, null, null, null, null))).isEmpty();
        Set<ConstraintViolation<AdminUpdateEmployeeRequest>> v = V.validate(
                new AdminUpdateEmployeeRequest("X", null, "abc", "<x>", null, null, null, null));
        // "X" 는 길이(min 2)와 한글 규칙을 동시에 어겨 name 위반이 2건 → 필드 집합으로 비교
        assertThat(fields(new AdminUpdateEmployeeRequest("X", null, "abc", "<x>", null, null, null, null)))
                .containsExactlyInAnyOrder("name", "phone", "address");
        assertThat(v).hasSize(4);
    }
}
