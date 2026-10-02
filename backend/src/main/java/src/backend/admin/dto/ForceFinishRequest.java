package src.backend.admin.dto;

import jakarta.validation.constraints.NotBlank;

/** 끝나지 않은 이동 중 회차 강제 종료 요청(API_SPEC §6.17, Ruling 724) — {@code reason} 이 공백뿐이면 {@code 422 VALIDATION_FAILED} 다. */
public record ForceFinishRequest(@NotBlank String reason) {
}
