package src.backend.account.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;

/** 재신청 요청(API_SPEC §2.4) — 학원을 다시 선택한다. */
public record ReapplyRequestPayload(@JsonProperty("academy_id") @NotBlank String academyId) {
}
