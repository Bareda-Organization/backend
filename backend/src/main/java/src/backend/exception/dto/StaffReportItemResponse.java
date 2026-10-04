package src.backend.exception.dto;

import java.time.OffsetDateTime;

/**
 * 예외 보고 1건(API_SPEC §5.20) — {@code studentName} 은 {@code type=guardian_absent} 일 때만 채워지고,
 * {@code handledAt}·{@code handledByName} 은 처리 표시({@code POST /staff/reports/{id}/handle}, Ruling 814)가 있을
 * 때만 채워진다. {@code reportedByRole} 은 보고자의 매니저 역할({@code driver}·{@code escort})이다.
 */
public record StaffReportItemResponse(
        Long reportId,
        String type,
        String memo,
        Long runId,
        String busNo,
        String studentName,
        String reportedBy,
        String reportedByRole,
        OffsetDateTime reportedAt,
        boolean handled,
        OffsetDateTime handledAt,
        String handledByName) {
}
