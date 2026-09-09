package src.backend.notification.dto;

import java.time.OffsetDateTime;
import java.util.Locale;

import src.backend.notification.entity.NotificationLog;
import src.backend.notification.entity.NotificationType;

/**
 * 알림 목록 항목 1건(API_SPEC §3.12 {@code items[]}).
 *
 * <p>{@code notificationId} · {@code studentId} 는 스펙 표가 {@code string} 으로 적었으나
 * {@code Long} 을 그대로 쓴다 — 이 코드베이스는 이미 같은 형태({@code my_stop_id} · {@code stop_id},
 * {@code StudentRouteResponse})에서 스펙의 "string" 표기를 문자 그대로 따르지 않고 다른 ID 필드와
 * 같은 {@code Long} 을 써 왔다. 이 한 필드만 {@code String} 으로 바꾸면 같은 응답 안에서 ID 표기가
 * 갈리므로, 기존 관례를 우선했다 — 스펙 표 자체가 압축 서식이라 여러 필드를 한 타입 칸에 묶는
 * 습관이 있다(§3.11 {@code run_id}·{@code bus_no} 도 같은 칸에서 "string").
 *
 * <p>{@code type} 은 enum 을 직접 노출하지 않고 소문자 문자열로 옮긴다({@code
 * ExceptionReportQueryService#lower} 와 같은 관례) — Jackson 이 enum 을 기본 대문자 이름으로 직렬화해,
 * 그대로 두면 DB 값(소문자)·다른 응답의 enum 표기와 어긋난다.
 */
public record NotificationItemResponse(
        Long notificationId,
        String type,
        String title,
        String body,
        Long studentId,
        String studentName,
        OffsetDateTime sentAt,
        OffsetDateTime readAt,
        boolean popup) {

    public static NotificationItemResponse of(NotificationLog log) {
        return new NotificationItemResponse(
                log.getId(),
                lower(log.getType()),
                log.getTitle(),
                log.getBody(),
                log.getStudentId(),
                log.getStudentName(),
                log.getSentAt(),
                log.getReadAt(),
                log.isPopup());
    }

    private static String lower(NotificationType type) {
        return type.name().toLowerCase(Locale.ROOT);
    }
}
