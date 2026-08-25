package src.backend.notification.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** 단말 등록 요청(API_SPEC §2.11). */
public record DeviceRegisterRequest(
        @NotBlank String token,
        @NotBlank @Pattern(regexp = "android|ios|web") String platform,
        @JsonProperty("device_id") @NotBlank String deviceId,
        @JsonProperty("app_version") String appVersion) {
}
