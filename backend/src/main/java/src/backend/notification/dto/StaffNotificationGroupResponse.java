package src.backend.notification.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 알림 로그 묶음 1개(API_SPEC §5.17 {@code group=true}, Ruling 813) — 같은 사건이 적재한 행(같은 {@code type} · {@code run_id} ·
 * {@code body} · 적재 시각 초 단위)을 한 항목으로 묶는다. {@code recipients} 는 앞 3명(적재 순)이고 전체 수는
 * {@code recipientCount} 다.
 */
public record StaffNotificationGroupResponse(String groupKey, OffsetDateTime sentAt, String busNo, String type,
        String body, long recipientCount, long ackedCount, List<Recipient> recipients) {

    /** 묶음의 수신자 한 명 — 이름과 역할. */
    public record Recipient(String recipientName, String recipientRole) {
    }
}
