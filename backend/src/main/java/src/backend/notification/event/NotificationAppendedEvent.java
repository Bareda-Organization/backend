package src.backend.notification.event;

/**
 * 아웃박스에 발송 대기 행이 적재됐음을 알리는 <b>모듈 내부</b> 이벤트 — 커밋 후 즉시 발송의 계기다.
 *
 * <p>적재와 발송을 이벤트로 가른 이유는 둘의 트랜잭션 경계가 다르기 때문이다. 적재는 상태 변경과
 * 같은 트랜잭션 안이어야 하고 발송은 그 밖이어야 한다(TECH_DECISIONS §7.5) — 한 메서드에 두면
 * 발송 실패가 상태 변경을 롤백시킨다.
 *
 * <p>{@code urgent} 는 비상 종류(팝업) 여부다 — 일반 알림 100건 뒤에 서지 않도록 전용 발송 실행기로 갈라 보내는 기준이다
 * (R46 S-2).
 */
public record NotificationAppendedEvent(Long notificationId, boolean urgent) {
}
