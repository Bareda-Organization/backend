package src.backend.request.dto;

import java.time.OffsetDateTime;
import java.util.Locale;

import src.backend.request.entity.ChangeRequest;

/**
 * 변경 신청 이력 한 건(API_SPEC §3.9 {@code GET /students/{id}/change-requests}).
 *
 * <p>{@code status} 는 4가지 값을 그대로 문자열로 실어 보낸다(대기·승인·거절·자동거절) — 목록 조회는
 * 판정을 가하지 않고 저장된 상태를 그대로 비춘다.
 */
public record ChangeRequestResponse(Long changeRequestId, String type, String status, String rejectReason,
        Long runId, OffsetDateTime requestedAt, OffsetDateTime decidedAt) {

    public static ChangeRequestResponse from(ChangeRequest changeRequest) {
        return new ChangeRequestResponse(changeRequest.getId(),
                changeRequest.getType().name().toLowerCase(Locale.ROOT),
                changeRequest.getStatus().name().toLowerCase(Locale.ROOT), changeRequest.getRejectReason(),
                changeRequest.getRunId(), changeRequest.getRequestedAt(), changeRequest.getDecidedAt());
    }
}
