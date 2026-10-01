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
 *
 * <p>{@code runId} 는 알림이 가리키는 회차다(Ruling 542) — 매니저 알림(노선·배치 변경)만 채우고 나머지는 키는 있고
 * 값이 {@code null} 이다. 매니저 앱이 눌러서 그 회차의 노선·운행 화면으로 가는 근거다.
 *
 * <p>{@code sentAt} 은 실제 발송 시각이 없는 행(미발송·발송 실패, {@code push_state != sent})에서
 * {@code createdAt} 으로 대신한다 — §5.17 {@code StaffNotificationItemResponse} 가 이미 같은 대체를
 * 쓰고 있고({@code NotificationLogRepository#searchForStaffLog} 의 {@code COALESCE} 와 짝), 이 절만
 * 대체가 빠져 있었다(API_SPEC §1.13). §3.12 는 {@code sent_at} 을 `●`(항상 값 있음)로 적어 두었으므로
 * 그 약속을 지키려면 여기도 같은 대체가 필요하다.
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
        boolean popup,
        Long runId) {

    public static NotificationItemResponse of(NotificationLog log) {
        return new NotificationItemResponse(
                log.getId(),
                lower(log.getType()),
                log.getTitle(),
                log.getBody(),
                log.getStudentId(),
                log.getStudentName(),
                log.getSentAt() != null ? log.getSentAt() : log.getCreatedAt(),
                log.getReadAt(),
                log.isPopup(),
                log.getRunId());
    }

    private static String lower(NotificationType type) {
        return type.name().toLowerCase(Locale.ROOT);
    }
}
