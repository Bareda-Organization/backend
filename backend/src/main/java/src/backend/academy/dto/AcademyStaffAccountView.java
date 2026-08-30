package src.backend.academy.dto;

/**
 * 탑승 의사 변경 알림({@code intent_changed}·{@code approval_requested}, API_SPEC §9.7)의 수신자
 * 조회 프로젝션 — 두 알림 모두 수신 대상이 "관계자"(학원 재직 스태프 전원)라 특정 배정 없이
 * 학원 단위로 계정을 훑는다.
 *
 * <p>{@link AcademyStaffAccountResponse}(관리자 콘솔 상세 응답)를 재사용하지 않는 이유는 그 DTO가
 * 이미 콘솔 화면 계약이라 필드를 더하면 그 응답에도 실린다 — 이 알림 발송 전용 조회이지 응답
 * 계약이 아니다({@code AssignedManagerAccountView} 와 같은 근거).
 */
public record AcademyStaffAccountView(Long accountId, String name) {
}
