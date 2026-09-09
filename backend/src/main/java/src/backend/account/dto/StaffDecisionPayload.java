package src.backend.account.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 메인 관리자의 관계자 가입 요청 수락·거절 요청(API_SPEC §6.5).
 *
 * <p>{@link SignupDecisionPayload} 와 달리 {@code link} 가 부재하다 — 이 축의 "연결" 은 요청 본문이
 * 지정하는 것이 아니라 승인이 만드는 {@code academy_staff} 행 자체다. 한 DTO 로 겸하면 메인 관리자가
 * 학생·매니저 연결을 지정할 자리가 생겨, 관계자 축의 인가 경계가 요청 본문으로 새어 나간다.
 *
 * <p>JSON 필드명은 전역 {@code spring.jackson.property-naming-strategy: SNAKE_CASE}(Ruling 104)가 변환한다.
 */
public record StaffDecisionPayload(@NotNull Boolean accept, String rejectReason) {
}
