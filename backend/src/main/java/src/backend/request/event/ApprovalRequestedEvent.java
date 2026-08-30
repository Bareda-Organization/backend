package src.backend.request.event;

import java.time.OffsetDateTime;

/**
 * ②구간 변경이 승인 대기 큐에 들어갔음을 알리는 도메인 이벤트(API_SPEC §3.6 ②) — {@code
 * approval_requested} 알림(§9.7, 수신자는 관계자)이 이 이벤트를 구독한다.
 *
 * <p>{@code changeRequestId} 를 실어 승인 화면으로 바로 이어지게 한다 — {@link IntentChangedEvent}
 * 와 갈라 둔 이유는 그 이벤트의 javadoc 을 본다. {@code requestedAt} 은 {@code dedup_key} 의 네 번째
 * 자리로 쓰인다({@link IntentChangedEvent#changedAt} 과 같은 근거).
 */
public record ApprovalRequestedEvent(Long changeRequestId, Long runId, Long academyId, Long studentId,
        OffsetDateTime requestedAt) {
}
