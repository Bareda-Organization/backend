package src.backend.exception.dto;

import java.time.OffsetDateTime;

/**
 * 발신자가 자기 신고 상태를 조회하는 목록의 1건(§4.15) — {@code EmergencyStaffItemResponse} 와 달리
 * {@code acked_by} 를 중첩하지 않고 {@code acked_by_name} 평평한 필드로 둔다(정본 §4.15 응답 문면이
 * {@code acked_by_name} 하나만 명시한다 — 역할·연락처는 이 화면에 없다).
 *
 * <p>{@code cancelableUntil} 은 {@link src.backend.exception.entity.EmergencyAlert#cancelableUntil}
 * 을 그대로 옮긴다 — {@code EmergencyCommandService#cancel} 의 취소 판정과 같은 창(1분)을 쓴다(두
 * 곳이 다른 규칙으로 계산되지 않는다).
 */
public record RunEmergencyItemResponse(Long emergencyId, String type, OffsetDateTime raisedAt,
        OffsetDateTime cancelableUntil, boolean acked, OffsetDateTime ackedAt, String ackedByName,
        OffsetDateTime canceledAt) {
}
