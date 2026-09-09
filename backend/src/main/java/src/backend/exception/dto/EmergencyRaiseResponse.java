package src.backend.exception.dto;

import java.time.OffsetDateTime;

/** 비상 신고 접수 응답(목표 5) — 취소 호출({@code DELETE /runs/{runId}/emergency/{id}})에 쓸 id 를 준다. */
public record EmergencyRaiseResponse(Long emergencyId, OffsetDateTime receivedAt) {
}
