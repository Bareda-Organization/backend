package src.backend.account.dto;

import java.time.OffsetDateTime;
import java.util.Locale;

import src.backend.account.entity.SignupRequest;
import src.backend.global.common.enums.Role;

/**
 * 관계자가 보는 가입 요청 1건(API_SPEC §5.1 {@code items[]}).
 *
 * <p>이름·연락처는 {@code signup_request} 가 아니라 {@code account} 가 보유하므로 두 값을 함께 받아
 * 조립한다 — 요청 행에 복제하면 신청자가 연락처를 바꿔도 큐가 옛 값을 계속 보여 준다.
 *
 * <p>{@code role} 은 소문자 문자열이다(§9.1) — {@link Role} 을 그대로 직렬화하면 {@code PARENT} 가 나간다.
 *
 * <p>JSON 필드명은 전역 {@code spring.jackson.property-naming-strategy: SNAKE_CASE}(Ruling 104)가 변환한다.
 */
public record SignupRequestSummaryResponse(Long requestId, String name, String role, String phone,
        OffsetDateTime requestedAt) {

    public static SignupRequestSummaryResponse of(SignupRequest request, String name, String phone) {
        return new SignupRequestSummaryResponse(request.getId(), name,
                request.getRequestedRole().name().toLowerCase(Locale.ROOT), phone, request.getRequestedAt());
    }
}
