package src.backend.notification.dto;

import java.time.OffsetDateTime;

/**
 * 알림 로그 1건(API_SPEC §5.17).
 *
 * <p>{@code recipientRole}·{@code type} 은 소문자 문자열로 나간다({@code StaffReportItemResponse} 의
 * {@code lower(enum)} 관례와 같다). {@code sentAt} 은 실제 발송 시각이 없는 행({@code SKIPPED}·아직
 * 발송 전 {@code PENDING})에서 {@code createdAt} 으로 대신한다 — 이 목록은 "푸시 off 로 차단된 건도
 * 레코드로 존치" 가 요구조건이라(§5.17) 그런 행이 {@code sent_at} 부재로 화면에서 빈 값이 되는 것보다
 * 로그가 남은 시각을 보이는 편이 낫다는 판단이다({@code NotificationLogRepository#searchForStaffLog} 의
 * {@code COALESCE} 와 짝).
 */
public record StaffNotificationItemResponse(
        Long notificationId,
        OffsetDateTime sentAt,
        String busNo,
        String recipientName,
        String recipientRole,
        String type,
        String body,
        boolean acked) {
}
