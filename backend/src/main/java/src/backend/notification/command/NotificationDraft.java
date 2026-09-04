package src.backend.notification.command;

import src.backend.global.common.enums.Role;
import src.backend.notification.entity.NotificationType;

/**
 * 아웃박스에 적재할 알림 한 건의 내용 — 어떤 사건이 알림을 만들었는지는 담지 않는다.
 *
 * <p>수신자 이름·역할을 <b>값으로</b> 받는 이유는 {@code notification_log} 가 그 둘을 스냅샷으로
 * 들고 있기 때문이다(ERD §3.4) — 계정이 지워져도 로그가 읽혀야 해 조회 시점에 다시 잇지 않는다.
 */
public record NotificationDraft(Long academyId, Long recipientAccountId, String recipientName,
        Role recipientRole, NotificationType type, String title, String body, String dedupKey) {
}
