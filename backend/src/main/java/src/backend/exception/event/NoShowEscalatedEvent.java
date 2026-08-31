package src.backend.exception.event;

import java.time.OffsetDateTime;

/**
 * 미승차 대기가 만료됐는데도 무응답이라 관계자에게 에스컬레이션됐음을 알리는 도메인 이벤트(목표 1,
 * API_SPEC §4.8) — {@code no_show_escalated} 알림(관계자 앞)이 이 이벤트를 구독한다.
 *
 * <p>{@link src.backend.request.event.ChangeRequestAutoRejectedEvent} 와 같은 형태의 평범한
 * record 다 — {@code NoShowEscalationPersistence.escalateOne} 이 조건부 UPDATE
 * ({@code NoShowCaseRepository#escalateIfDue}) 로 갱신에 성공한 트랜잭션 안에서만 발행한다.
 * {@code NoShowCase} 자신은 {@code runRiderId} 만 들고 있어 발행측이 {@code RunRider} → {@code Run}
 * 을 거쳐 {@code academyId} · {@code studentId} · {@code runId} 를 미리 채워 넣는다 — 구독자
 * ({@code NoShowEscalationNotificationListener})가 다시 조회하지 않게 하기 위함이다.
 */
public record NoShowEscalatedEvent(Long academyId, Long caseId, Long runId, Long studentId,
        OffsetDateTime escalatedAt) {
}
