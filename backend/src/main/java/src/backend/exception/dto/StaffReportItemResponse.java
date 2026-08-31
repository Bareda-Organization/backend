package src.backend.exception.dto;

import java.time.OffsetDateTime;

/**
 * 예외 보고 1건(API_SPEC §5.20) — {@code studentName} 은 {@code type=guardian_absent} 일 때만
 * 채워진다.
 *
 * <p>{@code handled}·{@code handledAt} 은 이 태스크에서 항상 {@code false}·{@code null} 이다 —
 * {@code exception_report} 테이블에 처리 여부 컬럼이 없다(§4.13 은 보고 자체까지만 다루고, 사후
 * 처리·인계 추적은 "2단계"로 미뤄져 있다). 필드를 응답에 남겨 둔 이유는 §5.20 이 명시한 응답
 * 스키마이기 때문이지, 값을 저장·갱신할 수단이 있어서가 아니다.
 */
public record StaffReportItemResponse(
        Long reportId,
        String type,
        String memo,
        Long runId,
        String busNo,
        String studentName,
        String reportedBy,
        OffsetDateTime reportedAt,
        boolean handled,
        OffsetDateTime handledAt) {
}
