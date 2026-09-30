package src.backend.run.event;

import java.time.OffsetDateTime;

/**
 * 승하차지 1건의 출발이 선점됐음을 알리는 도메인 이벤트(Ruling 308, docs/archive/rounds/be-rounds-r15-r21.md §8.23 T3 목표 2) — 그 승하차지의
 * 확정 결과(승차·하차·미승차)를 학생별로 1건씩 학부모에게 통지하는 재료다.
 *
 * <p>{@link src.backend.routing.repository.RunStopRepository#claimDeparture} 조건부 UPDATE 가
 * <b>실제로 1행을 갱신했을 때만</b> 발행된다(같은 정차지를 여러 경로 — 근접 스케줄러의 100m 이탈
 * 판정, 다음 승하차지 도착 시 폴백, 운행 종료 시 강제 적용(Ruling 312) — 가 중복으로 선점하려 해도
 * 이 이벤트는 최초 1회만 발행된다). {@code studentId} 를 담지 않는다 — 그 승하차지의 대상 학생
 * 전부를 이벤트 하나로 통지해야 해서, 수신 측이 {@code runId}·{@code stopId} 로 명단을 다시
 * 조회한다(그 시점의 <b>확정 결과</b>를 읽어야 하므로 이벤트 발행 시점 값을 스냅샷으로 들고 다니면
 * 안 된다).
 */
public record StopDepartedEvent(Long runId, Long academyId, Long stopId, OffsetDateTime departedAt) {
}
