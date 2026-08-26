package src.backend.notification.push.spec;

import src.backend.notification.entity.NotificationLog;
import src.backend.notification.entity.NotificationType;

/**
 * 발송 포트에 넘기는 한 건의 푸시 — {@link PushSender} 구현체(FCM · APNs · 알림톡)가 채널에 맞게
 * 옮겨 담는다.
 *
 * <p>{@link NotificationLog} 를 그대로 넘기지 않는 이유는 그러면 채널 구현체가 아웃박스 컬럼
 * ({@code push_state} · {@code push_attempts} · {@code dedup_key})까지 손댈 수 있게 되기 때문이다 —
 * 발송 상태를 옮기는 것은 {@code NotificationDispatcher} 의 일이지 채널의 일이 아니다.
 */
public record PushMessage(Long recipientAccountId, NotificationType type, String title, String body,
        boolean popup) {

    /** 적재된 아웃박스 행에서 채널이 실제로 쓰는 값만 뽑는다. */
    public static PushMessage from(NotificationLog log) {
        return new PushMessage(log.getRecipientAccountId(), log.getType(), log.getTitle(), log.getBody(),
                log.isPopup());
    }
}
