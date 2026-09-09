package src.backend.account.dto;

/**
 * 본인 프로필(API_SPEC §2.10) — 역할별로 다른 부가 필드는 해당 없으면 {@code null} 이다.
 * JSON 필드명은 전역 {@code spring.jackson.property-naming-strategy: SNAKE_CASE}(Ruling 104)가
 * 자바 필드명에서 자동 변환한다 — 개별 {@code @JsonProperty} 는 붙이지 않는다.
 */
public record MeResponse(
        Long accountId,
        String loginId,
        String name,
        String phone,
        String role,
        String status,
        Academy academy,
        String studentId,
        String managerId,
        String managerRole,
        Integer linkedStudentCount) {

    public record Academy(String id, String name) {
    }
}
