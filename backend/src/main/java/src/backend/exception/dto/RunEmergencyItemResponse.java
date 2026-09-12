package src.backend.exception.dto;

import java.time.OffsetDateTime;

/**
 * 발신자가 자기 신고 상태를 조회하는 목록의 1건(§4.15) — {@code EmergencyStaffItemResponse} 와 달리
 * {@code acked_by} 를 중첩하지 않고 {@code acked_by_name} 평평한 필드로 둔다(정본 §4.15 응답 문면이
 * {@code acked_by_name} 하나만 명시한다 — 역할·연락처는 이 화면에 없다).
 *
 * <p>{@code emergencyId} 는 문자열이다 — {@code API_SPEC §1.1} "식별자 | 서버 발급 문자열" 이 모든
 * 식별자에 적용되는 공통 규칙이고, {@code EmergencyRaiseResponse}(§4.14)가 이미 같은 방식이다. 이
 * 값을 그대로 {@code DELETE /runs/{runId}/emergency/{id}} 에 넣으므로(§4.15 "발신 응답을 놓친 경우의
 * 재취득 경로") 두 응답의 타입이 갈리면 취소 요청이 깨진다.
 *
 * <p>{@code cancelableUntil} 은 {@link src.backend.exception.entity.EmergencyAlert#cancelableUntil}
 * 을 그대로 옮긴다 — {@code EmergencyCommandService#cancel} 의 취소 판정과 같은 창(1분)을 쓴다(두
 * 곳이 다른 규칙으로 계산되지 않는다).
 */
public record RunEmergencyItemResponse(String emergencyId, String type, OffsetDateTime raisedAt,
        OffsetDateTime cancelableUntil, boolean acked, OffsetDateTime ackedAt, String ackedByName,
        OffsetDateTime canceledAt) {
}
