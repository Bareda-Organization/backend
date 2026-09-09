package src.backend.run.event;

import java.time.OffsetDateTime;

/**
 * 회차 노선이 확정됐음을 알리는 도메인 이벤트(RTE-08, 확정 배치 Phase 7) — {@code route_changed}
 * 계열 알림(Phase 7 T3 소유)이 이 이벤트를 구독한다.
 *
 * <p>{@link src.backend.account.event.SignupDecidedEvent} 와 같은 형태의 평범한 record 다 —
 * {@code DomainEvent} 인터페이스를 구현하지 않고 {@code ApplicationEventPublisher.publishEvent}
 * 로 곧바로 발행한다. {@code DomainEvent}+{@code TransactionalDomainEventRelay} 조합은 커밋 후
 * Kafka 로 중계해야 하는 이벤트 전용이고, 이 이벤트를 구독자가 인프로세스로 받을지 Kafka 로 받을지는
 * <b>구독자(T3)의 선택</b>이라 발행측이 미리 못박지 않는다.
 *
 * <p>확정을 시도했다 실패해 롤백된 경우는 발행되지 않는다 — {@code RunConfirmationService.confirmOne}
 * 이 전체를 한 트랜잭션으로 묶어, 이 이벤트도 그 트랜잭션의 성공 안에서만 발행된다(목표 5).
 */
public record RunRouteConfirmedEvent(Long runId, Long academyId, Long busId, OffsetDateTime confirmedAt) {
}
