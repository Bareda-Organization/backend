package src.backend.request.dto;

import jakarta.validation.constraints.NotNull;

/** 탑승 의사 토글 요청(API_SPEC §3.6 {@code PATCH /students/{id}/runs/{runId}/intent}). */
public record BoardingIntentToggleRequest(@NotNull Boolean riding) {
}
