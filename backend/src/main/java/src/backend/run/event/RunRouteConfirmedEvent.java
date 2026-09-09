package src.backend.run.event;

import java.time.OffsetDateTime;

/**
 * 회차 노선이 확정됐음을 알리는 도메인 이벤트(RTE-08, 확정 배치 Phase 7) — {@code route_changed}
 * 계열 알림(Phase 7 T3 소유)이 이 이벤트를 구독한다.
 *
 * <p>{@link src.backend.account.event.SignupDecidedEvent} 와 같은 형태의 평범한 record 이며
 * {@code ApplicationEventPublisher.publishEvent} 로 곧바로 발행한다 — 구독자도 같은 프로세스 안에 있다.
 * 프로세스 밖으로 내보내야 할 일이 생기면 그때 발신 지점을 하나 더하고, 발행측은 그대로 둔다.
 *
 * <p>확정을 시도했다 실패해 롤백된 경우는 발행되지 않는다 — {@code RunConfirmationService.confirmOne}
 * 이 전체를 한 트랜잭션으로 묶어, 이 이벤트도 그 트랜잭션의 성공 안에서만 발행된다(목표 5).
 */
public record RunRouteConfirmedEvent(Long runId, Long academyId, Long busId, OffsetDateTime confirmedAt) {
}
