package src.backend.exception.dto;

import java.time.OffsetDateTime;

/**
 * 비상 신고 접수 응답(API_SPEC §4.14, BE-R1 목표 1) — 취소 호출({@code DELETE
 * /runs/{runId}/emergency/{id}})에 쓸 id 를 준다.
 *
 * <p>{@code emergencyId} 는 문자열이다 — 이 저장소의 확정 관례(§1.1, {@code
 * ManagerRunResponse} 등이 이미 같은 방식)를 따른다. {@code notified} 는 이 신고가 발송될
 * 수신자(학원 관계자 + 메인관리자) 수다.
 */
public record EmergencyRaiseResponse(String emergencyId, OffsetDateTime raisedAt, OffsetDateTime cancelableUntil,
        long notified) {
}
