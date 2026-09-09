package src.backend.boarding.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 승하차 처리 요청(API_SPEC §4.6) — {@code status}·{@code verify_method} 는 {@code enum} 이라고
 * 문서화돼 있지만 {@link src.backend.request.dto.ChangeRequestCreateRequest} 등 기존 관례를 따라
 * 문자열로 받는다. 잘못된 값을 자동 바인딩(400/500)이 아니라 서비스 계층이
 * {@code 422 VALIDATION_FAILED} 로 직접 판정해야 하기 때문이다.
 *
 * <p>{@code clientKey} 는 {@link src.backend.boarding.entity.RiderStatusHistory#getClientKey()} 와
 * 저장 타입을 맞춰 {@code UUID} 로 받는다(엔티티 컬럼이 이미 {@code UUID}).
 */
public record RiderStatusUpdateRequest(@NotBlank String status, @NotBlank String verifyMethod,
        @NotNull UUID clientKey, OffsetDateTime occurredAt) {
}
