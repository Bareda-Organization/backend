package src.backend.exception.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 미승차 연락 시도 기록 요청(API_SPEC §4.8) — {@code attemptType}·{@code result} 는 문서화된
 * {@code enum} 이지만 {@link src.backend.boarding.dto.RiderStatusUpdateRequest} 와 같은 관례로
 * 문자열로 받는다. 잘못된 값을 자동 바인딩(400/500)이 아니라 서비스 계층이 {@code 422
 * VALIDATION_FAILED} 로 직접 판정해야 하기 때문이다.
 *
 * <p>{@code decision} 은 선택이다(§4.8 표) — 3분 경과 전 시도는 아직 최종 판단이 없을 수 있다.
 */
public record NoShowContactRequest(@NotBlank String attemptType, @NotBlank String result, String decision) {
}
