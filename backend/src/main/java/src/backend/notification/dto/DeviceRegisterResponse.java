package src.backend.notification.dto;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 단말 등록 응답(API_SPEC §2.11, 201). */
public record DeviceRegisterResponse(
        @JsonProperty("device_id") String deviceId,
        @JsonProperty("registered_at") OffsetDateTime registeredAt) {
}
