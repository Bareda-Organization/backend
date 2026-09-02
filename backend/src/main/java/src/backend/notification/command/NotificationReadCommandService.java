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
 * <p>"중요 통지"는 {@code USER_FLOWS §10.2} 규칙4·5가 정한다 — <b>단, 이 두 규칙을 나란히 읽어야만
 * 나오는 목록이다.</b> 규칙4가 "중요 알림(지연 · 미승차 · 노선 변경)은 팝업 병행(NTF-09)"이라 적고,
 * 바로 다음 규칙5가 "중요 변경은 수신 확인 여부까지 추적(NTF-10)"이라 적는다 — **같은 "중요"를 가리키는
 * 인접 규칙**이라는 것이 이 3종을 고른 근거이며, "이것이 NTF-10 대상 목록이다"라고 별도로 못박은
 * 문장은 어느 정본 문서에도 없다({@code API_SPEC §5.17}도 {@code acked} 필드를 전 항목 공통으로만
 * 적고 대상 종류를 나열하지 않는다). 이 서비스가 판정을 들고 있는 이유는 {@link NotificationType} 이
 * T4 전용 파일이라 엔티티·타입 쪽에 업무 중요도 판단을 추가로 얹지 않기 위함이다.
 *
 * <p><b>{@code acked} 계약(T2 가 쓰고 T3 이 읽는다, Ruling 222)</b> — {@code notification_log.acked} 는
 * 모든 행에 항상 존재하는 {@code boolean}이고({@code API_SPEC §5.17} 필수 필드) 생성 시 기본값이
 * {@code false}다. {@link #markRead}가 대상 알림의 {@link NotificationType}이 {@link #IMPORTANT_TYPES}에
 * 속할 때만 {@code true}로 바꾸고 {@code acked_at}에 처리 시각을 남긴다 — 그 외 종류는 읽음 처리를
 * 거쳐도 {@code acked}가 {@code false}로 남는다. {@link NotificationLog#ack}가 이미 {@code true}인
 * 행에는 다시 손대지 않아(no-op) 첫 확인 시각이 이후 재요청으로 갱신되지 않는다. 관계자 알림 로그
 * ({@code GET /staff/notifications}, §5.17 미확인 배지, T3 담당)가 이 필드를 그대로 읽으므로,
 * {@link #IMPORTANT_TYPES}를 넓히거나 좁히면 그 배지의 대상도 함께 바뀐다 — 변경 시 T3 소유 코드까지
 * 함께 확인해야 한다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class NotificationReadCommandService {

    /** NTF-10 이 추적하는 중요 통지 3종(USER_FLOWS §10.2 규칙4·5) — 이 3종만 {@link NotificationLog#ack} 를 함께 부른다. */
    /**
     * 🔴 <b>세는 쪽과 공유한다</b> — {@link NotificationType#IMPORTANT_FOR_ACK} 하나만 본다.
     * 여기에 따로 목록을 두면 미확인 배지({@code GET /staff/notifications})와 어긋나고,
     * 어긋나도 양쪽 시험이 각자 통과해 아무 데서도 안 잡힌다(Ruling 227).
     */
    private static final Set<NotificationType> IMPORTANT_TYPES = NotificationType.IMPORTANT_FOR_ACK;

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
