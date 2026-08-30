package src.backend.request.dto;

import java.time.OffsetDateTime;
import java.util.Locale;

import src.backend.request.entity.ChangeRequest;
import src.backend.request.entity.ChangeRequestSource;
import src.backend.global.common.enums.Direction;

/**
 * 승인 대기 1건 요약(API_SPEC §5.5 목록) — 재최적화를 실행하지 않고 이미 저장된 값과 단순 집계만으로
 * 채운다({@code remainingRiders}·{@code willRemoveStop} 은 {@code run_rider} 행 수만 세면 나온다).
 *
 * <p>목록과 상세({@link ApprovalDetailResponse})를 가른 이유는 문서에 적힌 그대로다 — 재최적화는
 * 계산 비용이 커서, 대기 건 N개를 목록 한 번에 함께 계산하면 그 자리에서 N회 호출이 동기로 실행된다.
 */
public record ApprovalSummaryResponse(
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
        OffsetDateTime requestedAt) {

    public static ApprovalSummaryResponse of(ChangeRequest changeRequest, String studentName, String busNo,
            Direction direction, OffsetDateTime deadlineAt, String stopName, int remainingRiders,
            boolean willRemoveStop) {
        return new ApprovalSummaryResponse(changeRequest.getId(), lower(changeRequest.getSource()), studentName,
                changeRequest.getRunId(), busNo, lower(direction), deadlineAt, stopName, remainingRiders,
                willRemoveStop, changeRequest.getRequestedAt());
    }

    private static String lower(ChangeRequestSource source) {
        return source.name().toLowerCase(Locale.ROOT);
    }

    private static String lower(Direction direction) {
        return direction.name().toLowerCase(Locale.ROOT);
    }
}
