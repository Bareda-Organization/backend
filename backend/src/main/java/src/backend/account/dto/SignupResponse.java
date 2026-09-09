package src.backend.account.dto;

import java.time.OffsetDateTime;
import java.util.Locale;

import src.backend.account.entity.ApproverType;
import src.backend.global.common.enums.AccountStatus;

/**
 * 회원가입 응답(API_SPEC §2.2, 201) — 승인 전이라 {@code account_status} 는 항상 {@code pending}.
 * JSON 필드명은 전역 {@code spring.jackson.property-naming-strategy: SNAKE_CASE}(Ruling 104)가 변환한다.
 */
public record SignupResponse(
        String accountStatus,
        OffsetDateTime requestedAt,
        String approver) {

    public static SignupResponse of(AccountStatus status, OffsetDateTime requestedAt, ApproverType approverType) {
        return new SignupResponse(status.name().toLowerCase(Locale.ROOT), requestedAt,
                approverType.name().toLowerCase(Locale.ROOT));
    }
}
