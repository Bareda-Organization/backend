package src.backend.account.dto;

import java.time.OffsetDateTime;
import java.util.Locale;

import src.backend.global.common.enums.AccountStatus;

/**
 * 가입 요청 처리 결과(API_SPEC §5.2·§6.5) — 두 승인 축이 같은 형태로 응답한다.
 *
 * <p>{@code accountStatus} 를 enum 이 아니라 소문자 문자열로 싣는 이유는 §9.2 가 값 도메인을
 * 소문자로 정하기 때문이다 — {@link AccountStatus} 를 그대로 직렬화하면 {@code ACTIVE} 가 나간다.
 *
 * <p>JSON 필드명은 전역 {@code spring.jackson.property-naming-strategy: SNAKE_CASE}(Ruling 104)가 변환한다.
 */
public record SignupDecisionResponse(String accountStatus, OffsetDateTime decidedAt) {

    public static SignupDecisionResponse of(AccountStatus accountStatus, OffsetDateTime decidedAt) {
        return new SignupDecisionResponse(accountStatus.name().toLowerCase(Locale.ROOT), decidedAt);
    }
}
