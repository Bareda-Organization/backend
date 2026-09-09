package src.backend.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 단말 등록 요청(API_SPEC §2.11). JSON 필드명은 전역
 * {@code spring.jackson.property-naming-strategy: SNAKE_CASE}(Ruling 104)가 변환한다.
 */
public record DeviceRegisterRequest(
        @NotBlank String token,
        @NotBlank @Pattern(regexp = "android|ios|web") String platform,
        @NotBlank String deviceId,
        String appVersion) {
}
