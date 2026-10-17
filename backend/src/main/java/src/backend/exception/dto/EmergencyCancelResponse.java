package src.backend.exception.dto;

import java.time.OffsetDateTime;

/** 비상 신고 취소 응답. */
public record EmergencyCancelResponse(Long emergencyId, OffsetDateTime canceledAt) {
}
