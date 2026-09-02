package src.backend.exception.event;

import java.time.OffsetDateTime;

/**
 * 현장 예외 보고 접수를 알리는 도메인 이벤트(EXC-02·03, API_SPEC §4.13) — {@code exception_reported}
 * 알림(§9.7)이 이 이벤트를 구독한다. 수신자는 §4.13 이 명시한 "관계자"(학원 재직 관계자 전원).
 *
 * <p>{@link src.backend.request.event.ApprovalRequestedEvent} 와 같은 형태의 평범한 record 다 —
 * {@code ExceptionReportCommandService} 의 저장 트랜잭션 안에서 발행되므로, 저장이 롤백되면 이
 * 이벤트도 함께 취소된다.
 */
public record ExceptionReportedEvent(Long reportId, Long academyId, Long runId, OffsetDateTime reportedAt) {
}
