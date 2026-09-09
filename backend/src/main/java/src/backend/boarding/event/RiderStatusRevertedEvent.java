package src.backend.boarding.event;

import java.time.OffsetDateTime;

/**
 * 승하차 되돌리기(BRD-05, API_SPEC §4.7)로 이미 나간 승차·하차 알림을 정정해야 함을 알리는 도메인
 * 이벤트(목표 13·14, Ruling 219) — 학부모에게만 알린다.
 *
 * <p><b>확정된 정책(Ruling 219)</b> — 이미 나간 알림은 고치지 않고 새 알림을 발행한다. 그래서 이
 * 이벤트는 {@link RiderStatusChangedEvent} 처럼 "지금 상태"만 나르지 않고, {@code canceledStatus}
 * (되돌리기로 취소된 상태 — {@code BoardingCommandService#revert} 의 {@code fromStatus})를 함께
 * 나른다. 취소된 것이 승차인지 하차인지를 알아야 "승차 취소"·"하차 취소" 문구를 가를 수 있는데,
 * {@link RiderStatusChangedEvent#status()} 는 되돌아간 <i>결과</i> 값이라 이 정보를 담지 못한다
 * ({@code WAITING} 으로 되돌아간 것만으로는 승차를 취소한 것인지 애초에 아무 것도 안 한 것인지
 * 구별할 수 없다).
 *
 * <p>{@link RiderStatusChangedEvent} 를 고쳐 필드를 더 얹지 않고 별도 이벤트로 둔 이유 —
 * {@code rider_changed} WebSocket 방송({@link src.backend.global.websocket.RiderChangedBroadcastListener})
 * 이 {@link RiderStatusChangedEvent} 를 공유 구독하는데, 그 방송 계약(§7.1)은 "지금 상태"만 필요하고
 * "무엇이 취소됐는지"는 필요로 하지 않는다. 그 계약을 이 이벤트 신설을 이유로 건드리지 않기 위해
 * 정정 알림 전용 이벤트를 분리했다({@code BoardingCommandService#revert} 는 이 이벤트와
 * {@link RiderStatusChangedEvent} 둘 다 발행한다 — 전자는 정정 알림, 후자는 WebSocket 방송 재료).
 */
public record RiderStatusRevertedEvent(Long runId, Long academyId, Long studentId, Long runRiderId,
        String canceledStatus, String revertedToStatus, OffsetDateTime revertedAt) {
}
