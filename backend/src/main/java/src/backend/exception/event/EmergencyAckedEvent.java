package src.backend.exception.event;

import java.time.OffsetDateTime;

/**
 * 학원 관계자·메인관리자의 확인(ack) 처리를 알리는 도메인 이벤트(EXC-04, Phase 11 T2 목표 10,
 * `Ruling 277` 로 {@code ackedByName} 추가) — 발신자(기사·동승자) 앱에 확인 사실을 되반영하는
 * WebSocket 방송만 이 이벤트를 구독한다. push 알림 {@code NotificationType} 에 확인 전용 값이
 * 없다(§9.7 표에 없음) — 그래서 이 이벤트는 {@code notification/domain/impl} 에 대응 컴포저가
 * 없고 WebSocket 방송 전용이다.
 *
 * <p>{@code ackedByName} 은 확인한 계정의 이름이다({@code API_SPEC §7.1} {@code emergency_acked}
 * 3필드 중 하나). 발행 지점({@link src.backend.exception.command.EmergencyCommandService#ack})이
 * 트랜잭션 안에서 채운다 — {@link EmergencyRaisedEvent#raisedBy()} 가 발행 지점에서 이름을 조회해
 * 싣는 것과 같은 자리다. 리스너는 {@code AFTER_COMMIT} 이후에 돌아 그 시점엔 트랜잭션이 없으므로
 * 이름 조회를 리스너로 미루지 않는다.
 */
public record EmergencyAckedEvent(Long emergencyId, Long academyId, Long runId, String ackedByName,
        OffsetDateTime ackedAt) {
}
