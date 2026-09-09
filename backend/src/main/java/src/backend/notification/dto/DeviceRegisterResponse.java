package src.backend.notification.dto;

import java.time.OffsetDateTime;

/**
 * 단말 등록 응답(API_SPEC §2.11, 201). JSON 필드명은 전역
 * {@code spring.jackson.property-naming-strategy: SNAKE_CASE}(Ruling 104)가 변환한다.
 */
public record DeviceRegisterResponse(String deviceId, OffsetDateTime registeredAt) {
}
