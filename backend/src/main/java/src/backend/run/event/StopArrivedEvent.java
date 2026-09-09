package src.backend.run.event;

import java.time.OffsetDateTime;

/**
 * 기사가 정차지 도착을 확정했음을 알리는 도메인 이벤트(API_SPEC §4.5·§7.1 {@code stop_arrived}) —
 * WebSocket 방송(T2 소유 목표 4·10)이 이 이벤트를 구독한다. §4.5 자체는 알림 트리거가 아니라(기사
 * 전용 조작) REST 응답만 갖고 있었는데, §7.1 이 4채널(학생·매니저·학원·관리자) 전부에 이 이벤트를
 * 요구해 {@link RunArrivalCommandService#arrive} 가 새로 발행한다.
 *
 * <p>{@code nextStopId} 는 최종 지점 도착 처리일 때 {@code null} 이다 — {@code RunArriveResponse
 * .NextStopResponse} 와 같은 의미(다음 정차 항목이 승하차지면 그 {@code stopId}, 경유지면
 * {@code waypointId}).
 */
public record StopArrivedEvent(Long runId, Long academyId, Long stopId, int seq, String name,
        OffsetDateTime arrivedAt, Long nextStopId) {
}
