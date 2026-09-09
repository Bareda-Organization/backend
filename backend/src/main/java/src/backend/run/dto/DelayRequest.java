package src.backend.run.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 지연 알림 신고 요청(NTF-06, API_SPEC §4.9). {@code reason} 을 문자열로 받는 이유는
 * {@code EmergencyRaiseRequest#type} 과 같다 — 잘못된 값을 자동 바인딩(400)이 아니라 서비스 계층이
 * {@code 422 VALIDATION_FAILED} 로 판정해야 한다. {@code minutes} 도 같은 이유로 5분 단위 검증을
 * 서비스 계층에 둔다(빈 검증 애너테이션만으로는 "5의 배수" 를 표현할 수 없다).
 */
public record DelayRequest(@NotNull Integer minutes, @NotBlank String reason, String message) {
}
