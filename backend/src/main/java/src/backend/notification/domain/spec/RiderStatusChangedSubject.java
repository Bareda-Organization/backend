package src.backend.notification.domain.spec;

/**
 * 승차·하차 알림 문구의 재료(BRD-01·02, API_SPEC §4.6) — {@link RiderStatusChangedComposer} 입력.
 *
 * <p>{@link src.backend.boarding.event.RiderStatusChangedEvent} 를 그대로 넘기지 않는다 — 그
 * 이벤트는 WebSocket {@code rider_changed} 방송과 공유하는 계약이라, 문구 전용 필드(자녀 이름,
 * ATT-03)를 얹어 그 계약을 건드리지 않는다({@link DelaySubject} 와 같은 근거).
 */
public record RiderStatusChangedSubject(String status, String studentName) {
}
