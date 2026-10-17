package src.backend.run.event;

import java.time.OffsetDateTime;

/**
 * <b>확정된({@code confirmed})</b> 회차가 취소됐음을 알리는 도메인 이벤트(SCH-03, Ruling 894 ⑤) — {@code run_canceled}
 * 알림이 이 이벤트를 구독해 배치된 기사·동승자에게 알린다. 확정 전({@code idle}) 회차의 취소에는 발행하지 않는다 — 매니저는
 * 확정 전 회차를 아직 모르므로 알릴 것이 없다.
 *
 * <p>취소와 같은 트랜잭션 안에서 발행되고 구독자도 그 안에서 적재하므로, 취소가 롤백되면 알림 행도 함께 사라진다.
 *
 * @param canceledAt 취소 시각 — 알림 중복 방지 키의 판정 시각 자리다
 */
public record RunCanceledEvent(Long runId, Long academyId, OffsetDateTime canceledAt) {
}
