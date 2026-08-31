package src.backend.run.event;

import java.time.OffsetDateTime;

/**
 * 운행이 시작됐음을 알리는 도메인 이벤트(API_SPEC §4.4·§9.7, RUN-05·M-10) — {@code run_started}
 * 알림(관계자·학부모·학생 3대상)이 이 이벤트를 구독한다.
 *
 * <p>{@link RunRouteConfirmedEvent} 와 같은 형태다 — 회차 상태 전이(moving) 트랜잭션 안에서
 * 발행되며, 그 트랜잭션이 롤백되면 이 이벤트도 발행되지 않는다.
 */
public record RunStartedEvent(Long runId, Long academyId, OffsetDateTime startedAt) {
}
