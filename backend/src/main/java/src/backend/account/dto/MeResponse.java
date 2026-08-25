package src.backend.account.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 본인 프로필(API_SPEC §2.10) — 역할별로 다른 부가 필드는 해당 없으면 {@code null} 이다. */
public record MeResponse(
        @JsonProperty("account_id") Long accountId,
        @JsonProperty("login_id") String loginId,
        String name,
        String phone,
        String role,
        String status,
        Academy academy,
        @JsonProperty("student_id") String studentId,
        @JsonProperty("manager_id") String managerId,
        @JsonProperty("manager_role") String managerRole,
        @JsonProperty("linked_student_count") Integer linkedStudentCount) {

    public record Academy(String id, String name) {
    }
}
