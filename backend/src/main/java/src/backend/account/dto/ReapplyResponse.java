package src.backend.account.dto;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 재신청 응답(API_SPEC §2.4) — 재신청 직후는 항상 {@code pending}. */
public record ReapplyResponse(String status, @JsonProperty("requested_at") OffsetDateTime requestedAt) {
}
