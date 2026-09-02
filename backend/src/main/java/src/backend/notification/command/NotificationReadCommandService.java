package src.backend.notification.command;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.notification.entity.NotificationLog;
import src.backend.notification.entity.NotificationType;
import src.backend.notification.repository.NotificationLogRepository;

/**
 * 알림 읽음 처리(API_SPEC §3.13, NTF-08) — 중요 통지는 이 처리가 수신 확인(NTF-10)의 근거다.
 *
 * <p>"중요 통지"는 {@code USER_FLOWS §10.2} 규칙4·5(지연 · 미승차 · 노선 변경)가 정한다 — 이 서비스가
 * 판정을 들고 있는 이유는 {@link NotificationType} 이 T4 전용 파일이라 엔티티·타입 쪽에 업무 중요도
 * 판단을 추가로 얹지 않기 위함이다. 관계자 알림 로그(§5.17 미확인 배지, T3 담당)가 {@code acked} 필드를
 * 그대로 읽으므로, 이 집합을 넓히거나 좁히면 그 배지의 대상도 함께 바뀐다 — 변경 시 T3 소유 코드까지
 * 함께 확인해야 한다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class NotificationReadCommandService {

    /** NTF-10 이 추적하는 중요 통지 3종(USER_FLOWS §10.2 규칙4·5) — 이 3종만 {@link NotificationLog#ack} 를 함께 부른다. */
    private static final Set<NotificationType> IMPORTANT_TYPES =
            Set.of(NotificationType.DELAY, NotificationType.NO_SHOW, NotificationType.ROUTE_CHANGED);

    private final NotificationLogRepository notificationLogRepository;

    private final Clock clock;

    /**
     * 읽음 처리(§3.13).
     *
     * @throws BusinessException {@code 404 NOTIFICATION_NOT_FOUND}(미존재) · {@code 403 FORBIDDEN}(타 계정 알림 —
     *     존재는 하나 {@code recipientAccountId} 가 다르다)
     */
    public void markRead(AuthUser requester, Long notificationId) {
        NotificationLog notification = notificationLogRepository.findById(notificationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND));
        if (!notification.getRecipientAccountId().equals(requester.accountId())
                || !notification.getAcademyId().equals(requester.academyId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        notification.markRead(now);
        if (IMPORTANT_TYPES.contains(notification.getType())) {
            notification.ack(now);
        }
    }
}
