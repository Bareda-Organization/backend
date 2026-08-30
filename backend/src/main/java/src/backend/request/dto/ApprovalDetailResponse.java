package src.backend.request.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 승인 대기 1건 상세(API_SPEC §5.5 상세) — 목록 항목 전체 + 재최적화 결과.
 *
 * <p>{@link RouteDetailResponse}(고정 노선 상세)와 같은 <b>플랫(flat)</b> 형태다 — 요약을 별도 키로
 * 감싸지 않고 요약 필드를 그대로 펼친 뒤 상세 전용 필드를 더한다. 조립은 정적 팩토리
 * {@link #of(ApprovalSummaryResponse, RoutePreviewResponse, OffsetDateTime, OffsetDateTime, BigDecimal,
 * BigDecimal, List, ApprovalCapacityResponse, String, boolean)} 가 요약을 먼저 만들고 그 필드를
 * 복사하는 형태로 맡는다.
 */
public record ApprovalDetailResponse(
        Long approvalId,
        String source,
        String studentName,
        Long runId,
        String busNo,
        String direction,
        OffsetDateTime deadlineAt,
        String stopName,
        int remainingRiders,
        boolean willRemoveStop,
        OffsetDateTime requestedAt,
        RoutePreviewResponse routePreview,
        OffsetDateTime estTimeBefore,
        OffsetDateTime estTimeAfter,
        BigDecimal estDistanceBefore,
        BigDecimal estDistanceAfter,
        List<AffectedStudentResponse> affectedStudents,
        ApprovalCapacityResponse capacity,
        String previewToken,
        boolean previewStale) {

    public static ApprovalDetailResponse of(ApprovalSummaryResponse summary, RoutePreviewResponse routePreview,
            OffsetDateTime estTimeBefore, OffsetDateTime estTimeAfter, BigDecimal estDistanceBefore,
            BigDecimal estDistanceAfter, List<AffectedStudentResponse> affectedStudents,
            ApprovalCapacityResponse capacity, String previewToken, boolean previewStale) {
        return new ApprovalDetailResponse(summary.approvalId(), summary.source(), summary.studentName(),
                summary.runId(), summary.busNo(), summary.direction(), summary.deadlineAt(), summary.stopName(),
                summary.remainingRiders(), summary.willRemoveStop(), summary.requestedAt(), routePreview,
                estTimeBefore, estTimeAfter, estDistanceBefore, estDistanceAfter, List.copyOf(affectedStudents),
                capacity, previewToken, previewStale);
    }
}
