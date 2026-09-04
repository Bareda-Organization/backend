package src.backend.run.event;

/**
 * {@link DelayRequestedEvent} 가 나르는 수신자 1행 — 관계자·학부모·학생 3집합이 전부 같은 모양을 쓴다.
 *
 * <p>{@code name} 을 값으로 담는 이유는 알림 모듈의 {@code NotificationDraft} 와 같다 —
 * {@code notification_log} 가 수신자 이름을 스냅샷으로 저장하므로 리스너가 다시 조회하지 않는다.
 *
 * <p>{@code dedupTargetId} 는 역할마다 다른 값이다 — 관계자·학생은 {@code accountId} 자신이지만,
 * 학부모는 <b>{@code studentId}</b> 다(한 계정이 형제자매를 함께 보호하면 {@code accountId} 로 dedup
 * 키를 잡을 때 두 학생의 지연 알림이 같은 키로 충돌한다).
 */
public record DelayNoticeRecipient(Long accountId, String name, Long dedupTargetId) {
}
