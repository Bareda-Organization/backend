package src.backend.account.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 관계자의 가입 요청 수락·거절 요청(API_SPEC §5.2).
 *
 * <p>{@code rejectReason}·{@code link} 는 <b>조건부</b> 필수라 Bean Validation 으로 표현하지 않는다 —
 * 필수 여부가 {@code accept} 값과 승인 대상의 역할에 달려 있어, 애너테이션으로 적으면 두 조건 중
 * 하나만 표현되고 나머지는 서비스에 남아 규칙이 두 곳으로 갈린다. 대신 {@code 422 VALIDATION_FAILED}
 * ·{@code 422 LINK_REQUIRED} 로 서비스가 함께 판정한다.
 *
 * <p>JSON 필드명은 전역 {@code spring.jackson.property-naming-strategy: SNAKE_CASE}(Ruling 104)가 변환한다.
 */
public record SignupDecisionPayload(@NotNull Boolean accept, String rejectReason, SignupLinkPayload link) {
}
