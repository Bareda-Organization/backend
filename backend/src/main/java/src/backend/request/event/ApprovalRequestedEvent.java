package src.backend.request.event;

import java.time.OffsetDateTime;

/**
 * ②구간 변경 신청이 승인 대기로 접수됐음을 알리는 도메인 이벤트(REQ-05, P-06) — {@code approval_requested}
 * 알림(API_SPEC §9.7)이 이 이벤트를 구독한다.
 *
 * <p>{@link src.backend.run.event.RunRouteConfirmedEvent} 와 같은 형태의 평범한 record 다 —
 * {@code DomainEvent} 인터페이스를 구현하지 않고 {@code ApplicationEventPublisher.publishEvent} 로
 * 곧바로 발행한다. {@code ChangeRequestStore} 의 저장 트랜잭션 안에서 발행되므로, 저장이 롤백되면 이
 * 이벤트도 함께 취소된다.
 *
 * <p>WebSocket 방송({@code /ws/academy/{id}/live} 의 {@code approval_requested}, API_SPEC §7.1)은
 * 이 이벤트를 구독하지 않는다 — 그 채널은 Phase 10 소유다(Ruling 195). 이 이벤트의 유일한 구독자는
 * 푸시 알림 리스너다.
 */
public record ApprovalRequestedEvent(Long changeRequestId, Long academyId, Long runId, Long studentId,
        OffsetDateTime requestedAt) {
}
