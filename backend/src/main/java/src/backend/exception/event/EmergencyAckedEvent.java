package src.backend.exception.event;

import java.time.OffsetDateTime;

/**
 * 학원 관계자·메인관리자의 확인(ack) 처리를 알리는 도메인 이벤트(EXC-04, Phase 11 T2 목표 10) —
 * 발신자(기사·동승자) 앱에 확인 사실을 되반영하는 WebSocket 방송만 이 이벤트를 구독한다. push
 * 알림 {@code NotificationType} 에 확인 전용 값이 없다(§9.7 표에 없음) — 그래서 이 이벤트는
 * {@code notification/domain/impl} 에 대응 컴포저가 없고 WebSocket 방송 전용이다.
 */
public record EmergencyAckedEvent(Long emergencyId, Long academyId, Long runId, Long ackedBy,
        OffsetDateTime ackedAt) {
}
