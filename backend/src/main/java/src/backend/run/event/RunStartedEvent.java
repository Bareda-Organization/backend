package src.backend.run.event;

import java.time.OffsetDateTime;

/**
 * 운행이 시작됐음을 알리는 도메인 이벤트(API_SPEC §4.4·§9.7, RUN-05·M-10) — {@code run_started}
 * 알림(관계자·학부모·학생 3대상)과 WebSocket {@code run_started} 방송(§7.1, T2 소유 목표 4·10)이
 * 이 이벤트를 구독한다.
 *
 * <p>{@link RunRouteConfirmedEvent} 와 같은 형태다 — 회차 상태 전이(moving) 트랜잭션 안에서
 * 발행되며, 그 트랜잭션이 롤백되면 이 이벤트도 발행되지 않는다.
 *
 * <p>{@code autoBoardedCount} 는 §7.1 {@code run_started} payload 의 필수 필드다 — 발행 시점에
 * {@code RunStartCommandService.start} 가 이미 계산해 둔 값을 그대로 threading 한다(새 조회 없음).
 * 자동 탑승이 없는 방향(등원 외 회차)에서는 {@code 0} 이다.
 */
public record RunStartedEvent(Long runId, Long academyId, OffsetDateTime startedAt, int autoBoardedCount) {
}
