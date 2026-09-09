package src.backend.request.event;

import java.time.OffsetDateTime;

/**
 * 탑승 의사가 즉시 반영됐음을 알리는 도메인 이벤트(ATT-01·02, API_SPEC §3.6 ①·③구간) —
 * {@code intent_changed} 알림(§9.7, 수신자는 관계자)이 이 이벤트를 구독한다.
 *
 * <p>{@link src.backend.run.event.RunRouteConfirmedEvent} 와 같은 형태의 평범한 record 다 —
 * {@code DomainEvent} 인터페이스를 구현하지 않고 발행측 트랜잭션 안에서 곧바로 발행한다.
 *
 * <p>{@code changedAt} 은 {@code dedup_key} 의 네 번째 자리(판정 시각)로 쓰인다 — 같은 학생이 같은
 * 회차에서 반복 토글하면(①구간은 여러 번 가능) 매번 다른 시각이 붙어야 두 번째 토글의 알림이 조용히
 * 차단되지 않는다({@code RunRouteConfirmedNotificationListener} 와 같은 근거).
 *
 * <p>②구간(승인 대기)은 이 이벤트가 아니라 {@link ApprovalRequestedEvent} 를 쓴다 — ①·③은 이미
 * 반영이 끝난 사실을 알리고, ②는 아직 반영되지 않은 "요청이 생겼다"는 다른 사실을 알리기 때문이다.
 */
public record IntentChangedEvent(Long runId, Long academyId, Long studentId, boolean riding,
        OffsetDateTime changedAt) {
}
