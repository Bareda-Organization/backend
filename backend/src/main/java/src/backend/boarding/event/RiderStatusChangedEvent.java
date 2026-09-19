package src.backend.boarding.event;

import java.time.OffsetDateTime;

/**
 * 승차·하차가 반영됐음을 알리는 도메인 이벤트(BRD-01·02, API_SPEC §4.6) — {@code rider_changed}
 * WebSocket 방송({@link src.backend.global.websocket.RiderChangedBroadcastListener})의 재료다.
 *
 * <p>{@code no_show} 는 이 이벤트를 쓰지 않는다({@link RiderNoShowEvent} 참고) — 학부모·관계자 둘
 * 다에게 각각 다른 문구로 보내야 해서 수신 대상 수 자체가 다르다. {@code alighted} 뒤 관계자가 보는
 * "실시간 현황 갱신"(§4.6 표)은 WebSocket·대시보드 갱신이라 이 알림 로그 적재 대상이 아니다.
 *
 * <p>{@link src.backend.request.event.IntentChangedEvent} 와 같은 형태의 평범한 record 다.
 *
 * <p>{@code reverted} — 되돌리기({@code BoardingCommandService#revert})도 실제 상태가 바뀌었으므로
 * {@code rider_changed} 방송 재료로 이 이벤트를 그대로 재사용한다(그쪽은 이 필드를 읽지 않는다 —
 * 방송 계약은 바뀌지 않는다). 학부모 승하차 알림({@code BoardingNotificationListener})은 더 이상
 * 이 이벤트를 구독하지 않는다 — 출발 시점의 확정 결과만 통지하도록 옮겨 갔다(Ruling 308,
 * §8.23 T3). 이 필드는 그 이관 전 되돌리기 기원 이벤트를 걸러내던 흔적으로 남아 있다.
 */
public record RiderStatusChangedEvent(Long runId, Long academyId, Long studentId, Long runRiderId,
        String status, OffsetDateTime changedAt, boolean reverted) {
}
