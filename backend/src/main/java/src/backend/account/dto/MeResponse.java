package src.backend.account.dto;

/**
 * 본인 프로필(API_SPEC §2.10) — 역할별로 다른 부가 필드는 해당 없으면 {@code null} 이다.
 * JSON 필드명은 전역 {@code spring.jackson.property-naming-strategy: SNAKE_CASE}(Ruling 104)가
 * 자바 필드명에서 자동 변환한다 — 개별 {@code @JsonProperty} 는 붙이지 않는다.
 *
 * <p>필드가 12개로 {@code CODE_CONVENTIONS §20.2} 기준을 넘는다(BR-101) — API_SPEC §2.10 이 역할 5종의 부가
 * 필드를 전부 한 응답에 실으라고 정한 표라, 역할별로 타입을 가르면 클라이언트가 응답 형태를 역할마다
 * 분기해야 한다. 사양이 이미 하나의 평평한 응답으로 정한 것을 코드가 다시 나누지 않는다.
 *
 * <p>{@code mustChangePassword} 는 임시 비밀번호 강제 변경 표식이다(Ruling 540) — 앱 재실행 때 로그인 응답 없이도
 * 변경 화면으로 보내려고 {@code GET /me} 에도 싣는다. 역할과 무관하게 항상 값이 있다.
 */
public record MeResponse(
        String accountId,
        String loginId,
        String name,
        String phone,
        String role,
        String status,
        Academy academy,
        String studentId,
        String managerId,
        String managerRole,
        Integer linkedStudentCount,
        boolean mustChangePassword) {

    /** {@code contact} 는 학원 대표 연락처 — 미등록이면 {@code null}(API_SPEC §2.10, Ruling 460). */
    public record Academy(String id, String name, String contact) {
    }
}
