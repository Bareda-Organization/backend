package src.backend.exception.dto;

import java.time.OffsetDateTime;

/** 비상 신고 확인(ack) 응답(목표 10). */
public record EmergencyAckResponse(Long emergencyId, OffsetDateTime ackedAt) {
}
